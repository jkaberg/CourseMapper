package com.coursemapper.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v14 -> v15 against a real v14 database built from Room's exported `14.json`.
 * The migration writes CREATE TABLE by hand and Room validates against the
 * entities on open, so drift crashes the first launch after an update.
 * `MigrationTestHelper` would need instrumentation assets, hence the classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OfflinePackMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: java.io.File

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    /** Database with the exact v14 schema from Room's export. */
    private fun createV14Database(seed: (SupportSQLiteDatabase) -> Unit = {}) {
        val schema = JSONObject(readSchemaResource(14))
            .getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        val statements = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            statements += entity.getString("createSql")
                .replace("\${TABLE_NAME}", tableName)

            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) {
                    statements += indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)
                }
            }
        }
        schema.optJSONArray("views")?.let { views ->
            for (i in 0 until views.length()) {
                statements += views.getJSONObject(i).getString("createSql")
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
                        // Room stores its schema fingerprint here and compares
                        // it on open; without the row it treats the database as
                        // one it has never seen.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES (42, '${schema.getString("identityHash")}')"
                        )
                        seed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { /* force onCreate */ }
        helper.close()
    }

    /** Open at v15 with the migration registered; Room validates the result. */
    private fun openMigrated(): CourseMapperDatabase =
        Room.databaseBuilder(context, CourseMapperDatabase::class.java, DB_NAME)
            .addMigrations(CourseMapperDatabase.MIGRATION_14_15, CourseMapperDatabase.MIGRATION_15_16)
            .allowMainThreadQueries()
            .build()

    private fun readSchemaResource(version: Int): String {
        val path = "com.coursemapper.data.db.CourseMapperDatabase/$version.json"
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing exported schema on the test classpath: $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `migrating from 14 to 15 produces the schema Room expects`() = runTest {
        createV14Database { db ->
            db.execSQL(
                """
                INSERT INTO offline_regions
                    (label, associatedCourseId, sizeBytes, status, coverageBoundsJson,
                     maplibreRegionId)
                VALUES ('Trondheim', 1, 12345, 'ready', '{}', 7)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        // Opening runs the migration and then Room's own schema validation; a
        // mismatch between the hand-written DDL and the entities throws here.
        db.offlinePackDao().observeAllSnapshot()
        db.close()
    }

    /**
     * The old table is dropped, not migrated - old "ready" rows were light style
     * only. The reconciler cleans up the MapLibre regions.
     */
    @Test
    fun `the old table is dropped and no pack is invented from its rows`() = runTest {
        createV14Database { db ->
            db.execSQL(
                "INSERT INTO offline_regions " +
                    "(label, associatedCourseId, sizeBytes, status, coverageBoundsJson) " +
                    "VALUES ('Trondheim', 1, 12345, 'ready', '{}')"
            )
        }

        val db = openMigrated()

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='offline_regions'",
            emptyArray()
        ).use { cursor ->
            assertFalse("offline_regions must be dropped, not kept", cursor.moveToFirst())
        }
        assertTrue(
            "a v14 row must not become a pack claiming to be ready",
            db.offlinePackDao().observeAllSnapshot().isEmpty()
        )
        db.close()
    }

    @Test
    fun `the new tables are usable immediately after migrating`() = runTest {
        createV14Database()
        val db = openMigrated()
        val packs = db.offlinePackDao()

        val routeId = db.routeDao().insert(BaseRouteEntity(name = "R", source = "gpx_import"))
        val courseId = db.courseDao().insert(
            ComposedCourseEntity(
                baseRouteId = routeId,
                name = "Marathon",
                totalDistanceMetres = 42_195.0
            )
        )
        val packId = packs.insertPack(
            OfflinePackEntity(label = "Trondheim", boundsJson = "{}")
        )
        packs.insertCourseLinks(listOf(OfflinePackCourseEntity(packId, courseId)))
        packs.insertRegion(
            OfflinePackRegionEntity(packId = packId, styleVariant = "light")
        )

        // Exercises the isDeleted join, the enum converters and both cascades - 
        // the parts of the schema a hand-written migration is most likely to get
        // subtly wrong.
        assertEquals(listOf(courseId), packs.liveCourseIds(packId))
        db.courseDao().softDelete(courseId)
        assertEquals(listOf(packId), packs.findOrphanPackIds())

        packs.deletePack(packId)
        assertTrue(packs.getRegions(packId).isEmpty())
        db.close()
    }

    @Test
    fun `the migration leaves every other table untouched`() = runTest {
        createV14Database { db ->
            db.execSQL(
                "INSERT INTO base_routes (name, notes, createdAt, updatedAt, isApproved, " +
                    "isDeleted, distanceMetres, source) " +
                    "VALUES ('Recording', '', 0, 0, 1, 0, 5000.0, 'gpx_import')"
            )
        }

        val db = openMigrated()

        val routes = db.routeDao().getById(1L)
        assertEquals(
            "a maps-only migration must not touch a recording",
            "Recording",
            routes?.name
        )
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
