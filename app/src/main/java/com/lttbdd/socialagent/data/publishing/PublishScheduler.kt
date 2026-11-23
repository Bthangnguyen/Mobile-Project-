package com.lttbdd.socialagent.data.publishing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Hẹn chạy hàng đợi đăng bài. Tách thành interface để unit test repository không cần WorkManager. */
interface PublishScheduler {
    fun schedule()

    /**
     * Hẹn một lượt chạy sau [delayMillis] để kiểm tra bài còn kẹt ở PUBLISHING (worker bị dừng giữa chừng).
     * Chạy ở chuỗi riêng nên không làm bài mới thêm vào hàng đợi phải chờ.
     */
    fun scheduleStaleCheck(delayMillis: Long)
}

/**
 * Hẹn [PublishQueueWorker] chạy khi có mạng. WorkManager lưu yêu cầu xuống đĩa nên vẫn chạy sau khi app bị
 * đóng hoặc máy khởi động lại. `APPEND_OR_REPLACE` bảo đảm bài thêm vào lúc worker đang chạy vẫn được xử lý
 * ở lượt kế tiếp, đồng thời chỉ có một chuỗi worker cho hàng đợi.
 */
class WorkManagerPublishScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PublishScheduler {

    override fun schedule() = enqueue(UNIQUE_WORK_NAME, delayMillis = 0)

    override fun scheduleStaleCheck(delayMillis: Long) = enqueue(STALE_CHECK_WORK_NAME, delayMillis)

    private fun enqueue(name: String, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<PublishQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "publish-queue"
        const val STALE_CHECK_WORK_NAME = "publish-queue-stale-check"
        private const val BACKOFF_SECONDS = 30L
    }
}
