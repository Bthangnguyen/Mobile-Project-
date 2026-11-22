package com.lttbdd.socialagent.data.apikeys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lttbdd.socialagent.core.model.LlmProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreKeyProvider(
    private val aliasPrefix: String = "socialagent.llm-api-key.v1.",
) : EncryptionKeyProvider {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun existing(provider: LlmProvider): SecretKey? {
        requireKeyProvider(provider)
        val store = keyStore()
        val alias = aliasPrefix + provider.name
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey
            ?: throw ApiKeyStorageException(KeyStorageFailure.UNREADABLE)
    }

    override fun create(provider: LlmProvider): SecretKey {
        requireKeyProvider(provider)
        val alias = aliasPrefix + provider.name
        check(!keyStore().containsAlias(alias))
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    override fun delete(provider: LlmProvider) {
        requireKeyProvider(provider)
        keyStore().deleteEntry(aliasPrefix + provider.name)
    }
}
