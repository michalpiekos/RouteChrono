package dev.mmp.go

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.mmp.core.*
import java.util.concurrent.Executors

@Entity(tableName = "configs")
data class ConfigRow(@PrimaryKey val id: String, val created: Long, val payload: String)

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val id: String,
    val configId: String,
    val started: Long,
    val ended: Long?,
    val trigger: String,
    val status: String,
)

@Entity(tableName = "fixes", indices = [Index(value = ["sessionId", "sequence"], unique = true)])
data class FixRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequence: Int,
    val payload: String,
)

@Entity(tableName = "events", indices = [Index("sessionId")])
data class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val wallMs: Long,
    val message: String,
)

@Entity(tableName = "runs", indices = [Index("sessionId")])
data class RunRow(
    @PrimaryKey val id: String,
    val sessionId: String,
    val created: Long,
    val algorithm: Int,
)

@Entity(tableName = "observations", indices = [Index("runId")])
data class ObservationRow(@PrimaryKey val id: String, val runId: String, val payload: String)

@Entity(tableName = "endpoint_pairs")
data class PairRow(
    @PrimaryKey val id: String,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val archived: Boolean = false,
    val end2Lat: Double? = null,
    val end2Lon: Double? = null,
    val end3Lat: Double? = null,
    val end3Lon: Double? = null,
    val end4Lat: Double? = null,
    val end4Lon: Double? = null,
    val end5Lat: Double? = null,
    val end5Lon: Double? = null,
) {
    fun spec() =
        EndpointPair(
            id,
            name,
            Point(startLat, startLon),
            listOf(Point(endLat, endLon)) +
                listOf(end2Lat to end2Lon, end3Lat to end3Lon, end4Lat to end4Lon, end5Lat to end5Lon)
                    .mapNotNull { (lat, lon) -> if (lat != null && lon != null) Point(lat, lon) else null },
        )

    companion object {
        fun from(p: EndpointPair) =
            PairRow(
                p.id,
                p.name,
                p.start.lat,
                p.start.lon,
                p.end.lat,
                p.end.lon,
                end2Lat = p.ends.getOrNull(1)?.lat,
                end2Lon = p.ends.getOrNull(1)?.lon,
                end3Lat = p.ends.getOrNull(2)?.lat,
                end3Lon = p.ends.getOrNull(2)?.lon,
                end4Lat = p.ends.getOrNull(3)?.lat,
                end4Lon = p.ends.getOrNull(3)?.lon,
                end5Lat = p.ends.getOrNull(4)?.lat,
                end5Lon = p.ends.getOrNull(4)?.lon,
            )
    }
}

@Entity(tableName = "journeys", indices = [Index("pairId")])
data class JourneyRow(
    @PrimaryKey val id: String,
    val pairId: String?,
    val direction: String,
    val source: String,
    val started: Long,
    val startMono: Long,
    val ended: Long? = null,
    val durationMs: Long = 0,
    val status: String = "recording",
    val endpoint: Int? = null,
)

@Entity(tableName = "track_points", indices = [Index("journeyId")])
data class TrackRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journeyId: String,
    val lat: Double,
    val lon: Double,
    val wallMs: Long,
    val monoMs: Long,
    val accuracy: Double,
) {
    fun fix() = Fix(Point(lat, lon), wallMs, monoMs, accuracy)

    companion object {
        fun from(id: String, f: Fix) =
            TrackRow(
                journeyId = id,
                lat = f.point.lat,
                lon = f.point.lon,
                wallMs = f.wallMs,
                monoMs = f.monoMs,
                accuracy = f.accuracy,
            )
    }
}

@Dao
interface DataDao {
    @Query("SELECT * FROM endpoint_pairs ORDER BY rowid") fun pairs(): List<PairRow>

    @Insert fun pair(row: PairRow)

    @Query("UPDATE endpoint_pairs SET archived=1 WHERE id=:id") fun archive(id: String)

    @Query("UPDATE endpoint_pairs SET name=:name WHERE id=:id") fun rename(id: String, name: String)

    @Query("SELECT * FROM journeys ORDER BY rowid DESC") fun journeys(): List<JourneyRow>

    @Query("SELECT * FROM journeys WHERE id=:id") fun journey(id: String): JourneyRow?

    @Insert fun journey(row: JourneyRow)

    @Update fun update(row: JourneyRow)

    @Insert fun point(row: TrackRow)

    @Query("DELETE FROM track_points WHERE journeyId=:id") fun deletePoints(id: String)

    @Query("DELETE FROM journeys WHERE id=:id") fun deleteJourney(id: String)

    @Query("SELECT * FROM track_points WHERE journeyId=:id ORDER BY id")
    fun points(id: String): List<TrackRow>

    @Query("SELECT COUNT(*) FROM fixes") fun legacyFixCount(): Int
}

@androidx.room.Database(
    entities =
        [
            ConfigRow::class,
            SessionRow::class,
            FixRow::class,
            EventRow::class,
            RunRow::class,
            ObservationRow::class,
            PairRow::class,
            JourneyRow::class,
            TrackRow::class,
        ],
    version = 4,
    exportSchema = true,
)
abstract class Database : RoomDatabase() {
    abstract fun data(): DataDao

    companion object {
        val MIGRATION =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS endpoint_pairs (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, startLat REAL NOT NULL, startLon REAL NOT NULL, endLat REAL NOT NULL, endLon REAL NOT NULL, archived INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS journeys (id TEXT NOT NULL PRIMARY KEY, pairId TEXT, direction TEXT NOT NULL, source TEXT NOT NULL, started INTEGER NOT NULL, startMono INTEGER NOT NULL, ended INTEGER, durationMs INTEGER NOT NULL, status TEXT NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_journeys_pairId ON journeys(pairId)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS track_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, journeyId TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, wallMs INTEGER NOT NULL, monoMs INTEGER NOT NULL, accuracy REAL NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_track_points_journeyId ON track_points(journeyId)"
                    )
                }
            }

        val MIGRATION_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE endpoint_pairs ADD COLUMN end2Lat REAL")
                    db.execSQL("ALTER TABLE endpoint_pairs ADD COLUMN end2Lon REAL")
                    db.execSQL("ALTER TABLE journeys ADD COLUMN endpoint INTEGER")
                }
            }

        val MIGRATION_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (n in 3..5) {
                        db.execSQL("ALTER TABLE endpoint_pairs ADD COLUMN end${n}Lat REAL")
                        db.execSQL("ALTER TABLE endpoint_pairs ADD COLUMN end${n}Lon REAL")
                    }
                }
            }
    }
}

data class ManualResult(val saved: Boolean, val learned: Boolean)

class Store internal constructor(context: Context, name: String = "mmp-go.db") {
    val executor = Executors.newSingleThreadExecutor()
    val reader = Executors.newSingleThreadExecutor()
    val db =
        Room.databaseBuilder(context.applicationContext, Database::class.java, name)
            .addMigrations(Database.MIGRATION, Database.MIGRATION_3, Database.MIGRATION_4)
            .build()
    val dao = db.data()

    fun deleteRecording(id: String) {
        db.runInTransaction {
            val recording = dao.journey(id) ?: return@runInTransaction
            check(recording.status != "recording") { "Stop the recording before deleting it." }
            discard(id)
        }
    }

    fun discard(id: String) {
        dao.deletePoints(id)
        dao.deleteJourney(id)
    }

    fun discardIncomplete() {
        db.runInTransaction {
            dao.journeys().filter { it.status != "complete" }.forEach { discard(it.id) }
        }
    }

    private fun complete(id: String, wall: Long, mono: Long, endpoint: Int? = null) {
        dao.journey(id)?.let {
            dao.update(
                it.copy(
                    ended = wall,
                    durationMs = (mono - it.startMono).coerceAtLeast(0),
                    status = "complete",
                    endpoint = endpoint ?: it.endpoint,
                )
            )
        }
    }

    fun finishManual(id: String, wall: Long, mono: Long): ManualResult? {
        val j = dao.journey(id) ?: return null
        if (j.status != "recording") return null
        val fixes = dao.points(id).map { it.fix() }
        val first = fixes.firstOrNull()
        val last = fixes.lastOrNull()
        val endpointFresh =
            first != null &&
                last != null &&
                first.monoMs - j.startMono <= 15000 &&
                mono - last.monoMs <= 15000
        var learned = false
        var saved = false
        db.runInTransaction {
            if (endpointFresh && distance(first!!.point, last!!.point) > 150) {
                val existing =
                    dao.pairs()
                        .filter { !it.archived }
                        .firstNotNullOfOrNull { p ->
                            p.spec().match(first.point, last.point)?.let { p to it }
                        }
                val pair =
                    existing?.first
                        ?: PairRow.from(
                                EndpointPair(
                                    newId(),
                                    "Journey ${dao.pairs().size + 1}",
                                    first.point,
                                    last.point,
                                )
                            )
                            .also { dao.pair(it) }
                val match = existing?.second ?: TripMatch(false, null)
                dao.update(
                    j.copy(
                        pairId = pair.id,
                        direction = if (match.reverse) "return" else "outward",
                        endpoint = match.endpoint,
                    )
                )
                learned = true
            }
            saved = first != null && endpointFresh && segments(fixes).size == 1
            if (saved) complete(id, wall, mono) else discard(id)
        }
        return ManualResult(saved, learned)
    }

    fun accept(events: List<TrackingEvent>) {
        db.runInTransaction {
            events.forEach { event ->
                when (event) {
                    is TrackingEvent.Started -> {
                        dao.journey(
                            JourneyRow(
                                event.id,
                                event.pair.id,
                                if (event.reverse) "return" else "outward",
                                "automatic",
                                event.fix.wallMs,
                                event.fix.monoMs,
                                endpoint = event.endpoint,
                            )
                        )
                        dao.point(TrackRow.from(event.id, event.fix))
                    }
                    is TrackingEvent.Sample -> dao.point(TrackRow.from(event.id, event.fix))
                    is TrackingEvent.Abandoned -> discard(event.id)
                    is TrackingEvent.Ended ->
                        if (event.complete)
                            complete(event.id, event.fix.wallMs, event.fix.monoMs, event.endpoint)
                        else discard(event.id)
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context).also { instance = it } }
    }
}
