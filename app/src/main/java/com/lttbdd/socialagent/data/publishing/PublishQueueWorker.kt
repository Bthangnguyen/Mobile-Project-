package com.lttbdd.socialagent.data.publishing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Worker chạy hàng đợi đăng bài khi có mạng. Mọi logic nằm ở [PublishQueueRepository];
 * worker chỉ đổi kết quả sang `Result` để WorkManager thử lại theo backoff khi còn bài chờ.
 */
@HiltWorker
class PublishQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: PublishQueueRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (repository.processQueue().shouldRetry) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Lỗi DB tạm thời: thử lại; bài đang PUBLISHING sẽ được xử lý theo giao thức chống trùng.
        Result.retry()
    }
}
