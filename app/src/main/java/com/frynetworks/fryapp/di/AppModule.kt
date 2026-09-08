package com.frynetworks.fryapp.di

import android.content.Context
import androidx.room.Room
import com.frynetworks.fryapp.BuildConfig
import com.frynetworks.fryapp.api.HardwareApi
import com.frynetworks.fryapp.data.DeviceDao
import com.frynetworks.fryapp.data.FryDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FryDatabase =
        Room.databaseBuilder(context, FryDatabase::class.java, "fry.db").build()

    @Provides
    fun provideDeviceDao(database: FryDatabase): DeviceDao = database.deviceDao()

    /**
     * Adds `Authorization: Bearer` only when [BuildConfig.HARDWAREAPI_TOKEN] is non-empty —
     * which it never is in a public build (the field defaults to "" and is only populated
     * from a developer's own, git-ignored local.properties). The app must never embed a
     * fleet-wide credential.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = BuildConfig.HARDWAREAPI_TOKEN
                val request = if (token.isNotEmpty()) {
                    chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("${BuildConfig.HARDWAREAPI_BASE}/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideHardwareApi(retrofit: Retrofit): HardwareApi = retrofit.create(HardwareApi::class.java)
}
