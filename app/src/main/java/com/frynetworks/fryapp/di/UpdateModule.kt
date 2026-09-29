package com.frynetworks.fryapp.di

import android.content.Context
import com.frynetworks.fryapp.BuildConfig
import com.frynetworks.fryapp.update.AndroidUpdateSources
import com.frynetworks.fryapp.update.ForegroundTracker
import com.frynetworks.fryapp.update.InstallInhibitor
import com.frynetworks.fryapp.update.UpdateCoordinator
import com.frynetworks.fryapp.update.UpdatePrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App self-update (O-2, contract C-6). */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @Singleton
    fun provideUpdatePrefs(@ApplicationContext context: Context): UpdatePrefs = UpdatePrefs(context)

    @Provides
    @Singleton
    fun provideUpdateCoordinator(
        @ApplicationContext context: Context,
        prefs: UpdatePrefs,
        inhibitor: InstallInhibitor,
        foreground: ForegroundTracker,
    ): UpdateCoordinator = UpdateCoordinator(
        installedPackage = context.packageName,
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        sources = AndroidUpdateSources(context),
        store = prefs,
        inhibited = { inhibitor.inhibited },
        foreground = { foreground.resumed },
    )
}
