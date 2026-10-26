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
) {
    fun spec() =
        EndpointPair(
            id,
            name,
            Point(startLat, startLon),
            Point(endLat, endLon),
            if (end2Lat != null && end2Lon != null) Point(end2Lat, end2Lon) else null,
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
                end2Lat = p.end2?.lat,
                end2Lon = p.end2?.lon,
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
    version = 3,
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
    }
}

class Store internal constructor(context: Context, name: String = "mmp-go.db") {
    val executor = Executors.newSingleThreadExecutor()
    val reader = Executors.newSingleThreadExecutor()
    val db =
        Room.databaseBuilder(context.applicationContext, Database::class.java, name)
            .addMigrations(Database.MIGRATION, Database.MIGRATION_3)
            .build()
    val dao = db.data()

    fun deleteRecording(id: String) {
        db.runInTransaction {
            val recording = dao.journey(id) ?: return@runInTransaction
            check(recording.status != "recording") { "Stop the recording before deleting it." }
            dao.deletePoints(id)
            dao.deleteJourney(id)
        }
    }

    fun recover() {
        dao.journeys()
            .filter { it.status == "recording" }
            .forEach { j ->
                val last = dao.points(j.id).lastOrNull()
                dao.update(
                    j.copy(
                        ended = last?.wallMs ?: j.started,
                        durationMs = ((last?.monoMs ?: j.startMono) - j.startMono).coerceAtLeast(0),
                        status = "interrupted",
                    )
                )
            }
    }

    fun end(id: String, wall: Long, mono: Long, status: String, endpoint: Int? = null) {
        dao.journey(id)?.let {
            dao.update(
                it.copy(
                    ended = wall,
                    durationMs = (mono - it.startMono).coerceAtLeast(0),
                    status = status,
                    endpoint = endpoint ?: it.endpoint,
                )
            )
        }
    }

    fun finishManual(id: String, wall: Long, mono: Long): Boolean {
        val j = dao.journey(id) ?: return false
        if (j.status != "recording") return false
        val fixes = dao.points(id).map { it.fix() }
        val first = fixes.firstOrNull()
        val last = fixes.lastOrNull()
        val endpointFresh =
            first != null &&
                last != null &&
                first.monoMs - j.startMono <= 15000 &&
                mono - last.monoMs <= 15000
        var learned = false
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
            val quality =
                when {
                    first == null -> "no GPS"
                    !endpointFresh || segments(fixes).size > 1 -> "interrupted"
                    else -> "complete"
                }
            end(id, wall, mono, quality)
        }
        return learned
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
                    is TrackingEvent.Abandoned -> {
                        dao.deletePoints(event.id)
                        dao.deleteJourney(event.id)
                    }
                    is TrackingEvent.Ended ->
                        end(
                            event.id,
                            event.fix.wallMs,
                            event.fix.monoMs,
                            if (event.complete) "complete" else "interrupted",
                            event.endpoint,
                        )
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
