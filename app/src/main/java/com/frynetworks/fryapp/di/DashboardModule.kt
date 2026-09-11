package com.frynetworks.fryapp.di

import android.content.Context
import com.frynetworks.fryapp.auth.EncryptedPrefsSessionStore
import com.frynetworks.fryapp.auth.FingerprintBinder
import com.frynetworks.fryapp.auth.NextAuthApi
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionStore
import com.frynetworks.fryapp.auth.SignInUseCase
import com.frynetworks.fryapp.auth.SignOutUseCase
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.network.dashboard.FingerprintRetryInterceptor
import com.frynetworks.fryapp.network.dashboard.HeaderPinInterceptor
import com.frynetworks.fryapp.network.dashboard.SecurityHeaderInterceptor
import com.frynetworks.fryapp.network.dashboard.SecurityHeaderSigner
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.cookies.CookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.EncryptedPrefsCookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import com.frynetworks.fryapp.wallet.WalletBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Dashboard session wiring. Two clients share one cookie jar:
 * - [AuthClient]: NextAuth endpoints (csrf, callback, capture-fingerprint, session) — no signing,
 *   no fingerprint retry, so the retry interceptor can rebind through it without a cycle.
 * - [DashboardClient]: data endpoints — signing on the guarded routes + one fingerprint-refresh retry.
 */
@Module
@InstallIn(SingletonComponent::class)
object DashboardModule {

    @Provides
    @Named("dashboardBaseUrl")
    fun provideDashboardBaseUrl(): String = DashboardConfig.BASE_URL + "/"

    @Provides
    @Singleton
    fun provideCookieStore(@ApplicationContext context: Context): CookieStore = EncryptedPrefsCookieStore(context)

    @Provides
    @Singleton
    fun provideCookieJar(store: CookieStore): PersistentCookieJar = PersistentCookieJar(store)

    @Provides
    @Singleton
    fun provideServerClock(): ServerClock = ServerClock()

    @Provides
    @Singleton
    fun provideSigner(): SecurityHeaderSigner = SecurityHeaderSigner(DashboardConfig.SIGNATURE_SECRET)

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): SessionStore = EncryptedPrefsSessionStore(context)

    @Provides
    @Singleton
    @AuthClient
    fun provideAuthClient(jar: PersistentCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(HeaderPinInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideNextAuthApi(@AuthClient client: OkHttpClient, @Named("dashboardBaseUrl") baseUrl: String): NextAuthApi =
        NextAuthApi.create(client, baseUrl)

    @Provides
    @Singleton
    fun provideFingerprintBinder(api: NextAuthApi): FingerprintBinder = FingerprintBinder(api)

    @Provides
    @Singleton
    @DashboardClient
    fun provideDashboardClient(
        jar: PersistentCookieJar,
        signer: SecurityHeaderSigner,
        clock: ServerClock,
        binder: FingerprintBinder,
        bus: SessionEventBus,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(HeaderPinInterceptor())
            .addInterceptor(SecurityHeaderInterceptor(signer, clock))
            .addInterceptor(FingerprintRetryInterceptor(rebind = { binder.rebindBlocking() }, onSessionEvent = { bus.emit(it) }))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @DashboardClient
    fun provideDashboardRetrofit(@DashboardClient client: OkHttpClient, @Named("dashboardBaseUrl") baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    /** Public algod node: a plain client — no cookie jar, no pinned headers, no signing, no developer bearer token. */
    @Provides
    @Singleton
    @AlgodClient
    fun provideAlgodClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // wait-for-block-after blocks server-side for up to a round
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @AlgodClient
    fun provideAlgodRetrofit(@AlgodClient client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(DashboardConfig.ALGOD_URL.let { if (it.endsWith("/")) it else "$it/" })
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideSessionRepository(api: NextAuthApi, jar: PersistentCookieJar, store: SessionStore): SessionRepository =
        SessionRepository(api, jar, store)

    @Provides
    fun provideSignInUseCase(bridge: WalletBridge, api: NextAuthApi, binder: FingerprintBinder, session: SessionRepository): SignInUseCase =
        SignInUseCase(bridge, api, binder, session)

    /** Sign-out also drops every cached dashboard row (the local `devices` table is untouched). */
    @Provides
    fun provideSignOutUseCase(
        api: NextAuthApi,
        bridge: WalletBridge,
        session: SessionRepository,
        miners: MinerRepository,
        rewards: RewardsRepository,
    ): SignOutUseCase =
        SignOutUseCase(api, bridge, session, clearCaches = { miners.clearCache(); rewards.clearCache() })
}
