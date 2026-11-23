package com.lttbdd.socialagent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Cấu hình WorkManager dùng [HiltWorkerFactory] để worker (hàng đợi đăng bài F5) được inject repository.
 * Bộ khởi tạo mặc định của WorkManager đã bị gỡ trong AndroidManifest.xml.
 */
@HiltAndroidApp
class SocialAgentApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
