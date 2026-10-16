package com.frynetworks.fryapp.di

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.DeviceDao
import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.api.boundedIndexerClient
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheDao
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheDao
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerDao
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.impl.AlgodRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.MinerRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.RewardsRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.StakeRepositoryImpl
import com.frynetworks.fryapp.data.dashboard.repo.impl.TxResolverImpl
import com.frynetworks.fryapp.data.pending.ClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.SharedPrefsClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.SharedPrefsPendingTransferStore
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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

    /** Absolute-URL GETs against the two public indexers on a bounded client: 15 s per call, no redirects. */
    @Provides
    @Singleton
    fun provideIndexerApi(@AlgodClient retrofit: Retrofit): IndexerApi = retrofit.bounded().create(IndexerApi::class.java)

    /** The resolver reads algod through the same bounded client, not the shared one with its 60 s long-poll timeout. */
    @Provides
    @Singleton
    fun provideTxResolver(@AlgodClient retrofit: Retrofit, indexer: IndexerApi): TxResolver =
        TxResolverImpl(AlgodRepositoryImpl(retrofit.bounded().create(AlgodApi::class.java)), indexer)

    private fun Retrofit.bounded(): Retrofit = newBuilder().callFactory(boundedIndexerClient(callFactory() as OkHttpClient)).build()

    /** "fry_pending_transfer": no sign-out, session-clear or wallet-disconnect path deletes this file. */
    @Provides
    @Singleton
    fun providePendingTransferStore(@ApplicationContext context: Context): PendingTransferStore =
        SharedPrefsPendingTransferStore(context.getSharedPreferences("fry_pending_transfer", Context.MODE_PRIVATE))

    /** "fry_claim_fee_binding": which claim each paid fee belongs to; cleared by no sign-out path either. */
    @Provides
    @Singleton
    fun provideClaimFeeBindingStore(@ApplicationContext context: Context): ClaimFeeBindingStore =
        SharedPrefsClaimFeeBindingStore(context.getSharedPreferences("fry_claim_fee_binding", Context.MODE_PRIVATE))

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
