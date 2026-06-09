package com.coursemapper.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database.
 *
 * Schema versions:
 * - 1 initial
 * - 2 `version` on distance_markers (optimistic locking)
 * - 3 route networks, junctions, segments and variants
 * - 4 `notes` and `isDraft` on composed_courses
 * - 5 `maplibreRegionId` on offline_regions
 * - 6 build spec columns on composed_courses
 * - 7 `fromMetres` / `toMetres` on route_segments
 * - 8 completed/skipped sets on navigation_sessions
 * - 9 placement runs, navigation_sessions dropped
 * - 10 `travelProfile` on placement_runs
 * - 11 course groups
 * - 12 raw lat/lon on route_points so smoothing can be re-run
 * - 13 `geometryEditJson` on composed_courses
 * - 14 `groupId` on placement_runs
 * - 15 offline packs replace offline_regions
 * - 16 `measuredLengthMetres` on composed_courses
 *
 * No destructive migrations in release builds, add a migration before bumping [version].
 */
@Database(
    entities = [
        BaseRouteEntity::class,
        RoutePointEntity::class,
        MarkerPresetEntity::class,
        ComposedCourseEntity::class,
        DistanceMarkerEntity::class,
        PlacementStopEntity::class,
        OfflinePackEntity::class,
        OfflinePackRegionEntity::class,
        OfflinePackCourseEntity::class,
        // route networks
        RouteNetworkEntity::class,
        RouteJunctionEntity::class,
        RouteSegmentEntity::class,
        RouteVariantEntity::class,
            PlacementRunEntity::class,
        PlacementRunCourseEntity::class,
        PlacementRunStopEntity::class,
            CourseGroupEntity::class,
        CourseGroupMemberEntity::class,
    ],
    version = 16,
    exportSchema = true
)
@androidx.room.TypeConverters(OfflineConverters::class)
abstract class CourseMapperDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun courseDao(): CourseDao
    abstract fun markerPresetDao(): MarkerPresetDao
    abstract fun offlinePackDao(): OfflinePackDao
    abstract fun routeNetworkDao(): RouteNetworkDao
    abstract fun routeVariantDao(): RouteVariantDao
    abstract fun placementRunDao(): PlacementRunDao
    abstract fun courseGroupDao(): CourseGroupDao

    companion object {
        /** 1 -> 2: optimistic lock version on markers. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE distance_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** 3 -> 4: notes and draft flag, existing courses count as published. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN notes TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN isDraft INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** 4 -> 5: MapLibre's region id, so refresh and delete can find the region. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE offline_regions ADD COLUMN maplibreRegionId INTEGER"
                )
            }
        }

        /**
         * 2 -> 3: route network tables. Existing routes get wrapped in a single
         * segment network so the routes screen has no legacy code path.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `route_networks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isDeleted` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `route_junctions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `networkId` INTEGER NOT NULL,
                        `latDeg` REAL NOT NULL,
                        `lonDeg` REAL NOT NULL,
                        `label` TEXT,
                        FOREIGN KEY(`networkId`) REFERENCES `route_networks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_junctions_networkId` " +
                    "ON `route_junctions` (`networkId`)"
                )

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `route_segments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `networkId` INTEGER NOT NULL,
                        `baseRouteId` INTEGER NOT NULL,
                        `isMainTrunk` INTEGER NOT NULL,
                        `fromJunctionId` INTEGER,
                        `toJunctionId` INTEGER,
                        `orderIndex` INTEGER NOT NULL,
                        `distanceMetres` REAL NOT NULL,
                        `isDeleted` INTEGER NOT NULL,
                        FOREIGN KEY(`networkId`) REFERENCES `route_networks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`baseRouteId`) REFERENCES `base_routes`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_segments_networkId` " +
                    "ON `route_segments` (`networkId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_segments_baseRouteId` " +
                    "ON `route_segments` (`baseRouteId`)"
                )

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `route_variants` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `networkId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `segmentIdsJson` TEXT NOT NULL,
                        `compiledPolylineJson` TEXT,
                        `totalDistanceMetres` REAL,
                        `isApproved` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isDeleted` INTEGER NOT NULL,
                        FOREIGN KEY(`networkId`) REFERENCES `route_networks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_variants_networkId` " +
                    "ON `route_variants` (`networkId`)"
                )

                db.execSQL("ALTER TABLE composed_courses ADD COLUMN variantId INTEGER")

                val now = System.currentTimeMillis()
                val cursor = db.query(
                    "SELECT id, name, notes, distanceMetres FROM base_routes WHERE isDeleted = 0"
                )
                while (cursor.moveToNext()) {
                    val routeId = cursor.getLong(0)
                    val name    = cursor.getString(1) ?: ""
                    val notes   = cursor.getString(2) ?: ""
                    val distM   = cursor.getDouble(3)

                    db.execSQL(
                        "INSERT INTO route_networks (name, notes, createdAt, updatedAt, isDeleted) " +
                        "VALUES (?, ?, ?, ?, 0)",
                        // explicit Array<Any>, mixing String and Long infers an
                        // intersection type Kotlin warns about
                        arrayOf<Any>(name, notes, now, now)
                    )
                    val idCursor = db.query("SELECT last_insert_rowid()")
                    idCursor.moveToFirst()
                    val networkId = idCursor.getLong(0)
                    idCursor.close()

                    db.execSQL(
                        "INSERT INTO route_segments " +
                        "(networkId, baseRouteId, isMainTrunk, fromJunctionId, toJunctionId, " +
                        " orderIndex, distanceMetres, isDeleted) " +
                        "VALUES (?, ?, 1, NULL, NULL, 0, ?, 0)",
                        arrayOf<Any>(networkId, routeId, distM)
                    )
                }
                cursor.close()
            }
        }

        /**
         * 5 -> 6: build spec columns. Best effort backfill, a partial final lap
         * becomes "target_distance" and everything else "fixed_laps".
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN buildSpecKind TEXT NOT NULL DEFAULT 'manual'"
                )
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN buildSpecLapCount INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN buildSpecTargetMetres REAL NOT NULL DEFAULT 0.0"
                )
                // Back-fill: rows with a partial lap → target_distance spec
                db.execSQL("""
                    UPDATE composed_courses
                    SET buildSpecKind = 'target_distance',
                        buildSpecLapCount = 1,
                        buildSpecTargetMetres = totalDistanceMetres
                    WHERE finalLapDistanceMetres > 0.0
                """.trimIndent())
                // Back-fill: rows with no partial lap → fixed_laps spec
                db.execSQL("""
                    UPDATE composed_courses
                    SET buildSpecKind = 'fixed_laps',
                        buildSpecLapCount = lapCount,
                        buildSpecTargetMetres = 0.0
                    WHERE finalLapDistanceMetres = 0.0
                """.trimIndent())
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE route_segments ADD COLUMN fromMetres REAL"
                )
                db.execSQL(
                    "ALTER TABLE route_segments ADD COLUMN toMetres REAL"
                )
                // Existing rows keep NULL in both columns, meaning “full polyline”.
            }
        }

        /** 7 -> 8: existing rows keep NULL and resume from lastCompletedIndex. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE navigation_sessions ADD COLUMN completedIndicesJson TEXT"
                )
                db.execSQL(
                    "ALTER TABLE navigation_sessions ADD COLUMN skippedIndicesJson TEXT"
                )
            }
        }

        /**
         * 8 -> 9: placement runs replace navigation_sessions. In-progress sessions
         * are dropped, the run model can't represent them.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `placement_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `orderingMode` TEXT NOT NULL,
                        `spineCourseId` INTEGER,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        `totalPlannedMetres` REAL NOT NULL,
                        `isDeleted` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `placement_run_courses` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `courseId` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `placement_runs`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`courseId`) REFERENCES `composed_courses`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_placement_run_courses_runId` " +
                    "ON `placement_run_courses` (`runId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_placement_run_courses_courseId` " +
                    "ON `placement_run_courses` (`courseId`)"
                )

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `placement_run_stops` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `stopIndex` INTEGER NOT NULL,
                        `latDeg` REAL NOT NULL,
                        `lonDeg` REAL NOT NULL,
                        `signsJson` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `stateChangedAt` INTEGER,
                        FOREIGN KEY(`runId`) REFERENCES `placement_runs`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_placement_run_stops_runId` " +
                    "ON `placement_run_stops` (`runId`)"
                )

                db.execSQL("DROP TABLE IF EXISTS `navigation_sessions`")
            }
        }

        /** 9 → 10: travel profile for run leg routing. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE placement_runs ADD COLUMN travelProfile TEXT NOT NULL DEFAULT 'car'"
                )
            }
        }

        /** 10 → 11: course groups (combined courses). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `course_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isDeleted` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `course_group_members` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `groupId` INTEGER NOT NULL,
                        `courseId` INTEGER NOT NULL,
                        FOREIGN KEY(`groupId`) REFERENCES `course_groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`courseId`) REFERENCES `composed_courses`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_course_group_members_groupId` " +
                    "ON `course_group_members` (`groupId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_course_group_members_courseId` " +
                    "ON `course_group_members` (`courseId`)"
                )
            }
        }

        /**
         * 12 -> 13: geometry edit as one JSON column, it's always read and written
         * whole. Empty string means no edit.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE composed_courses ADD COLUMN geometryEditJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * 11 -> 12: raw observation next to the filtered point. NULL for old rows,
         * the smoother already overwrote them.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE route_points ADD COLUMN rawLatDeg REAL")
                db.execSQL("ALTER TABLE route_points ADD COLUMN rawLonDeg REAL")
            }
        }

        /** 13 -> 14: launching group of a run, NULL means ad-hoc. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placement_runs ADD COLUMN groupId INTEGER")
            }
        }

        /**
         * 14 -> 15: offline packs.
         *
         * `offline_regions` is dropped, not migrated. It's only cache (tiles live in
         * MapLibre's db), old rows were light style only so they can't be "ready"
         * under the new model, and per-course rows don't map to per-area packs.
         * [com.coursemapper.offline.OfflineReconciler] deletes the orphaned MapLibre
         * regions on next launch.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS offline_regions")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_packs (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        label TEXT NOT NULL,
                        boundsJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        failureReason TEXT,
                        failureMessage TEXT,
                        userChoice TEXT NOT NULL,
                        estimatedBytes INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        completedResourceCount INTEGER NOT NULL,
                        requiredResourceCount INTEGER NOT NULL,
                        minZoom REAL NOT NULL,
                        maxZoom REAL NOT NULL,
                        createdAt INTEGER NOT NULL,
                        downloadedAt INTEGER,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_pack_regions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        packId INTEGER NOT NULL,
                        styleVariant TEXT NOT NULL,
                        maplibreRegionId INTEGER,
                        status TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        FOREIGN KEY(packId) REFERENCES offline_packs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_offline_pack_regions_packId " +
                        "ON offline_pack_regions(packId)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_pack_courses (
                        packId INTEGER NOT NULL,
                        courseId INTEGER NOT NULL,
                        PRIMARY KEY(packId, courseId),
                        FOREIGN KEY(packId) REFERENCES offline_packs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_offline_pack_courses_packId " +
                        "ON offline_pack_courses(packId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_offline_pack_courses_courseId " +
                        "ON offline_pack_courses(courseId)"
                )
            }
        }

        /**
         * 15 -> 16: measured course length. 0 means not measured, which keeps
         * marker placement exactly as before. Can't be backfilled from the polyline.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE composed_courses " +
                        "ADD COLUMN measuredLengthMetres REAL NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
