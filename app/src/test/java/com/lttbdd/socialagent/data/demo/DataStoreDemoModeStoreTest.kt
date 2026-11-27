package com.lttbdd.socialagent.data.demo

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mỗi test một tệp DataStore riêng; chạy bằng Robolectric như các test DataStore khác của nhóm. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataStoreDemoModeStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newStore() = DataStoreDemoModeStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = demoModeCorruptionHandler(),
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("demo_mode_${UUID.randomUUID()}") },
        ),
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `demo mode is off by default`() = runBlocking {
        assertFalse(newStore().isEnabled())
    }

    @Test
    fun `saves and clears demo mode`() = runBlocking {
        val store = newStore()

        store.setEnabled(true)
        assertTrue(store.isEnabled())

        store.setEnabled(false)
        assertFalse(store.isEnabled())
    }
}
