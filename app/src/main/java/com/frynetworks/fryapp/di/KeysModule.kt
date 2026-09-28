package com.frynetworks.fryapp.di

import com.frynetworks.fryapp.data.keys.KeyCheckApi
import com.frynetworks.fryapp.data.keys.KeyCheckClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Owner-key checks against the dashboard (contract C-3), on the signed dashboard client. */
@Module
@InstallIn(SingletonComponent::class)
object KeysModule {

    @Provides
    @Singleton
    fun provideKeyCheckClient(@DashboardClient retrofit: Retrofit): KeyCheckClient =
        KeyCheckClient(retrofit.create(KeyCheckApi::class.java))
}
