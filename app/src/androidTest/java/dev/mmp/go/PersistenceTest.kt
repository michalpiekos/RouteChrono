package dev.mmp.go

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mmp.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fix(x: Double, mono: Long, y: Double = 0.0) =
        Fix(Point(y, x), 100000 + mono, mono, 5.0)

    private fun withStore(test: (Store, String) -> Unit) {
        val name = "test-${newId()}.db"
        val store = Store(context, name)
        try {
            test(store, name)
        } finally {
            store.db.close()
            store.executor.shutdown()
            store.reader.shutdown()
            context.deleteDatabase(name)
        }
    }

    private fun legacyDatabase(name: String, version: Int): SQLiteDatabase {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema =
            JSONObject(
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open("dev.mmp.go.Database/$version.json")
                        .bufferedReader()
                        .readText()
                )
                .getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            old.execSQL(
                e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            )
            val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
            for (k in 0 until indices.length()) old.execSQL(
                indices
                    .getJSONObject(k)
                    .getString("createSql")
                    .replace("\${TABLE_NAME}", e.getString("tableName"))
            )
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.version = version
        return old
    }

    private fun open(name: String) =
        Room.databaseBuilder(context, Database::class.java, name)
            .addMigrations(Database.MIGRATION, Database.MIGRATION_3, Database.MIGRATION_4)
            .build()

    @Test
    fun migrationPreservesLegacyRawEvidenceAndAcceptsNewPairs() {
        val name = "migration-${newId()}.db"
        val old = legacyDatabase(name, 1)
        old.execSQL("INSERT INTO configs VALUES ('old',1,'{legacy:true}')")
        old.execSQL("INSERT INTO sessions VALUES ('session','old',1,2,'manual','finished')")
        old.execSQL("INSERT INTO fixes VALUES (1,'session',0,'raw-evidence')")
        old.close()
        val db = open(name)
        try {
            assertEquals(1, db.data().legacyFixCount())
            db.openHelper.readableDatabase.query("SELECT payload FROM fixes").use {
                assertTrue(it.moveToFirst())
                assertEquals("raw-evidence", it.getString(0))
            }
            db.data()
                .pair(PairRow.from(EndpointPair("p", "Pair", Point(0.0, 0.0), Point(0.0, 0.01))))
            assertEquals(1, db.data().pairs().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun schemaTwoMigrationKeepsRowsAndAddsSecondEnd() {
        val name = "migration-${newId()}.db"
        val old = legacyDatabase(name, 2)
        old.execSQL("INSERT INTO fixes VALUES (1,'session',0,'raw-evidence')")
        old.execSQL("INSERT INTO endpoint_pairs VALUES ('p','Pair',0.0,0.0,0.0,0.01,1)")
        old.execSQL("INSERT INTO journeys VALUES ('j','p','return','automatic',100,5,200,95,'complete')")
        old.execSQL("INSERT INTO track_points VALUES (7,'j',0.0,0.01,100,5,4.5)")
        old.close()
        val db = open(name)
        try {
            val dao = db.data()
            assertEquals(1, dao.legacyFixCount())
            assertEquals(
                PairRow("p", "Pair", 0.0, 0.0, 0.0, 0.01, archived = true),
                dao.pairs().single(),
            )
            assertEquals(1, dao.pairs().single().spec().ends.size)
            assertEquals(
                JourneyRow("j", "p", "return", "automatic", 100, 5, 200, 95, "complete"),
                dao.journey("j"),
            )
            assertEquals(listOf(TrackRow(7, "j", 0.0, 0.01, 100, 5, 4.5)), dao.points("j"))
            val fork = EndpointPair("f", "Fork", Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.0))
            dao.pair(PairRow.from(fork))
            assertEquals(fork, dao.pairs().last().spec())
            dao.journey(JourneyRow("k", "f", "outward", "automatic", 1, 1, endpoint = 2))
            assertEquals(2, dao.journey("k")!!.endpoint)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun schemaThreeMigrationKeepsRowsAndAddsFurtherEnds() {
        val name = "migration-${newId()}.db"
        val old = legacyDatabase(name, 3)
        old.execSQL("INSERT INTO fixes VALUES (1,'session',0,'raw-evidence')")
        old.execSQL("INSERT INTO endpoint_pairs VALUES ('p','Pair',0.0,0.0,0.0,0.01,0,NULL,NULL)")
        old.execSQL("INSERT INTO endpoint_pairs VALUES ('f','Fork',0.0,0.0,0.0,0.01,1,0.01,0.0)")
        old.execSQL("INSERT INTO journeys VALUES ('j','f','return','automatic',100,5,200,95,'complete',2)")
        old.execSQL("INSERT INTO track_points VALUES (7,'j',0.0,0.01,100,5,4.5)")
        old.close()
        val db = open(name)
        try {
            val dao = db.data()
            assertEquals(1, dao.legacyFixCount())
            assertEquals(
                listOf(
                    PairRow("p", "Pair", 0.0, 0.0, 0.0, 0.01),
                    PairRow("f", "Fork", 0.0, 0.0, 0.0, 0.01, true, 0.01, 0.0),
                ),
                dao.pairs(),
            )
            assertEquals(
                EndpointPair("f", "Fork", Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.0)),
                dao.pairs().last().spec(),
            )
            assertEquals(
                JourneyRow("j", "f", "return", "automatic", 100, 5, 200, 95, "complete", 2),
                dao.journey("j"),
            )
            assertEquals(listOf(TrackRow(7, "j", 0.0, 0.01, 100, 5, 4.5)), dao.points("j"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun fiveEndRoutePersistsAndTeachesAgainstFifthEnd() = withStore { store, _ ->
        val star =
            EndpointPair(
                "s",
                "Star",
                Point(0.0, 0.0),
                Point(0.0, 0.01),
                Point(0.01, 0.0),
                Point(0.0, -0.01),
                Point(-0.01, 0.0),
                Point(0.01, 0.01),
            )
        store.dao.pair(PairRow.from(star))
        assertEquals(star, store.dao.pairs().single().spec())
        val tracker = EndpointTracker(listOf(star))
        listOf(fix(0.0, 0), fix(0.0, 15000, 0.001), fix(0.005, 30000, 0.005), fix(0.01, 45000, 0.01))
            .forEach { store.accept(tracker.feed(it)) }
        assertEquals(
            listOf(Triple("outward", 5, "complete")),
            store.dao.journeys().map { Triple(it.direction, it.endpoint, it.status) },
        )
        val manual = JourneyRow("teach", null, "outward", "manual", 200000, 200000)
        store.dao.journey(manual)
        listOf(fix(0.01, 200000, 0.01), fix(0.005, 210000, 0.005), fix(0.0, 220000)).forEach {
            store.dao.point(TrackRow.from(manual.id, it))
        }
        assertTrue(store.finishManual(manual.id, 320000, 220000))
        assertEquals(1, store.dao.pairs().size)
        val taught = store.dao.journey("teach")!!
        assertEquals(Triple("s", "return", 5), Triple(taught.pairId, taught.direction, taught.endpoint))
    }

    @Test
    fun threePointRoutePersistsReachedEnds() = withStore { store, _ ->
        val fork = EndpointPair("f", "Fork", Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.0))
        store.dao.pair(PairRow.from(fork))
        val tracker = EndpointTracker(listOf(fork))
        listOf(
                fix(0.0, 0, 0.01),
                fix(0.0, 10000, 0.009),
                fix(0.0, 20000, 0.005),
                fix(0.0, 30000),
                fix(0.0, 100000),
                fix(0.001, 110000),
                fix(0.005, 120000),
                fix(0.01, 130000),
            )
            .forEach { store.accept(tracker.feed(it)) }
        assertEquals(
            listOf("outward" to 1, "return" to 2),
            store.dao.journeys().map { it.direction to it.endpoint },
        )
        assertTrue(store.dao.journeys().all { it.status == "complete" })
        val manual = JourneyRow("teach", null, "outward", "manual", 200000, 200000)
        store.dao.journey(manual)
        listOf(fix(0.0, 200000, 0.01), fix(0.0, 210000, 0.005), fix(0.0, 220000)).forEach {
            store.dao.point(TrackRow.from(manual.id, it))
        }
        assertTrue(store.finishManual(manual.id, 320000, 220000))
        assertEquals(1, store.dao.pairs().size)
        val taught = store.dao.journey("teach")!!
        assertEquals(Triple("f", "return", 2), Triple(taught.pairId, taught.direction, taught.endpoint))
    }

    @Test
    fun manualTeachingThenAutomaticDifferentRoutesAndReturnPersist() = withStore { store, name ->
        val manual = JourneyRow("teach", null, "outward", "manual", 100000, 0)
        store.dao.journey(manual)
        listOf(fix(0.0, 0), fix(0.002, 10000), fix(0.006, 20000), fix(0.01, 30000)).forEach {
            store.dao.point(TrackRow.from(manual.id, it))
        }
        assertTrue(store.finishManual(manual.id, 130000, 30000))
        assertFalse(store.finishManual(manual.id, 130000, 30000))
        val pair = store.dao.pairs().single()
        assertEquals(pair.id, store.dao.journey("teach")!!.pairId)
        val tracker = EndpointTracker(listOf(pair.spec()))
        listOf(
                fix(0.01, 40000),
                fix(0.009, 50000),
                fix(0.005, 60000, -0.001),
                fix(0.0, 70000),
                fix(0.0, 90000),
                fix(0.0, 110000),
                fix(0.0, 130000),
                fix(0.001, 140000),
                fix(0.005, 150000, 0.001),
                fix(0.01, 160000),
            )
            .forEach { store.accept(tracker.feed(it)) }
        assertEquals(3, store.dao.journeys().count { it.status == "complete" })
        assertEquals(1, store.dao.journeys().count { it.direction == "return" })
        val before = store.dao.journeys()
        store.db.close()
        val reopened = open(name)
        try {
            assertEquals(before, reopened.data().journeys())
            assertEquals(4, reopened.data().points("teach").size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun interruptionKeepsPartialTraceWithoutSuccessfulTiming() = withStore { store, _ ->
        store.dao.journey(JourneyRow("partial", null, "outward", "manual", 100000, 0))
        store.dao.point(TrackRow.from("partial", fix(0.0, 1000)))
        store.dao.point(TrackRow.from("partial", fix(0.001, 10000)))
        store.recover()
        assertEquals("interrupted", store.dao.journey("partial")!!.status)
        assertEquals(2, store.dao.points("partial").size)
        assertEquals(10000L, store.dao.journey("partial")!!.durationMs)
    }

    @Test
    fun noGpsOrUnseparatedEndpointsDoNotTeachPairs() = withStore { store, _ ->
        store.dao.journey(JourneyRow("empty", null, "outward", "manual", 100000, 0))
        assertFalse(store.finishManual("empty", 110000, 10000))
        assertEquals("no GPS", store.dao.journey("empty")!!.status)
        store.dao.journey(JourneyRow("near", null, "outward", "manual", 100000, 0))
        store.dao.point(TrackRow.from("near", fix(0.0, 0)))
        store.dao.point(TrackRow.from("near", fix(0.00001, 10000)))
        assertFalse(store.finishManual("near", 110000, 10000))
        assertTrue(store.dao.pairs().isEmpty())
    }

    @Test
    fun removedPairRetainsItsRoutesAndEndpointContext() = withStore { store, _ ->
        val pair = EndpointPair("p", "Test", Point(0.0, 0.0), Point(0.0, 0.01))
        store.dao.pair(PairRow.from(pair))
        val tracker = EndpointTracker(listOf(pair))
        listOf(fix(0.0, 0), fix(0.001, 10000), fix(0.01, 30000)).forEach {
            store.accept(tracker.feed(it))
        }
        store.dao.archive(pair.id)
        assertTrue(store.dao.pairs().single().archived)
        assertEquals(1, store.dao.journeys().size)
        assertEquals(3, store.dao.points(store.dao.journeys().single().id).size)
    }
    @Test
    fun deletionRemovesOnlyChosenRecordingAndPersistsAfterReopen() = withStore { store, name ->
        val pair = PairRow.from(EndpointPair("pair", "Saved pair", Point(0.0, 0.0), Point(0.0, 0.01)))
        store.dao.pair(pair)
        val chosen = JourneyRow("delete", pair.id, "outward", "manual", 100000, 0, 110000, 10000, "complete")
        val keep = chosen.copy(id = "keep", durationMs = 20000)
        store.dao.journey(chosen)
        store.dao.journey(keep)
        store.dao.point(TrackRow.from(chosen.id, fix(0.0, 0)))
        store.dao.point(TrackRow.from(chosen.id, fix(0.01, 10000)))
        store.dao.point(TrackRow.from(keep.id, fix(0.005, 5000)))
        val keptPoints = store.dao.points(keep.id)
        store.db.openHelper.writableDatabase.execSQL("INSERT INTO configs VALUES ('legacy',1,'retained')")
        store.deleteRecording(chosen.id)
        assertNull(store.dao.journey(chosen.id))
        assertTrue(store.dao.points(chosen.id).isEmpty())
        assertEquals(listOf(pair), store.dao.pairs())
        assertEquals(listOf(keep), store.dao.journeys())
        assertEquals(keptPoints, store.dao.points(keep.id))
        store.db.close()
        val reopened = Room.databaseBuilder(context, Database::class.java, name).build()
        try {
            assertNull(reopened.data().journey(chosen.id))
            assertTrue(reopened.data().points(chosen.id).isEmpty())
            assertEquals(listOf(pair), reopened.data().pairs())
            assertEquals(listOf(keep), reopened.data().journeys())
            assertEquals(keptPoints, reopened.data().points(keep.id))
            reopened.openHelper.readableDatabase.query("SELECT payload FROM configs WHERE id='legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("retained", it.getString(0))
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun unpairedFinishedRecordingsAndRepeatedDeletionAreSupported() = withStore { store, _ ->
        for (status in listOf("complete", "interrupted", "no GPS")) {
            val recording = JourneyRow(status, null, "outward", "manual", 100000, 0, 110000, 10000, status)
            store.dao.journey(recording)
            if (status != "no GPS") store.dao.point(TrackRow.from(recording.id, fix(0.0, 0)))
            store.deleteRecording(recording.id)
            store.deleteRecording(recording.id)
            assertNull(store.dao.journey(recording.id))
            assertTrue(store.dao.points(recording.id).isEmpty())
        }
        store.deleteRecording("missing")
        assertTrue(store.dao.journeys().isEmpty())
    }

    @Test
    fun activeRecordingCannotBeDeleted() = withStore { store, _ ->
        val active = JourneyRow("active", null, "outward", "manual", 100000, 0)
        store.dao.journey(active)
        store.dao.point(TrackRow.from(active.id, fix(0.0, 0)))
        val points = store.dao.points(active.id)
        try {
            store.deleteRecording(active.id)
            fail("Active recording deletion must be refused")
        } catch (expected: IllegalStateException) {
            assertEquals("Stop the recording before deleting it.", expected.message)
        }
        assertEquals(active, store.dao.journey(active.id))
        assertEquals(points, store.dao.points(active.id))
    }

    @Test
    fun failedDeletionRollsBackItsGpsPoints() = withStore { store, _ ->
        val recording = JourneyRow("rollback", null, "outward", "manual", 100000, 0, 110000, 10000, "complete")
        store.dao.journey(recording)
        store.dao.point(TrackRow.from(recording.id, fix(0.0, 0)))
        val points = store.dao.points(recording.id)
        store.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_deletion BEFORE DELETE ON journeys BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        try {
            store.deleteRecording(recording.id)
            fail("Injected database failure must surface")
        } catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(recording, store.dao.journey(recording.id))
        assertEquals(points, store.dao.points(recording.id))
    }

}
