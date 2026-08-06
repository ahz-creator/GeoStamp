package com.axiominfratech.geostamp.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────────────────────────────────────
// Entity
// ──────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "tower_sites",
    indices = [
        Index(value = ["operator"]),
        Index(value = ["latitude", "longitude"])
    ]
)
data class TowerSite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "site_id")     val siteId: String,
    @ColumnInfo(name = "operator")    val operator: String,
    @ColumnInfo(name = "sector")      val sector: String = "",
    @ColumnInfo(name = "site_name")   val siteName: String = "",
    @ColumnInfo(name = "latitude")    val latitude: Double,
    @ColumnInfo(name = "longitude")   val longitude: Double,
    @ColumnInfo(name = "city")        val city: String = "",
    @ColumnInfo(name = "province")    val province: String = "",
    @ColumnInfo(name = "technology")  val technology: String = ""
)

/** Pakistan telecom operators */
enum class Operator(val displayName: String, val code: String) {
    JAZZ   ("Jazz / Warid",             "JZZ"),
    ZONG   ("Zong (China Mobile)",      "ZNG"),
    TELENOR("Telenor Pakistan",         "TNR"),
    UFONE  ("Ufone (PTCL)",             "UFN"),
    PTCL   ("PTCL",                     "PTL"),
    SCO    ("SCO (Special Communications)", "SCO"),
    ALL    ("All Operators",            "ALL");

    companion object {
        fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DAO
// ──────────────────────────────────────────────────────────────────────────────

@Dao
interface TowerSiteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sites: List<TowerSite>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(site: TowerSite)

    @Query("SELECT COUNT(*) FROM tower_sites")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM tower_sites WHERE operator = :operator")
    suspend fun countByOperator(operator: String): Int

    /** Paginated full-table scan — used for in-memory spatial index rebuild */
    @Query("SELECT * FROM tower_sites LIMIT :limit OFFSET :offset")
    suspend fun getPage(offset: Int, limit: Int): List<TowerSite>

    /** Load ALL sites that have valid coordinates — used for TowerFinder-style nearest search */
    @Query("SELECT * FROM tower_sites WHERE (latitude != 0.0 OR longitude != 0.0)")
    suspend fun getAllWithCoords(): List<TowerSite>

    /**
     * Bounding-box proximity query (SQLite-compatible).
     * 1° latitude ≈ 111,320 m  →  radiusM / 111320 = pad in degrees.
     * Exact Haversine filtering is done in Kotlin.
     */
    @Query("""
        SELECT * FROM tower_sites
        WHERE latitude  BETWEEN :latMin AND :latMax
          AND longitude BETWEEN :lngMin AND :lngMax
        LIMIT 20
    """)
    suspend fun findNearbyBox(
        latMin: Double, latMax: Double,
        lngMin: Double, lngMax: Double
    ): List<TowerSite>

    @Query("""
        SELECT * FROM tower_sites
        WHERE operator = :operator
          AND latitude  BETWEEN :latMin AND :latMax
          AND longitude BETWEEN :lngMin AND :lngMax
        LIMIT 20
    """)
    suspend fun findNearbyBoxByOperator(
        latMin: Double, latMax: Double,
        lngMin: Double, lngMax: Double,
        operator: String
    ): List<TowerSite>

    @Query("SELECT DISTINCT operator FROM tower_sites ORDER BY operator")
    fun getAllOperators(): Flow<List<String>>

    @Query("SELECT DISTINCT operator FROM tower_sites ORDER BY operator")
    suspend fun getDistinctOperatorList(): List<String>

    @Query("SELECT * FROM tower_sites WHERE operator = :operator ORDER BY city, site_id")
    suspend fun getSitesByOperator(operator: String): List<TowerSite>

    @Query("DELETE FROM tower_sites WHERE operator = :operator")
    suspend fun deleteByOperator(operator: String)

    @Query("DELETE FROM tower_sites")
    suspend fun deleteAll()

    @Query("SELECT * FROM tower_sites WHERE site_id LIKE '%' || :query || '%' OR site_name LIKE '%' || :query || '%' LIMIT 50")
    suspend fun search(query: String): List<TowerSite>
}

// ──────────────────────────────────────────────────────────────────────────────
// Database
// ──────────────────────────────────────────────────────────────────────────────

@Database(entities = [TowerSite::class], version = 1, exportSchema = false)
abstract class SiteDatabase : RoomDatabase() {
    abstract fun towerSiteDao(): TowerSiteDao

    companion object {
        @Volatile private var INSTANCE: SiteDatabase? = null

        fun getInstance(context: android.content.Context): SiteDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SiteDatabase::class.java,
                    "geostamp_sites.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
