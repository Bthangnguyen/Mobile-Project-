package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.data.apikeys.AesGcmApiKeyCipher
import com.lttbdd.socialagent.data.apikeys.AndroidKeystoreKeyProvider
import com.lttbdd.socialagent.data.apikeys.ApiKeyStore
import com.lttbdd.socialagent.data.apikeys.ApiKeyVerifier
import com.lttbdd.socialagent.data.apikeys.EncryptedApiKeyStore
import com.lttbdd.socialagent.data.apikeys.FileCiphertextStorage
import com.lttbdd.socialagent.data.apikeys.OkHttpApiKeyVerifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiKeysModule {
    @Provides
    @Singleton
    fun store(@ApplicationContext context: Context): ApiKeyStore = EncryptedApiKeyStore(
        FileCiphertextStorage(File(context.filesDir, FileCiphertextStorage.DIRECTORY)),
        AesGcmApiKeyCipher(AndroidKeystoreKeyProvider()),
    )

    @Provides
    @Singleton
    fun verifier(): ApiKeyVerifier = OkHttpApiKeyVerifier(OkHttpApiKeyVerifier.newHttpClient())
}
