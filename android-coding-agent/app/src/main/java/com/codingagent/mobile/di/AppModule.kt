package com.codingagent.mobile.di

import android.content.Context
import com.codingagent.mobile.bridge.IntelligenceBridge
import com.codingagent.mobile.runtime.RuntimeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRuntimeManager(
        @ApplicationContext context: Context
    ): RuntimeManager = RuntimeManager(context)

    @Provides
    @Singleton
    fun provideIntelligenceBridge(): IntelligenceBridge = IntelligenceBridge()
}
