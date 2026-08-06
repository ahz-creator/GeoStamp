package com.axiominfratech.geostamp.database

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.math.*

class SiteRepository(private val context: Context, private val dao: TowerSiteDao) {

    companion object {
        const val GITHUB_CSV_URL =
            "https://raw.githubusercontent.com/ahz-creator/GeoStamp/main/all_operators_2g.csv"

        private const val PAGE_SIZE = 1000  // safe below 2MB CursorWindow limit
        @Volatile private var INSTANCE: SiteRepository? = null

        fun getInstance(context: Context): SiteRepository {
            return INSTANCE ?: synchronized(this) {
                val db  = SiteDatabase.getInstance(context)
                val dao = db.towerSiteDao()
                SiteRepository(context.applicationContext, dao).also { INSTANCE = it }
            }
        }

        val OPERATOR_ALIASES: Map<Operator, List<String>> = mapOf(
            Operator.JAZZ    to listOf("Jazz", "Mobilink", "PMCL", "Warid", "Jazz / Warid"),
            Operator.ZONG    to listOf("Zong", "CMPak", "Zong (China Mobile)", "China Mobile"),
            Operator.TELENOR to listOf("Telenor", "Telenor Pakistan"),
            Operator.UFONE   to listOf("Ufone", "PTML", "Ufone (PTCL)"),
            Operator.PTCL    to listOf("PTCL", "Pakistan Telecommunication"),
            Operator.SCO     to listOf("SCO", "Special Communications",
                "SCO (Special Communications)", "SCOM")
        )

        fun siteMatchesOperator(site: TowerSite, op: Operator): Boolean {
            if (op == Operator.ALL) return true
            val aliases = OPERATOR_ALIASES[op] ?: return false
            val siteOp  = site.operator.trim()
            return aliases.any { it.equals(siteOp, ignoreCase = true) }
        }

        fun haversineMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R    = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a    = sin(dLat / 2).let { it * it } +
                       cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                       sin(dLng / 2).let { it * it }
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }

    data class SiteMatchResult(
        val found: Boolean,
        val site: TowerSite? = null,
        val distanceM: Double = 0.0,
        val fallbackText: String = ""
    )

    /**
     * Paginated nearest-per-operator search.
     * Reads DB in chunks of PAGE_SIZE rows — avoids CursorWindow overflow crash
     * that occurs when trying to load 50k+ rows into a single 2MB SQLite window.
     */
    suspend fun findNearestPerOperator(lat: Double, lng: Double): Map<Operator, SiteMatchResult> =
        withContext(Dispatchers.IO) {
            val bestSite = mutableMapOf<Operator, TowerSite>()
            val bestDist = mutableMapOf<Operator, Double>()
            var offset   = 0

            while (true) {
                val chunk: List<TowerSite> = try {
                    dao.getPage(offset, PAGE_SIZE)
                } catch (_: Exception) { break }
                if (chunk.isEmpty()) break

                for (site in chunk) {
                    if (site.latitude == 0.0 && site.longitude == 0.0) continue
                    val d = haversineMetres(lat, lng, site.latitude, site.longitude)
                    for (op in Operator.values()) {
                        if (op == Operator.ALL) continue
                        if (!siteMatchesOperator(site, op)) continue
                        val cur = bestDist[op]
                        if (cur == null || d < cur) { bestSite[op] = site; bestDist[op] = d }
                    }
                }
                offset += chunk.size
                if (chunk.size < PAGE_SIZE) break   // last page
            }

            val result = mutableMapOf<Operator, SiteMatchResult>()
            for (op in Operator.values()) {
                if (op == Operator.ALL) continue
                val site = bestSite[op]
                val dist = bestDist[op] ?: Double.MAX_VALUE
                result[op] = if (site != null) SiteMatchResult(true, site, dist)
                             else SiteMatchResult(false, fallbackText = "No ${op.displayName} site")
            }
            result
        }

    suspend fun findMatchingSite(lat: Double, lng: Double, operator: Operator = Operator.ALL): SiteMatchResult =
        withContext(Dispatchers.IO) {
            var bestSite: TowerSite? = null
            var bestDist = Double.MAX_VALUE
            var offset   = 0

            while (true) {
                val chunk: List<TowerSite> = try {
                    dao.getPage(offset, PAGE_SIZE)
                } catch (_: Exception) { break }
                if (chunk.isEmpty()) break

                for (site in chunk) {
                    if (site.latitude == 0.0 && site.longitude == 0.0) continue
                    if (!siteMatchesOperator(site, operator)) continue
                    val d = haversineMetres(lat, lng, site.latitude, site.longitude)
                    if (d < bestDist) { bestDist = d; bestSite = site }
                }
                offset += chunk.size
                if (chunk.size < PAGE_SIZE) break
            }

            when {
                bestSite == null  -> SiteMatchResult(false, fallbackText = "No site found")
                bestDist <= 500.0 -> SiteMatchResult(true, bestSite, bestDist)
                else              -> SiteMatchResult(true, bestSite, bestDist, "~${bestSite.siteId}")
            }
        }

    fun ensureDatabaseLoaded() {
        val count = runCatching {
            var c = 0; val t = Thread { c = runBlocking { dao.count() } }; t.start(); t.join(); c
        }.getOrDefault(0)
        if (count == 0) runBlocking { seedSampleData() }
    }

    data class SyncResult(val success: Boolean, val count: Int, val message: String)

    suspend fun syncRadiusFromGitHub(
        lat: Double, lng: Double, radiusKm: Double = 150.0
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(GITHUB_CSV_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 180_000
            conn.setRequestProperty("User-Agent", "GeoStamp-Android/5.8")
            conn.requestMethod  = "GET"
            val code = conn.responseCode
            if (code != 200) return@withContext SyncResult(false, 0, "HTTP $code")

            var colMap: Map<String, Int>? = null
            var headerRead = false
            val batch      = mutableListOf<TowerSite>()
            var saved = 0; var skipped = 0; var errors = 0

            val reader = conn.inputStream.bufferedReader()
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    if (!headerRead) {
                        colMap     = detectColumns(line)
                        headerRead = true
                    } else {
                        try {
                            val site = parseLine(line, colMap)
                            if (site != null) {
                                val distKm = haversineMetres(lat, lng, site.latitude, site.longitude) / 1000.0
                                if (distKm <= radiusKm) {
                                    batch.add(site); saved++
                                    if (batch.size >= 500) { dao.insertAll(batch); batch.clear() }
                                } else skipped++
                            } else skipped++
                        } catch (_: Exception) { errors++ }
                    }
                }
                line = reader.readLine()
            }
            reader.close()
            conn.disconnect()
            if (batch.isNotEmpty()) dao.insertAll(batch)
            val msg = "Downloaded $saved sites within ${radiusKm.toInt()}km" +
                      if (errors > 0) " ($errors errors)" else ""
            SyncResult(saved > 0, saved, msg)
        } catch (e: Exception) {
            SyncResult(false, 0, "Radius sync failed: ${e.message}")
        }
    }

        suspend fun syncFromGitHub(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(GITHUB_CSV_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent","GeoStamp-Android/5.7")
            conn.setRequestProperty("Cache-Control","no-cache")
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) return@withContext SyncResult(false, 0, "HTTP $code")
            val lines = conn.inputStream.bufferedReader().readLines()
            conn.disconnect()
            if (lines.isEmpty()) return@withContext SyncResult(false, 0, "Empty response")
            val colMap = detectColumns(lines.first())
            val data   = if (colMap != null) lines.drop(1) else lines
            dao.deleteAll()
            val batch = mutableListOf<TowerSite>()
            var success = 0; var errors = 0
            for (line in data) {
                if (line.isBlank()) continue
                try {
                    parseLine(line, colMap)?.also {
                        batch.add(it); success++
                        if (batch.size >= 500) { dao.insertAll(batch); batch.clear() }
                    } ?: errors++
                } catch (_: Exception) { errors++ }
            }
            if (batch.isNotEmpty()) dao.insertAll(batch)
            val msg = if (success > 0) "synced $success sites${if (errors > 0) " ($errors skipped)" else ""}"
                      else "0 sites synced"
            SyncResult(success > 0, success, msg)
        } catch (e: Exception) { SyncResult(false, 0, "Sync failed: ${e.message}") }
    }

    suspend fun importFromCsv(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var success = 0; var errors = 0; val batch = mutableListOf<TowerSite>()
        try {
            dao.deleteAll()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val lines = stream.bufferedReader().readLines()
                val colMap = detectColumns(lines.firstOrNull() ?: "")
                val data   = if (colMap != null) lines.drop(1) else lines
                for (line in data) {
                    if (line.isBlank()) continue
                    try { parseLine(line, colMap)?.also { batch.add(it); success++
                        if (batch.size >= 500) { dao.insertAll(batch); batch.clear() } }
                    } catch (_: Exception) { errors++ }
                }
                if (batch.isNotEmpty()) dao.insertAll(batch)
            }
        } catch (_: Exception) { errors++ }
        Pair(success, errors)
    }

    private fun detectColumns(headerLine: String): Map<String, Int>? {
        val cols = parseCsvLine(headerLine).map { it.trim().lowercase().replace(" ","_").replace("-","_") }
        val known = setOf("site_id","siteid","operator","latitude","lat","longitude","lng","lon")
        if (cols.none { it in known }) return null
        val map = mutableMapOf<String, Int>()
        cols.forEachIndexed { i, col ->
            when {
                col in setOf("site_id","siteid","site","id")                            -> map["site_id"]  = i
                col in setOf("operator","op","carrier","network","opco")                -> map["operator"] = i
                col in setOf("site_name","name","sitename","location_name")             -> map["name"]     = i
                col in setOf("sector","azimuth","direction","cell")                     -> map["sector"]   = i
                col in setOf("latitude","lat","y","lat_dd","decimal_lat")               -> map["lat"]      = i
                col in setOf("longitude","lng","lon","long","x","lng_dd","decimal_lng") -> map["lng"]      = i
                col in setOf("city","town","district","area","division")                -> map["city"]     = i
                col in setOf("province","state","region","prov")                       -> map["province"] = i
                col in setOf("technology","tech","generation","type","rat")             -> map["tech"]     = i
            }
        }
        return map
    }

    private fun parseLine(line: String, colMap: Map<String, Int>?): TowerSite? {
        val row = parseCsvLine(line)
        if (row.size < 2) return null
        return if (colMap != null && colMap.isNotEmpty()) {
            val lat = row.getOrElse(colMap["lat"] ?: -1) { "" }.trim().toDoubleOrNull() ?: return null
            val lng = row.getOrElse(colMap["lng"] ?: -1) { "" }.trim().toDoubleOrNull() ?: return null
            if (lat == 0.0 && lng == 0.0) return null
            val sid = row.getOrElse(colMap["site_id"] ?: -1) { "" }.trim()
            val op  = row.getOrElse(colMap["operator"] ?: -1) { "" }.trim()
            if (sid.isBlank() && op.isBlank()) return null
            TowerSite(siteId=sid.ifBlank{"SITE-${(lat*1000).toInt()}-${(lng*1000).toInt()}"},
                operator=op.ifBlank{"Unknown"},
                siteName=row.getOrElse(colMap["name"]?:-1){""}.trim(),
                sector=row.getOrElse(colMap["sector"]?:-1){""}.trim(),
                latitude=lat, longitude=lng,
                city=row.getOrElse(colMap["city"]?:-1){""}.trim(),
                province=row.getOrElse(colMap["province"]?:-1){""}.trim(),
                technology=row.getOrElse(colMap["tech"]?:-1){""}.trim())
        } else {
            if (row.size < 6) return null
            val lat = row.getOrElse(4){"0"}.trim().toDoubleOrNull() ?: return null
            val lng = row.getOrElse(5){"0"}.trim().toDoubleOrNull() ?: return null
            if (lat == 0.0 && lng == 0.0) return null
            TowerSite(siteId=row.getOrElse(0){""}.trim(), operator=row.getOrElse(1){""}.trim(),
                siteName=row.getOrElse(2){""}.trim(), sector=row.getOrElse(3){""}.trim(),
                latitude=lat, longitude=lng, city=row.getOrElse(6){""}.trim(),
                province=row.getOrElse(7){""}.trim(), technology=row.getOrElse(8){""}.trim())
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>(); val cur = StringBuilder(); var inQ = false
        for (ch in line) { when { ch=='"'->inQ=!inQ; ch==','&&!inQ->{result+=cur.toString();cur.clear()}; else->cur.append(ch) } }
        result+=cur.toString(); return result
    }

    suspend fun getCount(): Int = withContext(Dispatchers.IO) { dao.count() }
    suspend fun getCountByOperator(op: String) = withContext(Dispatchers.IO) { dao.countByOperator(op) }
    fun getAllOperators(): Flow<List<String>> = dao.getAllOperators()
    suspend fun getDistinctOperators(): List<String> = withContext(Dispatchers.IO) { dao.getDistinctOperatorList() }
    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }
    fun isIndexReady() = true

    internal suspend fun seedSampleData() = withContext(Dispatchers.IO) {
        fun s(id:String,op:String,n:String,sec:String,lat:Double,lng:Double,city:String,p:String,t:String) =
            TowerSite(siteId=id,operator=op,siteName=n,sector=sec,latitude=lat,longitude=lng,city=city,province=p,technology=t)
        dao.insertAll(listOf(
            s("HYD-TNR-001","Telenor","Auto Bahn Road","Alpha 0°",25.374543,68.351205,"Hyderabad","Sindh","4G"),
            s("HYD-TNR-105","Telenor","Latifabad Unit-8","Beta 120°",25.336909,68.367949,"Hyderabad","Sindh","4G"),
            s("HYD-JZZ-001","Jazz","Saddar Hyderabad","Alpha 0°",25.367000,68.372000,"Hyderabad","Sindh","4G"),
            s("HYD-JZZ-002","Jazz","Qasimabad","Beta 120°",25.352000,68.385000,"Hyderabad","Sindh","4G"),
            s("HYD-ZNG-001","Zong","Hyderabad Saddar","Alpha 0°",25.361000,68.369000,"Hyderabad","Sindh","5G"),
            s("HYD-ZNG-002","Zong","Hyder Chowk","Beta 120°",25.371000,68.395000,"Hyderabad","Sindh","4G"),
            s("HYD-UFN-001","Ufone","Hyderabad Exchange","Alpha 0°",25.357000,68.360000,"Hyderabad","Sindh","4G"),
            s("HYD-UFN-002","Ufone","Tando Road","Beta 120°",25.342000,68.352000,"Hyderabad","Sindh","4G"),
            s("HYD-PTL-001","PTCL","HYD Telephone Exch","Alpha 0°",25.369000,68.367000,"Hyderabad","Sindh","4G"),
            s("HYD-SCO-001","SCO","Hyderabad SCO","Alpha 0°",25.355000,68.342000,"Hyderabad","Sindh","3G"),
            s("KHI-JZZ-001","Jazz","Saddar Exchange","Alpha 0°",24.860966,67.010534,"Karachi","Sindh","4G"),
            s("KHI-ZNG-001","Zong","DHA Phase 5","Alpha 0°",24.809823,67.060415,"Karachi","Sindh","5G"),
            s("KHI-TNR-001","Telenor","Gulshan-e-Iqbal","Gamma 240°",24.921938,67.093912,"Karachi","Sindh","4G"),
            s("LHR-JZZ-001","Jazz","Liberty Market","Alpha 0°",31.522300,74.329399,"Lahore","Punjab","4G"),
            s("ISB-PTL-001","PTCL","Blue Area","Alpha 0°",33.728519,73.093946,"Islamabad","ICT","4G"),
            s("ISB-TNR-001","Telenor","F-7 Markaz","Beta 120°",33.722580,73.056190,"Islamabad","ICT","4G"),
            s("MZD-SCO-001","SCO","Muzaffarabad City","Alpha 0°",34.370000,73.471000,"Muzaffarabad","AJK","3G")
        ))
    }

    /** Match a site using an administrator-defined operator and aliases. */
    suspend fun findMatchingSiteByAliases(
        lat: Double,
        lng: Double,
        operatorName: String,
        aliases: List<String>,
        maxDistanceM: Double = 500.0
    ): SiteMatchResult = withContext(Dispatchers.IO) {
        val accepted = (aliases + operatorName).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        var bestSite: TowerSite? = null
        var bestDist = Double.MAX_VALUE
        var offset = 0
        while (true) {
            val chunk = try { dao.getPage(offset, PAGE_SIZE) } catch (_: Exception) { emptyList() }
            if (chunk.isEmpty()) break
            for (site in chunk) {
                if (site.latitude == 0.0 && site.longitude == 0.0) continue
                val siteOperator = site.operator.trim().lowercase()
                if (accepted.none { alias -> siteOperator == alias || siteOperator.contains(alias) || alias.contains(siteOperator) }) continue
                val d = haversineMetres(lat, lng, site.latitude, site.longitude)
                if (d < bestDist) { bestDist = d; bestSite = site }
            }
            offset += chunk.size
            if (chunk.size < PAGE_SIZE) break
        }
        when {
            bestSite == null -> SiteMatchResult(false, fallbackText = "No $operatorName site found")
            bestDist <= maxDistanceM -> SiteMatchResult(true, bestSite, bestDist)
            else -> SiteMatchResult(false, bestSite, bestDist, "Nearest site is %.0fm away".format(bestDist))
        }
    }

    /**
     * Replace one operator's local site data from an admin-published CSV file.
     * The CSV may include an operator column; the administrator-selected name
     * remains authoritative when the column is blank.
     */
    suspend fun syncOperatorCsv(
        operatorName: String,
        aliases: List<String>,
        csvUrl: String
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(csvUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "GeoStamp-Android/10")
            val code = conn.responseCode
            if (code !in 200..299) return@withContext SyncResult(false, 0, "HTTP $code")

            val reader = conn.inputStream.bufferedReader()
            val header = reader.readLine() ?: return@withContext SyncResult(false, 0, "Empty CSV")
            val colMap = detectColumns(header)
            val parsed = mutableListOf<TowerSite>()
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val site = runCatching { parseLine(line, colMap) }.getOrNull() ?: return@forEachLine
                parsed += if (site.operator.isBlank() || site.operator.equals("Unknown", true)) {
                    site.copy(operator = operatorName)
                } else site
            }
            reader.close()
            conn.disconnect()

            val knownNames = (aliases + operatorName).distinct()
            knownNames.forEach { dao.deleteByOperator(it) }
            parsed.chunked(500).forEach { dao.insertAll(it) }
            SyncResult(true, parsed.size, "Synced ${parsed.size} $operatorName sites")
        } catch (e: Exception) {
            SyncResult(false, 0, "Site sync failed: ${e.message}")
        }
    }

}
