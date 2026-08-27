package com.coursemapper.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.data.repository.toDomain
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v15 -> v16 against a real v15 database. Room validates the schema on first
 * open, so a wrong column type crashes the first launch after an update.
 * Existing courses must come through unmeasured (0) with everything else intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MeasuredLengthMigrationTest {

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

    /** Build a database with the exact v15 schema Room exported. */
    private fun createV15Database(seed: (SupportSQLiteDatabase) -> Unit = {}) {
        val schema = JSONObject(readSchemaResource(15)).getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        val statements = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            statements += entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) {
                    statements += indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)
                }
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
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

    /** Open at the current version; Room validates the migrated schema itself. */
    private fun openMigrated(): CourseMapperDatabase =
        Room.databaseBuilder(context, CourseMapperDatabase::class.java, DB_NAME)
            .addMigrations(CourseMapperDatabase.MIGRATION_15_16)
            .allowMainThreadQueries()
            .build()

    private fun readSchemaResource(version: Int): String {
        val path = "com.coursemapper.data.db.CourseMapperDatabase/$version.json"
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing exported schema on the test classpath: $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun seedOneCourse(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO base_routes
                (id, name, notes, createdAt, updatedAt, isApproved, isDeleted,
                 distanceMetres, source)
            VALUES (1, 'Trondheim loop', '', 1, 1, 1, 0, 42650.36, 'gpx_import')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO composed_courses
                (id, baseRouteId, name, notes, createdAt, updatedAt, isDeleted,
                 lapCount, finalLapDistanceMetres, totalDistanceMetres,
                 presetSnapshotJson, presetId, variantId, isDraft,
                 buildSpecKind, buildSpecLapCount, buildSpecTargetMetres, geometryEditJson)
            VALUES (1, 1, 'Helmaraton', 'notes here', 100, 200, 0,
                    1, 0.0, 42650.36,
                    '[{"type":"distance","intervalMetres":1000}]', 5, NULL, 0,
                    'fixed_laps', 1, 0.0, '')
            """.trimIndent()
        )
    }

    @Test
    fun `migrating from 15 to 16 produces the schema Room expects`() = runTest {
        createV15Database()
        // Opening is the assertion: Room runs onValidateSchema and throws if the
        // migration did not produce exactly the schema the entities describe.
        val db = openMigrated()
        db.courseDao().getById(1L)
        db.close()
    }

    @Test
    fun `an existing course comes through unmeasured`() = runTest {
        createV15Database(::seedOneCourse)

        val db = openMigrated()
        val course = requireNotNull(db.courseDao().getById(1L))
        assertEquals(
            "no measurement may be invented for a course nobody measured",
            0.0, course.measuredLengthMetres, 0.0
        )
        db.close()
    }

    @Test
    fun `the migration leaves the rest of the course untouched`() = runTest {
        createV15Database(::seedOneCourse)

        val db = openMigrated()
        val course = requireNotNull(db.courseDao().getById(1L))
        assertEquals("Helmaraton", course.name)
        assertEquals("notes here", course.notes)
        assertEquals(1, course.lapCount)
        assertEquals(42650.36, course.totalDistanceMetres, 0.001)
        assertEquals(5L, course.presetId)
        assertEquals("fixed_laps", course.buildSpecKind)
        assertEquals(100L, course.createdAt)
        assertTrue(course.presetSnapshotJson.contains("intervalMetres"))
        db.close()
    }

    /** Unmeasured gives scale 1.0, so existing courses place markers as before. */
    @Test
    fun `a migrated course places markers exactly as it did before`() = runTest {
        createV15Database(::seedOneCourse)

        val db = openMigrated()
        val domain = requireNotNull(db.courseDao().getById(1L)).toDomain()
        assertEquals(1.0, domain.distanceScale, 0.0)
        assertEquals(42650.36, domain.displayDistanceMetres, 0.001)
        db.close()
    }

    private companion object {
        const val DB_NAME = "measured_length_migration_test.db"
    }
}
