package com.coursemapper.di

import com.coursemapper.offline.MapLibreOfflineGateway
import com.coursemapper.offline.MapLibreOfflineGatewayImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The gateway is an interface so orchestration can be tested with a fake, see `OfflineDownloadOrchestrationTest`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {

    @Binds
    @Singleton
    abstract fun bindMapLibreOfflineGateway(
        impl: MapLibreOfflineGatewayImpl
    ): MapLibreOfflineGateway
}
