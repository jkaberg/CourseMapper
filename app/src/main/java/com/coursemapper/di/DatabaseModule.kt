package com.coursemapper.di

import android.content.Context
import androidx.room.Room
import com.coursemapper.data.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CourseMapperDatabase =
        Room.databaseBuilder(
            context,
            CourseMapperDatabase::class.java,
            "coursemapper.db"
        )
            .addMigrations(
                CourseMapperDatabase.MIGRATION_1_2,
                CourseMapperDatabase.MIGRATION_2_3,
                CourseMapperDatabase.MIGRATION_3_4,
                CourseMapperDatabase.MIGRATION_4_5,
                CourseMapperDatabase.MIGRATION_5_6,
                CourseMapperDatabase.MIGRATION_6_7,
                CourseMapperDatabase.MIGRATION_7_8,
                CourseMapperDatabase.MIGRATION_8_9,
                CourseMapperDatabase.MIGRATION_9_10,
                CourseMapperDatabase.MIGRATION_10_11,
                CourseMapperDatabase.MIGRATION_11_12,
                CourseMapperDatabase.MIGRATION_12_13,
                CourseMapperDatabase.MIGRATION_13_14,
                CourseMapperDatabase.MIGRATION_14_15,
                CourseMapperDatabase.MIGRATION_15_16
            )
            .build()

    @Provides fun provideRouteDao(db: CourseMapperDatabase): RouteDao = db.routeDao()
    @Provides fun provideCourseDao(db: CourseMapperDatabase): CourseDao = db.courseDao()
    @Provides fun provideMarkerPresetDao(db: CourseMapperDatabase): MarkerPresetDao = db.markerPresetDao()
    @Provides fun provideOfflinePackDao(db: CourseMapperDatabase): OfflinePackDao = db.offlinePackDao()
    @Provides fun provideRouteNetworkDao(db: CourseMapperDatabase): RouteNetworkDao = db.routeNetworkDao()
    @Provides fun provideRouteVariantDao(db: CourseMapperDatabase): RouteVariantDao = db.routeVariantDao()
    // Placement runs
    @Provides fun providePlacementRunDao(db: CourseMapperDatabase): PlacementRunDao = db.placementRunDao()
    // Course groups
    @Provides fun provideCourseGroupDao(db: CourseMapperDatabase): CourseGroupDao = db.courseGroupDao()
}
