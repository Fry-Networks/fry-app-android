package com.frynetworks.fryapp.di

import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.NotWiredAlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.NotWiredMinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.NotWiredRewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.NotWiredStakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Repository bindings for the dashboard feature. The data layer replaces the `NotWired*`
 * placeholders with the Retrofit + Room implementations; the UI layer only ever sees the interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
object DashboardRepositoryModule {
    @Provides @Singleton fun provideMinerRepository(): MinerRepository = NotWiredMinerRepository()
    @Provides @Singleton fun provideRewardsRepository(): RewardsRepository = NotWiredRewardsRepository()
    @Provides @Singleton fun provideStakeRepository(): StakeRepository = NotWiredStakeRepository()
    @Provides @Singleton fun provideAlgodRepository(): AlgodRepository = NotWiredAlgodRepository()
}
