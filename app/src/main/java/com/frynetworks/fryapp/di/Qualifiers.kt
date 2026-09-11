package com.frynetworks.fryapp.di

import javax.inject.Qualifier

/** OkHttp/Retrofit for hardwareapi.frynetworks.com (optional developer bearer token). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HardwareApiClient

/** OkHttp for the dashboard's NextAuth endpoints: cookie jar + pinned headers, no signing/retry. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

/** OkHttp/Retrofit for the dashboard's data endpoints: jar + pinned headers + signing + fingerprint retry. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DashboardClient
