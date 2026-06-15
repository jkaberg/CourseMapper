package com.coursemapper.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.gpx.GpxExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes GPX (or CSV) to `cache/share/` and builds a share intent with a
 * content:// URI, no storage permission needed. Caller wraps it in
 * [Intent.createChooser]. [cleanupShareTemp] removes old files.
 */
@Singleton
class RouteShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gpxExporter: GpxExporter
) {
    companion object {
        private const val AUTHORITY         = "com.coursemapper.fileprovider"
        private const val SHARE_DIR         = "share"
        private const val GPX_MIME          = "application/gpx+xml"
        const val MAX_SHARE_FILE_AGE_MS     = 24 * 60 * 60 * 1_000L  // 24 h
    }

    private val shareDir: File
        get() = context.cacheDir.resolve(SHARE_DIR).also { it.mkdirs() }

    /** Share intent for [route] with GPX attachment and a text summary. */
    fun buildRouteShareIntent(route: BaseRoute): Intent {
        val gpx      = gpxExporter.exportRoute(route)
        val fileName = sanitiseFileName("${route.name}.gpx")
        val uri      = writeGpxAndGetUri(fileName, gpx)
        val summary  = buildRouteSummary(route)

        return buildShareIntent(uri, fileName, summary)
    }

    /** Share intent for [course], [composedPoints] must be the full composed polyline. */
    fun buildCourseShareIntent(
        course: ComposedCourse,
        composedPoints: List<RoutePoint>
    ): Intent {
        val gpx      = gpxExporter.exportCourse(course, composedPoints)
        val fileName = sanitiseFileName("${course.name}.gpx")
        val uri      = writeGpxAndGetUri(fileName, gpx)
        val summary  = buildCourseSummary(course)

        return buildShareIntent(uri, fileName, summary)
    }

    /** Share intent for any text file, eg the run log CSV. */
    fun buildTextFileShareIntent(
        fileName: String,
        content: String,
        mimeType: String,
        summary: String
    ): Intent {
        val safeName = sanitiseFileName(fileName)
        val file = shareDir.resolve(safeName)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, summary)
            putExtra(Intent.EXTRA_SUBJECT, safeName.substringBeforeLast('.'))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Remove temp GPX files older than [maxAgeMs] from the share directory. */
    fun cleanupShareTemp(maxAgeMs: Long = MAX_SHARE_FILE_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        shareDir.listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun writeGpxAndGetUri(fileName: String, gpxContent: String): Uri {
        val file = shareDir.resolve(fileName)
        file.writeText(gpxContent, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    private fun buildShareIntent(uri: Uri, fileName: String, summaryText: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type     = GPX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, summaryText)
            putExtra(Intent.EXTRA_SUBJECT, fileName.removeSuffix(".gpx"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildRouteSummary(route: BaseRoute): String = buildString {
        appendLine("Route: ${route.name}")
        if (route.notes.isNotBlank()) appendLine(route.notes)
        appendLine("Distance: ${"%.2f km".format(route.distanceMetres / 1000.0)}")
        appendLine("Source: ${if (route.source == "gpx_import") "GPX import" else "Recorded"}")
    }

    private fun buildCourseSummary(course: ComposedCourse): String = buildString {
        appendLine("Course: ${course.name}")
        appendLine("Total distance: ${"%.2f km".format(course.displayDistanceMetres / 1000.0)}")
        if (course.isMeasured) {
            appendLine("Drawn line: ${"%.2f km".format(course.totalDistanceMetres / 1000.0)}")
        }
        appendLine("Laps: ${course.lapCount}")
        if (course.finalLapDistanceMetres > 0) {
            appendLine("Final partial lap: ${"%.2f km".format(course.finalLapDistanceMetres / 1000.0)}")
        }
        appendLine("Markers: ${course.markers.size}")
    }

    /** Replace characters unsafe for filenames with underscores. */
    private fun sanitiseFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
}
