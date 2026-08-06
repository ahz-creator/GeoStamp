package com.axiominfratech.geostamp.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.databinding.ActivityGalleryBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ── Data ──────────────────────────────────────────────────────────────────────
data class GeoPhoto(
    val uri: Uri, val name: String, val dateMs: Long,
    val lat: Double = 0.0, val lon: Double = 0.0,
    val siteId: String = "", val operator: String = "",
    val distanceM: Double = 0.0, val gpsVerified: Boolean = false,
    val accuracy: Double = 0.0, val city: String = "",
    val workspaceMode: String = "organization",
    val locationIntegrityRisk: Boolean = false,
    val verificationId: String = "",
    val imageSha256: String = "",
    val evidenceStatus: String = "UNAVAILABLE",
    val sameDayPriorPhotos: Int = 0,
    val photosInLast90Days: Int = 0,
    val visitSessionsLast90Days: Int = 0,
    val lastPreviousVisitMs: Long = 0L,
    val trustedHistoryPhotos: Int = 0,
    val warningHistoryPhotos: Int = 0,
    val riskHistoryPhotos: Int = 0
)

data class SiteAlbum(
    val siteId: String, val operator: String, val distanceM: Double,
    val photos: List<GeoPhoto>
) {
    val verifiedPct: Int get() {
        if (photos.isEmpty()) return 0
        return (photos.count { it.gpsVerified && !it.locationIntegrityRisk && it.imageSha256.isNotBlank() } * 100) / photos.size
    }
    val lastVisitMs: Long get() = photos.maxOfOrNull { it.dateMs } ?: 0L
}

// ── GPS status ────────────────────────────────────────────────────────────────
private fun GeoPhoto.gpsColor(): Int = when {
    locationIntegrityRisk           -> 0xFFEF4444.toInt()  // Red — integrity risk
    gpsVerified && accuracy <= 15.0 -> 0xFF22C55E.toInt()  // Green
    gpsVerified                     -> 0xFFF59E0B.toInt()  // Yellow — weak
    else                            -> 0xFFEF4444.toInt()  // Red — invalid
}
private fun GeoPhoto.gpsLabel(): String = when {
    locationIntegrityRisk           -> "F  RISK"
    evidenceStatus == "FAIL"        -> "✗ FAILED"
    evidenceStatus == "WARNING"     -> "! WARNING"
    gpsVerified && imageSha256.isNotBlank() && accuracy <= 15.0 -> "✓ REGISTERED"
    gpsVerified                     -> "! PENDING"
    else                            -> "✗ GPS"
}

// ── Activity ──────────────────────────────────────────────────────────────────
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    private enum class Tab { BY_SITE, TODAY, THIS_WEEK, SEARCH }
    private var currentTab = Tab.BY_SITE
    private var searchQuery = ""
    private var allPhotos = listOf<GeoPhoto>()
    private var inAlbumDetail = false
    private var currentAlbumDetail: SiteAlbum? = null

    // Selection
    private var selectionMode = false
    private val selectedUris = mutableSetOf<Uri>()

    // Adapters
    private lateinit var siteAlbumAdapter: SiteAlbumAdapter
    private lateinit var photoGridAdapter: SelectablePhotoAdapter

    private val sdfDate = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val sdfFull = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupAdapters(); setupChips(); setupSearch(); setupSelectionBar()
        binding.btnBack.setOnClickListener { onBackPressedDispatch() }
        binding.btnBreadcrumbBack.setOnClickListener { exitAlbumDetail() }
        loadPhotos()
    }

    private fun onBackPressedDispatch() {
        when {
            selectionMode   -> exitSelectionMode()
            inAlbumDetail   -> exitAlbumDetail()
            currentTab == Tab.SEARCH -> exitSearch()
            else            -> finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { onBackPressedDispatch() }

    // ── Adapters ──────────────────────────────────────────────────────────────
    private fun setupAdapters() {
        siteAlbumAdapter = SiteAlbumAdapter(
            onShareClick   = { album -> showShareDialog(album) },
            onViewAllClick = { album -> openAlbumDetail(album) },
            onPhotoLongPress = { photo -> enterSelectionMode(photo) },
            onPhotoClick   = { photo ->
                if (selectionMode) toggleSelection(photo) else openPhoto(photo)
            },
            getSelectionMode = { selectionMode },
            isSelected = { uri -> selectedUris.contains(uri) }
        )
        photoGridAdapter = SelectablePhotoAdapter(
            onPhotoClick = { photo ->
                if (selectionMode) toggleSelection(photo) else openPhoto(photo)
            },
            onPhotoLongPress = { photo -> enterSelectionMode(photo) },
            getSelectionMode = { selectionMode },
            isSelected = { uri -> selectedUris.contains(uri) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = siteAlbumAdapter
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private val chipMap by lazy {
        mapOf(
            Tab.BY_SITE  to binding.chipBySite,
            Tab.TODAY    to binding.chipToday,
            Tab.THIS_WEEK to binding.chipWeek,
            Tab.SEARCH   to binding.chipSearch
        )
    }

    private fun setupChips() {
        binding.chipBySite.setOnClickListener  { switchTab(Tab.BY_SITE) }
        binding.chipToday.setOnClickListener   { switchTab(Tab.TODAY) }
        binding.chipWeek.setOnClickListener    { switchTab(Tab.THIS_WEEK) }
        binding.chipSearch.setOnClickListener  { toggleSearch() }
    }

    private fun switchTab(tab: Tab) {
        if (tab == Tab.SEARCH) { toggleSearch(); return }
        exitSearch(quiet = true); exitAlbumDetail()
        currentTab = tab
        updateChipUI(tab)
        applyFilter()
    }

    private fun updateChipUI(activeTab: Tab) {
        chipMap.forEach { (tab, view) ->
            val isActive = tab == activeTab
            view.setBackgroundResource(if (isActive) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
            val tv = view.getChildAt(0) as? TextView
            tv?.setTextColor(if (isActive) 0xFF07090F.toInt() else 0xFF64748B.toInt())
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter()
            }
        })
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText(""); searchQuery = ""; applyFilter()
        }
    }

    private fun toggleSearch() {
        if (currentTab == Tab.SEARCH) { exitSearch(); return }
        currentTab = Tab.SEARCH
        updateChipUI(Tab.SEARCH)
        binding.searchBarContainer.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSearch, 0)
        applyFilter()
    }

    private fun exitSearch(quiet: Boolean = false) {
        if (currentTab != Tab.SEARCH && binding.searchBarContainer.visibility == View.GONE) return
        currentTab = Tab.BY_SITE; searchQuery = ""
        binding.etSearch.setText("")
        binding.searchBarContainer.visibility = View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        if (!quiet) { updateChipUI(Tab.BY_SITE); applyFilter() }
    }

    // ── Filter & display ──────────────────────────────────────────────────────
    private fun applyFilter() {
        if (inAlbumDetail) return
        // Smooth crossfade on tab switch
        binding.recyclerView.animate().alpha(0f).setDuration(80).withEndAction {
            applyFilterInternal()
            binding.recyclerView.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun applyFilterInternal() {
        if (inAlbumDetail) return
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L; val weekMs = 7 * dayMs

        val filtered = when {
            currentTab == Tab.SEARCH && searchQuery.isNotEmpty() -> {
                val q = searchQuery.lowercase()
                allPhotos.filter {
                    it.siteId.lowercase().contains(q) ||
                    it.operator.lowercase().contains(q) ||
                    it.city.lowercase().contains(q) ||
                    it.name.lowercase().contains(q) ||
                    it.verificationId.lowercase().contains(q)
                }
            }
            currentTab == Tab.TODAY     -> allPhotos.filter { now - it.dateMs < dayMs }
            currentTab == Tab.THIS_WEEK -> allPhotos.filter { now - it.dateMs < weekMs }
            else                        -> allPhotos
        }

        val showGrid = currentTab == Tab.TODAY || currentTab == Tab.THIS_WEEK ||
                      (currentTab == Tab.SEARCH && searchQuery.isNotEmpty())

        if (showGrid) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
            binding.recyclerView.adapter = photoGridAdapter
            photoGridAdapter.update(filtered)
        } else {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = siteAlbumAdapter
            siteAlbumAdapter.update(buildAlbums(filtered))
        }

        binding.emptyState.visibility   = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filtered.isEmpty()) View.GONE  else View.VISIBLE
        if (filtered.isNotEmpty()) {
            binding.recyclerView.alpha = 0.7f
            binding.recyclerView.animate().alpha(1f).setDuration(180).start()
        }
        updateStats(filtered)
    }

    private fun buildAlbums(photos: List<GeoPhoto>): List<SiteAlbum> {
        // Organization evidence is grouped by Site ID. Personal evidence is grouped
        // by Project + Reference, while displaying the Project as the album title.
        val groups = photos.groupBy { photo ->
            if (photo.workspaceMode == "personal") {
                "PERSONAL|${photo.operator.ifBlank { "Personal" }}|${photo.siteId.ifBlank { "New Evidence" }}"
            } else {
                "ORG|${photo.siteId.ifBlank { "UNASSIGNED" }}"
            }
        }
        return groups.values.map { ph ->
            val sorted = ph.sortedByDescending { it.dateMs }
            val first = sorted.first()
            if (first.workspaceMode == "personal") {
                SiteAlbum(
                    siteId = first.operator.ifBlank { "Personal" },
                    operator = "Reference: ${first.siteId.ifBlank { "New Evidence" }}",
                    distanceM = 0.0,
                    photos = sorted
                )
            } else {
                SiteAlbum(first.siteId.ifBlank { "UNASSIGNED" }, first.operator, first.distanceM, sorted)
            }
        }.sortedByDescending { it.lastVisitMs }
    }

    private fun updateStats(photos: List<GeoPhoto>) {
        val uniqueSites = photos.map { it.siteId }.filter { it.isNotBlank() }.toSet().size
        val now = System.currentTimeMillis()
        val todayCount = photos.count { now - it.dateMs < 86_400_000L }
        binding.tvPhotoPill.text    = "${photos.size} Photos"
        binding.tvStatPhotos.text   = "${photos.size}"
        binding.tvStatSites.text    = "$uniqueSites"
        binding.tvStatVerified.text = "$todayCount"
    }

    // ── Album detail ──────────────────────────────────────────────────────────
    private fun openAlbumDetail(album: SiteAlbum) {
        inAlbumDetail = true; currentAlbumDetail = album
        binding.albumBreadcrumb.visibility = View.VISIBLE
        binding.tvAlbumLabel.text = album.siteId
        binding.tvAlbumCount.text = "${album.photos.size} photos"
        binding.tvGalleryTitle.text = "SITE ALBUM"
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = photoGridAdapter
        photoGridAdapter.update(album.photos)
        updateStats(album.photos)
    }

    private fun exitAlbumDetail() {
        if (!inAlbumDetail) return
        inAlbumDetail = false; currentAlbumDetail = null
        binding.albumBreadcrumb.visibility = View.GONE
        binding.tvGalleryTitle.text = "EVIDENCE GALLERY"
        applyFilter()
    }

    // ── Selection ─────────────────────────────────────────────────────────────
    private fun setupSelectionBar() {
        binding.btnSelectAll.setOnClickListener {
            val currentList = currentAlbumDetail?.photos ?: allPhotos
            selectedUris.addAll(currentList.map { it.uri })
            refreshSelectionUI()
        }
        binding.btnDeleteSelected.setOnClickListener { confirmDelete() }
        binding.btnShareSelected.setOnClickListener  { shareSelectedPhotos() }
        binding.btnExportSelected.setOnClickListener { exportSelectedAsPdf() }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
    }

    private fun enterSelectionMode(firstPhoto: GeoPhoto) {
        selectionMode = true
        selectedUris.add(firstPhoto.uri)
        refreshSelectionUI()
    }

    private fun exitSelectionMode() {
        selectionMode = false; selectedUris.clear()
        binding.selectionActionBar.visibility = View.GONE
        siteAlbumAdapter.notifyDataSetChanged()
        photoGridAdapter.notifyDataSetChanged()
    }

    private fun toggleSelection(photo: GeoPhoto) {
        if (selectedUris.contains(photo.uri)) selectedUris.remove(photo.uri)
        else selectedUris.add(photo.uri)
        if (selectedUris.isEmpty()) exitSelectionMode() else refreshSelectionUI()
    }

    private fun animateCardPress(v: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.96f),
                ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.96f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, "scaleX", 0.96f, 1f),
                ObjectAnimator.ofFloat(v, "scaleY", 0.96f, 1f)
            )
            duration = 180; interpolator = OvershootInterpolator(2f)
        }
        scaleDown.start()
        scaleDown.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { scaleUp.start() }
        })
    }

    private fun refreshSelectionUI() {
        binding.selectionActionBar.visibility = View.VISIBLE
        binding.tvSelectedCount.text = "${selectedUris.size} Selected"
        siteAlbumAdapter.notifyDataSetChanged()
        photoGridAdapter.notifyDataSetChanged()
    }

    // ── Open photo ────────────────────────────────────────────────────────────
    private fun openPhoto(photo: GeoPhoto) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, photo.uri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open photo", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Share dialog ──────────────────────────────────────────────────────────
    private fun showShareDialog(album: SiteAlbum) {
        val sheet = BottomSheetDialog(this, R.style.DarkBottomSheetDialog)
        val view  = layoutInflater.inflate(R.layout.bottom_sheet_share, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.tv_share_title).text = "Share Site Album"
        view.findViewById<TextView>(R.id.tv_share_subtitle).text =
            "${album.siteId}  ·  ${album.photos.size} Photos"

        view.findViewById<View>(R.id.btn_share_photos).setOnClickListener {
            sheet.dismiss(); shareAlbumAsPhotos(album)
        }
        view.findViewById<View>(R.id.btn_share_zip).setOnClickListener {
            sheet.dismiss(); shareAlbumAsZip(album)
        }
        view.findViewById<View>(R.id.btn_share_cancel).setOnClickListener {
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun shareAlbumAsPhotos(album: SiteAlbum) {
        val uris = ArrayList(album.photos.map { it.uri })
        startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun shareSelectedPhotos() {
        val selected = allPhotos.filter { selectedUris.contains(it.uri) }
        if (selected.isEmpty()) return
        val uris = ArrayList(selected.map { it.uri })
        startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun shareAlbumAsPdf(album: SiteAlbum) {
        lifecycleScope.launch {
            Toast.makeText(this@GalleryActivity, "Generating PDF report…", Toast.LENGTH_SHORT).show()
            val file = withContext(Dispatchers.IO) { generatePdf(album) }
            if (file != null) shareFile(file, "application/pdf") else
                Toast.makeText(this@GalleryActivity, "PDF generation failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAlbumAsZip(album: SiteAlbum) {
        lifecycleScope.launch {
            Toast.makeText(this@GalleryActivity, "Creating ZIP archive…", Toast.LENGTH_SHORT).show()
            val file = withContext(Dispatchers.IO) { generateZip(album) }
            if (file != null) shareFile(file, "application/zip") else
                Toast.makeText(this@GalleryActivity, "ZIP creation failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportSelectedAsPdf() {
        val selected = allPhotos.filter { selectedUris.contains(it.uri) }
        if (selected.isEmpty()) return
        val fakeAlbum = SiteAlbum("Selected Photos", "", 0.0, selected)
        shareAlbumAsPdf(fakeAlbum)
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedUris.size} photo(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteSelected() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelected() {
        var deleted = 0
        selectedUris.forEach { uri ->
            try { contentResolver.delete(uri, null, null); deleted++ } catch (_: Exception) {}
        }
        Toast.makeText(this, "Deleted $deleted photo(s)", Toast.LENGTH_SHORT).show()
        exitSelectionMode()
        loadPhotos()
    }

    // ── PDF generation ────────────────────────────────────────────────────────
    private fun generatePdf(album: SiteAlbum): File? = try {
        val exportDir = File(filesDir, "gallery_exports").also { it.mkdirs() }
        val outFile   = File(exportDir, "GeoStamp_${album.siteId}_Report.pdf")
        val doc       = PdfDocument()
        val W = 595; val H = 842  // A4 portrait

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 22f; isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF94A3B8.toInt(); textSize = 11f }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22D3EE.toInt(); textSize = 12f; isFakeBoldText = true }
        val bgPaint  = Paint().apply { color = 0xFF07090F.toInt() }
        val cardPaint = Paint().apply { color = 0xFF0E1829.toInt() }
        val linePaint = Paint().apply { color = 0xFF1E293B.toInt(); strokeWidth = 1f }

        var pageNum = 1
        val photosPerPage = 6  // 3×2 grid
        val totalPages = 1 + Math.ceil(album.photos.size / photosPerPage.toDouble()).toInt()

        // ── Cover page ─────────────────────────────────────────────────────
        val pageInfo = PdfDocument.PageInfo.Builder(W, H, pageNum++).create()
        val page = doc.startPage(pageInfo)
        val cv = page.canvas

        // Background
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)

        // Header band
        val headerPaint = Paint().apply { color = 0xFF0D1120.toInt() }
        cv.drawRect(0f, 0f, W.toFloat(), 80f, headerPaint)

        // Title
        cv.drawText("GeoStamp Evidence Report", 30f, 38f, titlePaint.apply { textSize = 18f; color = Color.WHITE })
        cv.drawText("Axiom Infratech · Telecom Field Documentation", 30f, 58f, subPaint)

        // Divider
        cv.drawLine(0f, 80f, W.toFloat(), 80f, linePaint)

        // Site info card
        val cardRect = RectF(20f, 100f, W - 20f, 280f)
        cv.drawRoundRect(cardRect, 8f, 8f, cardPaint)
        cv.drawRoundRect(cardRect, 8f, 8f, linePaint.apply { style = Paint.Style.STROKE })

        // Cyan accent bar
        val accentBar = Paint().apply { color = 0xFF22D3EE.toInt() }
        cv.drawRect(20f, 100f, 26f, 280f, accentBar)

        var y = 130f
        cv.drawText("SITE ID",   40f, y, subPaint.apply { textSize = 9f; color = 0xFF64748B.toInt() }); y += 18f
        cv.drawText(album.siteId.ifBlank { "–" }, 40f, y, accentPaint.apply { textSize = 20f }); y += 30f
        cv.drawText("OPERATOR",  40f, y, subPaint); y += 18f
        cv.drawText(album.operator.ifBlank { "–" }, 40f, y, bodyPaint); y += 30f
        cv.drawText("DISTANCE",  40f, y, subPaint); y += 18f
        val dist = if (album.distanceM >= 1000) "%.1f km".format(album.distanceM / 1000)
                   else "%.0f m".format(album.distanceM)
        cv.drawText(dist.ifBlank { "–" }, 40f, y, bodyPaint); y += 30f
        cv.drawText("CAPTURE STATUS",  40f, y, subPaint); y += 18f
        val registeredCount = album.photos.count {
            it.gpsVerified && !it.locationIntegrityRisk && it.imageSha256.isNotBlank()
        }
        val pendingCount = (album.photos.size - registeredCount).coerceAtLeast(0)
        cv.drawText("$registeredCount registered · $pendingCount pending",
            40f, y, bodyPaint.apply { color = 0xFF22D3EE.toInt() })

        // Stats summary
        y = 310f
        cv.drawText("SUMMARY", 30f, y, subPaint.apply { textSize = 9f; color = 0xFF64748B.toInt() }); y += 20f
        cv.drawText("Total Photos:   ${album.photos.size}", 30f, y, bodyPaint.apply { color = Color.WHITE; textSize = 12f }); y += 18f
        cv.drawText("Last Visit:     ${sdfFull.format(Date(album.lastVisitMs))}", 30f, y, bodyPaint); y += 18f
        val firstVisit = album.photos.minOfOrNull { it.dateMs } ?: 0L
        cv.drawText("First Visit:    ${sdfFull.format(Date(firstVisit))}", 30f, y, bodyPaint); y += 18f
        val latestHistory = album.photos.maxByOrNull { it.dateMs }
        cv.drawText("Earlier Today:  ${latestHistory?.sameDayPriorPhotos ?: 0} photos", 30f, y, bodyPaint); y += 18f
        cv.drawText("Last 90 Days:   ${latestHistory?.photosInLast90Days ?: 0} photos / ${latestHistory?.visitSessionsLast90Days ?: 0} visits", 30f, y, bodyPaint); y += 18f
        cv.drawText("Visit History:  ${latestHistory?.photosInLast90Days ?: 0} photos across ${latestHistory?.visitSessionsLast90Days ?: 0} visits", 30f, y, bodyPaint); y += 18f

        // Footer
        cv.drawLine(0f, H - 40f, W.toFloat(), H - 40f, linePaint.apply { style = Paint.Style.FILL; color = 0xFF1E293B.toInt() })
        cv.drawText("Generated by GeoStamp  ·  Axiom Infratech  ·  ${sdfFull.format(Date())}",
            30f, H - 18f, subPaint.apply { textSize = 9f })
        doc.finishPage(page)

        // ── Photo pages ────────────────────────────────────────────────────
        val thumbW = (W - 80) / 3; val thumbH = 140
        album.photos.chunked(photosPerPage).forEach { chunk ->
            val photoPageInfo = PdfDocument.PageInfo.Builder(W, H, pageNum++).create()
            val pp = doc.startPage(photoPageInfo)
            val pc = pp.canvas
            pc.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)
            pc.drawRect(0f, 0f, W.toFloat(), 44f, headerPaint)
            pc.drawText("${album.siteId} · Photo Evidence",
                20f, 28f, titlePaint.apply { textSize = 14f; color = Color.WHITE })
            pc.drawLine(0f, 44f, W.toFloat(), 44f, linePaint.apply { style = Paint.Style.FILL; color = 0xFF1E293B.toInt() })

            var row = 0; var col = 0
            chunk.forEach { photo ->
                val left = 20 + col * (thumbW + 10)
                val top  = 60 + row * (thumbH + 60)
                try {
                    contentResolver.openInputStream(photo.uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            val scaled = Bitmap.createScaledBitmap(bmp, thumbW, thumbH, true)
                            pc.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
                            bmp.recycle(); scaled.recycle()
                        }
                    }
                } catch (_: Exception) {}

                // Photo info below thumbnail
                val infoY = (top + thumbH + 14).toFloat()
                val gpsColor = photo.gpsColor()
                val gpsBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gpsColor }
                pc.drawText(photo.gpsLabel(), left.toFloat(), infoY, gpsBadgePaint.apply { textSize = 9f; isFakeBoldText = true })
                if (photo.lat != 0.0 || photo.lon != 0.0) {
                    val coords = "%.4f°%s %.4f°%s".format(
                        Math.abs(photo.lat), if (photo.lat >= 0) "N" else "S",
                        Math.abs(photo.lon), if (photo.lon >= 0) "E" else "W")
                    pc.drawText(coords, left.toFloat(), infoY + 12f,
                        subPaint.apply { textSize = 8f; color = 0xFF9DD9F3.toInt() })
                }
                pc.drawText(SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(photo.dateMs)),
                    left.toFloat(), infoY + 24f, subPaint.apply { color = 0xFF64748B.toInt() })

                col++; if (col >= 3) { col = 0; row++ }
            }
            pc.drawText("Page $pageNum of $totalPages · GeoStamp · Axiom Infratech",
                20f, H - 14f, subPaint.apply { textSize = 8f; color = 0xFF475569.toInt() })
            doc.finishPage(pp)
        }

        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    } catch (e: Exception) { null }

    // ── ZIP generation ────────────────────────────────────────────────────────
    private fun generateZip(album: SiteAlbum): File? = try {
        val exportDir = File(filesDir, "gallery_exports").also { it.mkdirs() }
        val outFile   = File(exportDir, "GeoStamp_${album.siteId}.zip")
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            album.photos.forEachIndexed { i, photo ->
                try {
                    contentResolver.openInputStream(photo.uri)?.use { stream ->
                        zos.putNextEntry(ZipEntry("${album.siteId}_photo_${i + 1}.jpg"))
                        stream.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (_: Exception) {}
            }
            // Include metadata JSON
            val meta = buildString {
                appendLine("GeoStamp Evidence Report")
                appendLine("Site: ${album.siteId}")
                appendLine("Operator: ${album.operator}")
                appendLine("Photos: ${album.photos.size}")
                val registeredCount = album.photos.count { it.gpsVerified && !it.locationIntegrityRisk && it.imageSha256.isNotBlank() }
                appendLine("Capture status: $registeredCount registered · ${album.photos.size - registeredCount} pending")
                appendLine("Generated: ${sdfFull.format(Date())}")
                appendLine()
                album.photos.forEach { p ->
                    appendLine("${p.name} | ${sdfFull.format(Date(p.dateMs))} | ${p.gpsLabel()} | ${p.verificationId} | %.4f %.4f".format(p.lat, p.lon))
                    if (p.imageSha256.isNotBlank()) appendLine("SHA-256: ${p.imageSha256}")
                    appendLine("History: ${p.sameDayPriorPhotos} earlier today | ${p.photosInLast90Days} photos / ${p.visitSessionsLast90Days} visits in 90 days")
                }
            }
            zos.putNextEntry(ZipEntry("evidence_manifest.txt"))
            zos.write(meta.toByteArray()); zos.closeEntry()
        }
        outFile
    } catch (_: Exception) { null }

    // ── Load from MediaStore ──────────────────────────────────────────────────
    private fun loadPhotos() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            allPhotos = withContext(Dispatchers.IO) { loadFromMediaStore() }
            binding.progressBar.visibility = View.GONE
            if (allPhotos.isEmpty()) {
                binding.emptyState.visibility   = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyState.visibility   = View.GONE
            }
            applyFilter()
        }
    }

    private fun loadFromMediaStore(): List<GeoPhoto> {
        val photos     = mutableListOf<GeoPhoto>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else @Suppress("DEPRECATION") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj  = arrayOf(MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.DATE_ADDED)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val metaDir = File(filesDir, "gallery_meta")

        contentResolver.query(collection, proj, "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("GeoStamp_%"), order)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nmCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dtCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id   = c.getLong(idCol); val name = c.getString(nmCol)
                val date = c.getLong(dtCol) * 1000L
                val uri  = ContentUris.withAppendedId(collection, id)
                val meta = loadMeta(metaDir, name)
                photos += GeoPhoto(uri, name, date,
                    lat         = meta.optDouble("lat", 0.0),
                    lon         = meta.optDouble("lon", 0.0),
                    accuracy    = meta.optDouble("accuracy", 0.0),
                    siteId      = meta.optString("siteId", ""),
                    operator    = meta.optString("operator", ""),
                    distanceM   = meta.optDouble("distanceM", 0.0),
                    gpsVerified = meta.optBoolean("gpsVerified", false),
                    city        = meta.optString("city", ""),
                    workspaceMode = meta.optString("workspaceMode", "organization"),
                    locationIntegrityRisk = meta.optBoolean("locationIntegrityRisk", false),
                    verificationId = meta.optString("verificationId", ""),
                    imageSha256 = meta.optString("imageSha256", ""),
                    evidenceStatus = meta.optString("evidenceStatus", "UNAVAILABLE"),
                    sameDayPriorPhotos = meta.optInt("sameDayPriorPhotos", 0),
                    photosInLast90Days = meta.optInt("photosInLast90Days", 0),
                    visitSessionsLast90Days = meta.optInt("visitSessionsLast90Days", 0),
                    lastPreviousVisitMs = meta.optLong("lastPreviousVisitMs", 0L),
                    trustedHistoryPhotos = meta.optInt("trustedHistoryPhotos", 0),
                    warningHistoryPhotos = meta.optInt("warningHistoryPhotos", 0),
                    riskHistoryPhotos = meta.optInt("riskHistoryPhotos", 0))
            }
        }
        return photos
    }

    private fun loadMeta(dir: File, name: String): JSONObject {
        val base = name.removeSuffix(".jpg").removeSuffix(".jpeg").removeSuffix(".png")
        val f = File(dir, "$base.meta")
        return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }
}

// ── Site Album Adapter ────────────────────────────────────────────────────────
class SiteAlbumAdapter(
    private val onShareClick:     (SiteAlbum) -> Unit,
    private val onViewAllClick:   (SiteAlbum) -> Unit,
    private val onPhotoLongPress: (GeoPhoto)  -> Unit,
    private val onPhotoClick:     (GeoPhoto)  -> Unit,
    private val getSelectionMode: () -> Boolean,
    private val isSelected:       (Uri) -> Boolean
) : RecyclerView.Adapter<SiteAlbumAdapter.VH>() {

    private var items = listOf<SiteAlbum>()
    private val sdfDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

    fun update(newItems: List<SiteAlbum>) { items = newItems; notifyDataSetChanged() }

    inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
        val cover:       ImageView = v.findViewById(R.id.iv_album_cover)
        val siteId:      TextView  = v.findViewById(R.id.tv_site_id_header)
        val operator:    TextView  = v.findViewById(R.id.tv_album_operator)
        val distance:    TextView  = v.findViewById(R.id.tv_album_distance)
        val photoCount:  TextView  = v.findViewById(R.id.tv_album_photo_count)
        val lastVisit:   TextView  = v.findViewById(R.id.tv_last_visit)
        val progress:    com.google.android.material.progressindicator.CircularProgressIndicator =
            v.findViewById(R.id.progress_verification)
        val verifyPct:   TextView  = v.findViewById(R.id.tv_verification_pct)
        val verifyLabel: TextView  = v.findViewById(R.id.tv_verification_label)
        val shareBtn:    View      = v.findViewById(R.id.btn_share_album)
        val strip:       RecyclerView = v.findViewById(R.id.rv_photo_strip)
        val viewAll:     View      = v.findViewById(R.id.btn_view_all)
        val viewAllTv:   TextView  = v.findViewById(R.id.tv_view_all)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        LayoutInflater.from(p.context).inflate(R.layout.item_site_album_card, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val album = items[pos]

        // ── Entry animation — subtle lift ─────────────────────────────
        h.itemView.alpha = 0f
        h.itemView.translationY = 30f
        h.itemView.animate().alpha(1f).translationY(0f)
            .setDuration(280).setStartDelay((pos * 40L).coerceAtMost(200))
            .setInterpolator(DecelerateInterpolator()).start()

        // Cover
        Glide.with(h.cover).load(album.photos.firstOrNull()?.uri)
            .centerCrop().placeholder(android.R.drawable.ic_menu_gallery).into(h.cover)

        val isUnassigned = album.siteId == "UNASSIGNED" || album.siteId.isBlank()
        h.siteId.text = if (isUnassigned) "⚠  UNASSIGNED" else album.siteId
        h.siteId.setTextColor(if (isUnassigned) 0xFFF59E0B.toInt() else 0xFFF1F5F9.toInt())
        // Amber border for unassigned via tag
        h.itemView.tag = if (isUnassigned) "unassigned" else null
        h.operator.text = if (isUnassigned) "GPS Verification Failed" else album.operator.ifBlank { "–" }
        h.operator.setTextColor(if (isUnassigned) 0xFFEF4444.toInt() else 0xFF22D3EE.toInt())
        h.distance.text = if (album.distanceM > 0) {
            " · " + if (album.distanceM >= 1000) "%.1fkm away".format(album.distanceM / 1000)
                    else "%.0fm away".format(album.distanceM)
        } else ""
        h.photoCount.text = "${album.photos.size} Photo${if (album.photos.size != 1) "s" else ""}"
        val latest = album.photos.maxByOrNull { it.dateMs }
        val previousVisit = latest?.lastPreviousVisitMs?.takeIf { it > 0L }
        h.lastVisit.text = buildString {
            append(if (previousVisit != null) "Previous visit: ${sdfDate.format(Date(previousVisit))}" else "First recorded visit")
            if ((latest?.photosInLast90Days ?: 0) > 0) {
                append("  ·  Previous 90 days: ${latest?.photosInLast90Days ?: 0} photos across ${latest?.visitSessionsLast90Days ?: 0} visits")
            }
        }

        // Field-staff operational circle: captures made today at this site.
        val now = System.currentTimeMillis()
        val todayCount = album.photos.count { now - it.dateMs < 86_400_000L }
        h.progress.progress = 0
        val ringTarget = if (todayCount > 0) 100 else 0
        ObjectAnimator.ofInt(h.progress, "progress", 0, ringTarget).apply {
            duration = 500
            startDelay = (pos * 40L).coerceAtMost(200) + 150
            interpolator = DecelerateInterpolator()
            start()
        }
        val operationalColor = if (todayCount > 0) 0xFF22D3EE.toInt() else 0xFF475569.toInt()
        h.progress.setIndicatorColor(operationalColor)
        h.verifyPct.text = "$todayCount"
        h.verifyLabel.text = "TODAY"
        h.verifyPct.setTextColor(0xFFFFFFFF.toInt())
        h.verifyPct.setShadowLayer(8f, 0f, 0f, operationalColor)

        h.shareBtn.setOnClickListener {
            it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()
            onShareClick(album)
        }
        h.viewAllTv.text = "View all ${album.photos.size} photos"
        h.viewAll.setOnClickListener { onViewAllClick(album) }

        // Photo strip inner RecyclerView
        val stripAdapter = PhotoStripAdapter(album.photos.take(8),
            onPhotoClick = onPhotoClick,
            onPhotoLongPress = onPhotoLongPress,
            getSelectionMode = getSelectionMode,
            isSelected = isSelected)
        h.strip.layoutManager = LinearLayoutManager(h.v.context, LinearLayoutManager.HORIZONTAL, false)
        h.strip.adapter = stripAdapter
        h.strip.setRecycledViewPool(RecyclerView.RecycledViewPool())
    }
}

// ── Photo Strip Adapter (inner horizontal) ────────────────────────────────────
class PhotoStripAdapter(
    private val items: List<GeoPhoto>,
    private val onPhotoClick:     (GeoPhoto) -> Unit,
    private val onPhotoLongPress: (GeoPhoto) -> Unit,
    private val getSelectionMode: () -> Boolean,
    private val isSelected:       (Uri) -> Boolean
) : RecyclerView.Adapter<PhotoStripAdapter.VH>() {

    private val sdfTime = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb:    ImageView = v.findViewById(R.id.iv_strip_thumb)
        val cb:       CheckBox  = v.findViewById(R.id.cb_select)
        val selOvl:   View      = v.findViewById<View>(android.R.id.background)
                                    .let { v.findViewWithTag("sel_overlay") ?: View(v.context) }
        val gps:      TextView  = v.findViewById(R.id.tv_strip_gps)
        val coords:   TextView  = v.findViewById(R.id.tv_strip_coords)
        val time:     TextView  = v.findViewById(R.id.tv_strip_time)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        LayoutInflater.from(p.context).inflate(R.layout.item_photo_strip, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val photo = items[pos]
        Glide.with(h.thumb).load(photo.uri).centerCrop()
            .placeholder(android.R.drawable.ic_menu_gallery).into(h.thumb)

        val inSel = getSelectionMode()
        val sel   = isSelected(photo.uri)
        h.cb.visibility = if (inSel) View.VISIBLE else View.GONE
        h.cb.isChecked  = sel
        // Selection animation
        if (sel) {
            h.itemView.animate().scaleX(0.93f).scaleY(0.93f).setDuration(100).start()
        } else {
            h.itemView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }

        h.gps.text = photo.gpsLabel()
        h.gps.setTextColor(photo.gpsColor())
        h.gps.setBackgroundResource(when {
            photo.locationIntegrityRisk                -> R.drawable.bg_gps_badge_red
            photo.gpsVerified && photo.accuracy <= 15.0 -> R.drawable.bg_gps_badge_green
            photo.gpsVerified                           -> R.drawable.bg_gps_badge_yellow
            else                                        -> R.drawable.bg_gps_badge_red
        })

        if (photo.lat != 0.0 || photo.lon != 0.0)
            h.coords.text = "%.4f°%s\n%.4f°%s".format(
                Math.abs(photo.lat), if (photo.lat >= 0) "N" else "S",
                Math.abs(photo.lon), if (photo.lon >= 0) "E" else "W")
        else h.coords.text = ""

        h.time.text = sdfTime.format(Date(photo.dateMs))

        h.itemView.setOnClickListener { onPhotoClick(photo) }
        h.itemView.setOnLongClickListener { onPhotoLongPress(photo); true }
    }
}

// ── Selectable Photo Grid Adapter ─────────────────────────────────────────────
class SelectablePhotoAdapter(
    private val onPhotoClick:     (GeoPhoto) -> Unit,
    private val onPhotoLongPress: (GeoPhoto) -> Unit,
    private val getSelectionMode: () -> Boolean,
    private val isSelected:       (Uri) -> Boolean
) : RecyclerView.Adapter<SelectablePhotoAdapter.VH>() {

    private var items = listOf<GeoPhoto>()
    private val sdf   = SimpleDateFormat("dd MMM yyyy · HH:mm 'PKT'", Locale.ENGLISH)

    fun update(newItems: List<GeoPhoto>) { items = newItems; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb:    ImageView = v.findViewById(R.id.iv_thumb)
        val overlay:  View      = v.findViewById(R.id.selection_overlay)
        val cb:       CheckBox  = v.findViewById(R.id.cb_select)
        val gps:      TextView  = v.findViewById(R.id.tv_gps_badge)
        val coords:   TextView  = v.findViewById(R.id.tv_coords_overlay)
        val siteId:   TextView  = v.findViewById(R.id.tv_site_id)
        val dateTime: TextView  = v.findViewById(R.id.tv_date_time)
        val operator: TextView  = v.findViewById(R.id.tv_operator)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        LayoutInflater.from(p.context).inflate(R.layout.item_photo_selectable, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        Glide.with(h.thumb).load(p.uri).centerCrop()
            .placeholder(android.R.drawable.ic_menu_gallery).into(h.thumb)

        val inSel = getSelectionMode(); val sel = isSelected(p.uri)
        h.cb.visibility      = if (inSel) View.VISIBLE else View.GONE
        h.cb.isChecked       = sel
        h.overlay.visibility = if (sel) View.VISIBLE else View.GONE
        val targetScale = if (sel) 0.94f else 1f
        h.itemView.animate().scaleX(targetScale).scaleY(targetScale).setDuration(120).start()

        h.gps.text = p.gpsLabel(); h.gps.setTextColor(p.gpsColor())
        h.gps.setBackgroundResource(when {
            p.locationIntegrityRisk              -> R.drawable.bg_gps_err_refined
            p.gpsVerified && p.accuracy <= 15.0 -> R.drawable.bg_gps_badge_refined
            p.gpsVerified                       -> R.drawable.bg_gps_warn_refined
            else                                -> R.drawable.bg_gps_err_refined
        })

        if (p.lat != 0.0 || p.lon != 0.0)
            h.coords.text = "%.4f°%s  %.4f°%s".format(
                Math.abs(p.lat), if (p.lat >= 0) "N" else "S",
                Math.abs(p.lon), if (p.lon >= 0) "E" else "W")
        else h.coords.visibility = View.INVISIBLE

        if (p.siteId.isBlank()) {
            h.siteId.text = "⚠  UNASSIGNED"
            h.siteId.setTextColor(0xFFF59E0B.toInt())
        } else {
            h.siteId.text = p.siteId
            h.siteId.setTextColor(0xFF22D3EE.toInt())
        }
        h.dateTime.text = sdf.format(Date(p.dateMs))
        h.operator.text = when {
            p.operator.isBlank() -> "–"
            p.distanceM > 0 -> {
                val d = if (p.distanceM >= 1000) "%.1fkm away".format(p.distanceM / 1000)
                        else "%.0fm away".format(p.distanceM)
                "${p.operator}  ·  $d"
            }
            else -> p.operator
        }

        h.itemView.setOnClickListener     { onPhotoClick(p) }
        h.itemView.setOnLongClickListener { onPhotoLongPress(p); true }
    }
}
