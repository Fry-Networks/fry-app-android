package com.frynetworks.fryapp.di

import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.bridge.WalletBridgeWebView
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WalletModule {
    @Binds
    abstract fun bindWalletBridge(impl: WalletBridgeWebView): WalletBridge
}
