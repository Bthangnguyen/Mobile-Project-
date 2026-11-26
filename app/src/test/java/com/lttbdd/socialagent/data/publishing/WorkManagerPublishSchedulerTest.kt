package com.lttbdd.socialagent.data.publishing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test cách [WorkManagerPublishScheduler] hẹn worker. Dùng WorkManager test (không ràng buộc mạng được thoả
 * nên worker không chạy), chỉ kiểm tra yêu cầu được xếp vào chuỗi như thế nào.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkManagerPublishSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: WorkManagerPublishScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        scheduler = WorkManagerPublishScheduler(context)
    }

    private fun pending(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .filter { !it.state.isFinished }

    @Test
    fun `schedule requires network and appends while work is pending`() {
        scheduler.schedule()
        scheduler.schedule()

        val works = pending(WorkManagerPublishScheduler.UNIQUE_WORK_NAME)
        assertEquals("Bài thêm vào khi đang có lượt chờ vẫn có lượt riêng", 2, works.size)
        assertTrue(works.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
    }

    @Test
    fun `scheduleIfIdle keeps existing pending work`() {
        scheduler.scheduleIfIdle()
        scheduler.scheduleIfIdle()
        scheduler.scheduleIfIdle()

        assertEquals(1, pending(WorkManagerPublishScheduler.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun `scheduleIfIdle enqueues when nothing is pending`() {
        scheduler.scheduleIfIdle()

        assertEquals(1, pending(WorkManagerPublishScheduler.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun `retry after uses its own chain with the requested delay`() {
        scheduler.schedule()
        scheduler.scheduleRetryAfter(60_000L)

        val retry = pending(WorkManagerPublishScheduler.RETRY_AFTER_WORK_NAME)
        assertEquals(1, retry.size)
        assertEquals("Không chen vào chuỗi chính", 1, pending(WorkManagerPublishScheduler.UNIQUE_WORK_NAME).size)
        assertTrue(retry.single().initialDelayMillis >= 60_000L)
        assertTrue(retry.single().constraints.requiredNetworkType == NetworkType.CONNECTED)
    }

    @Test
    fun `stale check uses its own chain with the requested delay`() {
        scheduler.schedule()
        scheduler.scheduleStaleCheck(16 * 60 * 1000L)

        val stale = pending(WorkManagerPublishScheduler.STALE_CHECK_WORK_NAME)
        assertEquals(1, stale.size)
        assertEquals(1, pending(WorkManagerPublishScheduler.UNIQUE_WORK_NAME).size)
        assertTrue(stale.single().initialDelayMillis >= 16 * 60 * 1000L)
    }
}
