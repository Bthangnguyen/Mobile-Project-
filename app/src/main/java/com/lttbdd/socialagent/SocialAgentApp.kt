package com.lttbdd.socialagent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lttbdd.socialagent.data.publishing.PublishQueueRepository
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * Cấu hình WorkManager dùng [HiltWorkerFactory] để worker (hàng đợi đăng bài F5) được inject repository.
 * Bộ khởi tạo mặc định của WorkManager đã bị gỡ trong AndroidManifest.xml.
 */
@HiltAndroidApp
class SocialAgentApp : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var publishQueue: PublishQueueRepository

    @Inject
    lateinit var channelRepository: Provider<ChannelRepository>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            // Khôi phục các lượt ngắt kết nối dang dở ngay cả khi không mở màn hình Kênh của tôi.
            try {
                channelRepository.get().getConnectedChannels().first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lần đọc tiếp theo sẽ thử lại nếu kho lưu trữ chưa sẵn sàng.
            }
            // Dọn kênh dang dở trước khi hẹn lại worker cho các bài còn trong hàng đợi.
            publishQueue.resumePendingWork()
        }
    }
}
