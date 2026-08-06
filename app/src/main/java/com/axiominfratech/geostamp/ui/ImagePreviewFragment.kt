package com.axiominfratech.geostamp.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.axiominfratech.geostamp.databinding.FragmentImagePreviewBinding
import java.io.File
import java.io.FileInputStream

class ImagePreviewFragment : Fragment() {

    companion object {
        private const val ARG_PATH = "image_path"
        fun newInstance(path: String) = ImagePreviewFragment().apply {
            arguments = Bundle().apply { putString(ARG_PATH, path) }
        }
    }

    private var _binding: FragmentImagePreviewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImagePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run {
            Toast.makeText(requireContext(), "No image path", Toast.LENGTH_LONG).show()
            return
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Image not found: ${file.name}", Toast.LENGTH_LONG).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            binding.ivPreview.setImageBitmap(bitmap)
        }
        binding.tvFilePath.text = "${file.name}  ·  ${file.length() / 1024} KB"

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnSave.setOnClickListener {
            saveToGallery(file)
        }

        binding.btnShare.setOnClickListener {
            shareImage(file)
        }
    }

    /**
     * Save using MediaStore API (API 29+) or legacy File API (API < 29).
     * Creates DCIM/GeoStamp Photos/ folder automatically — no permission needed on API 29+.
     */
    private fun saveToGallery(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ — use MediaStore, no WRITE_EXTERNAL_STORAGE needed
                val resolver = requireContext().contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/GeoStamp Photos")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(requireContext(), "Could not create gallery entry", Toast.LENGTH_LONG).show()
                    return
                }
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Toast.makeText(requireContext(), "✓ Saved to DCIM/GeoStamp Photos", Toast.LENGTH_SHORT).show()
            } else {
                // API < 29 — write directly, then scan
                @Suppress("DEPRECATION")
                val dcim = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM
                )
                val folder = File(dcim, "GeoStamp Photos")
                if (!folder.exists() && !folder.mkdirs()) {
                    Toast.makeText(requireContext(), "Cannot create folder — check Storage permission", Toast.LENGTH_LONG).show()
                    return
                }
                val dest = File(folder, file.name)
                file.copyTo(dest, overwrite = true)
                // Tell MediaStore to index the new file
                @Suppress("DEPRECATION")
                requireContext().sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = Uri.fromFile(dest)
                    }
                )
                Toast.makeText(requireContext(), "✓ Saved to DCIM/GeoStamp Photos", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareImage(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share GeoStamp Photo"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
