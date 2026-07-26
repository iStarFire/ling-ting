package com.tingyiting.di

import com.tingyiting.data.store.EncryptedWebDavConfigStore
import com.tingyiting.data.store.WebDavConfigStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {
    @Binds
    @Singleton
    abstract fun bindWebDavConfigStore(
        impl: EncryptedWebDavConfigStore
    ): WebDavConfigStore
}
