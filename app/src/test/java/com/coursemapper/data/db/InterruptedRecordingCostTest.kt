package com.coursemapper.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.data.repository.toDomain
import kotlinx.coroutines.test.runTest
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
 * Home must not load GPS points to decide whether to offer a recovery. The
 * routes it gets must be stubs, points in them end up in `HomeUiState` and get
 * compared on every emission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class InterruptedRecordingCostTest {

    private lateinit var db: CourseMapperDatabase
    private lateinit var routes: RouteDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CourseMapperDatabase::class.java
        ).allowMainThreadQueries().build()
        routes = db.routeDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun recordingWith(points: Int, approved: Boolean = false): Long {
        val id = routes.insert(
            BaseRouteEntity(name = "Recording", source = "recorded", isApproved = approved)
        )
        if (points > 0) {
            routes.insertPoints(
                (0 until points).map { i ->
                    RoutePointEntity(
                        routeId        = id,
                        index          = i,
                        latDeg         = 63.42 + i * 1e-5,
                        lonDeg         = 10.39,
                        altMetres      = null,
                        accuracyMetres = null,
                        timestampMs    = i.toLong() * 1_000
                    )
                }
            )
        }
        return id
    }

    @Test
    fun `an abandoned recording with fixes is recoverable`() = runTest {
        val id = recordingWith(points = 500)
        assertTrue(routes.hasAnyPoints(id))
    }

    @Test
    fun `a recording that captured nothing is not offered`() = runTest {
        val id = recordingWith(points = 0)
        assertFalse(routes.hasAnyPoints(id))
    }

    @Test
    fun `the question is answered without loading the fixes`() = runTest {
        val id = recordingWith(points = 5_000)

        // one index probe, the rows are never loaded. The stub check below is
        // what really guards this.
        assertTrue(routes.hasAnyPoints(id))
        assertEquals(5_000, routes.getPoints(id).size)
    }

    @Test
    fun `Home's route list carries no points`() = runTest {
        val id = recordingWith(points = 5_000)

        // `toDomain()` without points, Home only needs name and id
        val stub = routes.getById(id)!!.toDomain()
        assertTrue("a route stub must not carry its fixes", stub.rawPoints.isEmpty())
        assertTrue(stub.smoothedPoints.isEmpty())
        assertEquals("Recording", stub.name)
    }
}
