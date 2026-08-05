package com.coursemapper.offline

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Free space where MapLibre keeps its tiles, checked before a download starts. */
@Singleton
class DeviceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Usable bytes remaining, or [Long.MAX_VALUE] if the volume cannot be read. */
    fun freeBytes(): Long = try {
        val path = context.filesDir ?: return Long.MAX_VALUE
        StatFs(path.absolutePath).availableBytes
    } catch (_: Exception) {
        // Failing open: refusing a download because we could not measure the
        // disk would be a worse error than letting MapLibre try and report back.
        Long.MAX_VALUE
    }

    /** Whether [estimatedBytes] fits with headroom, filling the last MB breaks more than this feature. */
    fun hasRoomFor(estimatedBytes: Long): Boolean =
        freeBytes() > (estimatedBytes * HEADROOM_MULTIPLIER).toLong() + MIN_RESERVE_BYTES

    companion object {
        const val HEADROOM_MULTIPLIER = 1.3
        const val MIN_RESERVE_BYTES = 250L * 1024 * 1024
    }
}
