package com.sentra.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sentra.nav.GeoPoint
import kotlinx.coroutines.flow.Flow

/** A route recorded by the companion (Record Route, §9.4): start, every turn, destination. */
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "lat,lon;lat,lon;…" in walking order. */
    val points: String,
    val createdAt: Long,
) {
    fun geoPoints(): List<GeoPoint> = decodePoints(points)

    companion object {
        fun encodePoints(points: List<GeoPoint>): String = points.joinToString(";") { "${it.lat},${it.lon}" }

        fun decodePoints(text: String): List<GeoPoint> = text.split(';').mapNotNull { pair ->
            val (lat, lon) = pair.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            GeoPoint(lat, lon)
        }
    }
}

/**
 * Something that happened on a trip, for the parents. The sleeve keeps no event queue, so the
 * phone numbers events itself: [id] is the seq the relay de-duplicates on.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The sleeve's Bluetooth address, or SIMULATED. */
    val deviceAddress: String,
    /** A relay event type: NAV_START, TURN_LEFT, OFF_ROUTE, ARRIVED, SLEEVE_DISCONNECTED, … */
    val type: String,
    /** The phone's battery. */
    val batteryPct: Int,
    val occurredAt: Long,
    val lat: Double?,
    val lon: Double?,
    val message: String,
    /** When the relay accepted it, epoch ms; null while it waits in the outbox. */
    val uploadedAt: Long? = null,
    /** The parent who acknowledged this alarm on the relay, and when. */
    val ackedBy: String? = null,
    val ackedAt: Long? = null,
)

/** A GPS fix waiting to be uploaded; deleted once the relay has it. */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float,
    val bearing: Float,
)

/** A finished trip, for Route History on the child's phone. Kept on the phone only. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    /** Copied, so the trip keeps its name after the route is deleted. */
    val routeName: String,
    val startedAt: Long,
    val endedAt: Long,
    /** True when the destination was reached, false when the trip was stopped early. */
    val arrived: Boolean,
    val lengthM: Double,
) {
    val durationMs: Long get() = endedAt - startedAt
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT 200")
    fun recent(): Flow<List<TripEntity>>

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Query("DELETE FROM trips")
    suspend fun clear()
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun byId(id: Long): RouteEntity?

    @Insert
    suspend fun insert(route: RouteEntity): Long

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OutboxDao : Outbox {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE uploadedAt IS NULL ORDER BY id ASC")
    override suspend fun pendingEvents(): List<EventEntity>

    @Query("UPDATE events SET uploadedAt = :at WHERE id = :id")
    override suspend fun markEventUploaded(id: Long, at: Long)

    @Query("UPDATE events SET ackedBy = :by, ackedAt = :at WHERE id = :id")
    suspend fun setAck(id: Long, by: String, at: Long)

    @Query("SELECT * FROM events ORDER BY occurredAt DESC, id DESC LIMIT 300")
    fun recentEvents(): Flow<List<EventEntity>>

    @Query("DELETE FROM events")
    suspend fun clearEvents()

    @Insert
    suspend fun insert(location: LocationEntity): Long

    @Query("SELECT * FROM locations ORDER BY id ASC LIMIT :limit")
    override suspend fun pendingLocations(limit: Int): List<LocationEntity>

    @Query("DELETE FROM locations WHERE id <= :id")
    override suspend fun deleteLocationsThrough(id: Long)

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun locationCount(): Int

    /** Without a reachable relay the queue must not grow forever: keep the newest [keep]. */
    @Query("DELETE FROM locations WHERE id <= (SELECT MAX(id) FROM locations) - :keep")
    suspend fun trimLocations(keep: Int)

    @Query("DELETE FROM locations")
    suspend fun clearLocations()
}

@Database(entities = [RouteEntity::class, EventEntity::class, LocationEntity::class, TripEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routes(): RouteDao
    abstract fun outbox(): OutboxDao
    abstract fun trips(): TripDao

    companion object {
        /** Version 2 adds Route History. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routeId` INTEGER NOT NULL, " +
                        "`routeName` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `arrived` INTEGER NOT NULL, " +
                        "`lengthM` REAL NOT NULL)"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sentra.db").addMigrations(MIGRATION_1_2).build()
    }
}
