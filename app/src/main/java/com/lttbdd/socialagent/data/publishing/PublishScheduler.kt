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
    /** Hẹn một lượt chạy cho bài vừa thêm vào hàng đợi; nối sau lượt đang chạy nếu có. */
    fun schedule()

    /**
     * Hẹn một lượt chạy chỉ khi chưa có lượt nào đang chờ hoặc đang chạy (dùng khi mở app). Mở app nhiều lần lúc
     * offline không nối thêm worker rỗng vào chuỗi.
     */
    fun scheduleIfIdle() = schedule()

    /**
     * Hẹn một lượt chạy sau [delayMillis] để kiểm tra bài còn kẹt ở PUBLISHING (worker bị dừng giữa chừng).
     * Chạy ở chuỗi riêng nên không làm bài mới thêm vào hàng đợi phải chờ.
     */
    fun scheduleStaleCheck(delayMillis: Long)

    /**
     * Hẹn một lượt chạy lúc [retryAtMillis] (còn [delayMillis] nữa) cho bài kênh yêu cầu chờ (`Retry-After`, #39).
     * Gọi lại cùng mốc nhiều lần chỉ tạo một lượt; các mốc khác nhau chạy độc lập, mốc ngắn không phải chờ mốc dài.
     * Mặc định dùng chung chuỗi trễ với [scheduleStaleCheck] (fake trong test không phải sửa).
     */
    fun scheduleRetryAt(retryAtMillis: Long, delayMillis: Long) = scheduleStaleCheck(delayMillis)
}

/**
 * Hẹn [PublishQueueWorker] chạy khi có mạng. WorkManager lưu yêu cầu xuống đĩa nên vẫn chạy sau khi app bị
 * đóng hoặc máy khởi động lại.
 *
 * - [schedule] dùng `APPEND_OR_REPLACE`: bài thêm vào lúc worker đang chạy vẫn được xử lý ở lượt kế tiếp.
 * - [scheduleIfIdle] dùng `KEEP`: chỉ hẹn khi chưa có lượt nào chờ/chạy.
 * - [scheduleStaleCheck] giữ `APPEND_OR_REPLACE`: chính lượt kiểm tra đang chạy có thể cần hẹn lượt kế tiếp
 *   (còn bài khác đang đăng dở); `KEEP` sẽ bỏ qua yêu cầu đó vì lượt hiện tại vẫn đang RUNNING. Chuỗi này chỉ
 *   dài thêm khi `countInFlight() > 0`, nên không phình ra lúc offline.
 * - [scheduleRetryAt] tạo **một work riêng cho mỗi mốc** (`publish-queue-retry-at-<mốc>`) với `KEEP`: hẹn lại cùng
 *   mốc ở lượt sau không nhân bản, còn mốc 60 giây không bị nối sau mốc 24 giờ như khi dùng chung một chuỗi
 *   `APPEND_OR_REPLACE` (review #107). Work xong thì WorkManager tự dọn, nên số tên không tăng mãi.
 */
class WorkManagerPublishScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PublishScheduler {

    override fun schedule() = enqueue(UNIQUE_WORK_NAME, delayMillis = 0, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun scheduleIfIdle() = enqueue(UNIQUE_WORK_NAME, delayMillis = 0, ExistingWorkPolicy.KEEP)

    override fun scheduleStaleCheck(delayMillis: Long) =
        enqueue(STALE_CHECK_WORK_NAME, delayMillis, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun scheduleRetryAt(retryAtMillis: Long, delayMillis: Long) =
        enqueue(retryWorkName(retryAtMillis), delayMillis.coerceAtLeast(0), ExistingWorkPolicy.KEEP)

    private fun enqueue(name: String, delayMillis: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PublishQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "publish-queue"
        const val STALE_CHECK_WORK_NAME = "publish-queue-stale-check"
        private const val RETRY_AT_WORK_PREFIX = "publish-queue-retry-at-"

        /** Tên work của một mốc thử lại; mỗi mốc một tên để các mốc không chặn nhau. */
        fun retryWorkName(retryAtMillis: Long) = RETRY_AT_WORK_PREFIX + retryAtMillis
        private const val BACKOFF_SECONDS = 30L
    }
}
