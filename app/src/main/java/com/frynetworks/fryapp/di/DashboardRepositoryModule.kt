package com.frynetworks.fryapp.di

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.DeviceDao
import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheDao
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheDao
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerDao
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.impl.AlgodRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.MinerRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.RewardsRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.StakeRepositoryImpl
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Repository bindings for the dashboard feature: the Retrofit + Room implementations behind the
 * interfaces in `data/dashboard/repo`. The UI layer only ever sees the interfaces. The session
 * address is read live from [SessionRepository] on every call, so the repositories follow
 * sign-in/sign-out without being rebuilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object DashboardRepositoryModule {

    @Provides
    @Singleton
    fun provideDashboardApi(@DashboardClient retrofit: Retrofit): DashboardApi = retrofit.create(DashboardApi::class.java)

    @Provides
    @Singleton
    fun provideAlgodApi(@AlgodClient retrofit: Retrofit): AlgodApi = retrofit.create(AlgodApi::class.java)

    @Provides
    @Singleton
    fun provideRewardsRepository(
        api: DashboardApi,
        summaryDao: RewardSummaryCacheDao,
        totalsDao: AssetTotalsCacheDao,
        session: SessionRepository,
        bus: SessionEventBus,
        clock: ServerClock,
    ): RewardsRepository = RewardsRepositoryImpl(
        api = api,
        summaryDao = summaryDao,
        totalsDao = totalsDao,
        sessionAddress = { session.signedInAddress },
        bus = bus,
        onServerTime = { clock.observeServerMillis(it) },
    )

    @Provides
    @Singleton
    fun provideMinerRepository(
        api: DashboardApi,
        remoteDao: RemoteMinerDao,
        detailDao: MinerDetailCacheDao,
        summaryDao: RewardSummaryCacheDao,
        deviceDao: DeviceDao,
        rewards: RewardsRepository,
        session: SessionRepository,
        bus: SessionEventBus,
    ): MinerRepository = MinerRepositoryImpl(
        api = api,
        remoteDao = remoteDao,
        detailDao = detailDao,
        summaryDao = summaryDao,
        deviceDao = deviceDao,
        rewards = rewards,
        sessionAddress = { session.signedInAddress },
        bus = bus,
    )

    @Provides
    @Singleton
    fun provideStakeRepository(api: DashboardApi, bus: SessionEventBus): StakeRepository = StakeRepositoryImpl(api = api, bus = bus)

    @Provides
    @Singleton
    fun provideAlgodRepository(api: AlgodApi): AlgodRepository = AlgodRepositoryImpl(api)
}
