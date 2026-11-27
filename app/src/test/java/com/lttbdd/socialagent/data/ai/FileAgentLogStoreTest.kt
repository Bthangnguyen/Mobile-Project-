package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileAgentLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `recordRun stores newest first, survives reopen, and caps at maxRuns`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("logs")
        val store = FileAgentLogStore(baseDir = dir, ioDispatcher = dispatcher, maxRuns = 2)

        store.recordRun(sampleRun("r1", 1_000L))
        store.recordRun(sampleRun("r2", 2_000L))
        store.recordRun(sampleRun("r3", 3_000L))

        val reopened = FileAgentLogStore(baseDir = dir, ioDispatcher = dispatcher, maxRuns = 2)
        val loaded = reopened.getLogs()
        assertEquals(listOf("r3", "r2"), loaded.map { it.id })
    }

    @Test
    fun `sanitizer redacts API keys and bearer tokens before persisting`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileAgentLogStore(baseDir = tempFolder.newFolder("redact"), ioDispatcher = dispatcher)

        val riskyRun = sampleRun(
            id = "r-secret",
            startedAtMillis = 1_000L,
            prompt = "Use Bearer eyJhbGciOiJIUzI1NiJ9 and sk-proj-1234567890abcdef123456",
            output = "Key AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz1234",
            costUsd = Double.NaN,
        )
        store.recordRun(riskyRun)

        val saved = store.getLogs().single()
        val step = saved.steps.single()
        assertFalse(step.prompt.contains("eyJhbGci"))
        assertFalse(step.prompt.contains("sk-proj-"))
        assertFalse(step.output.contains("AIzaSy"))
        assertTrue(step.prompt.contains("[REDACTED]"))
        assertTrue(step.output.contains("[REDACTED]"))
        assertNull(step.costUsd)
    }

    @Test
    fun `atomic write leaves no temp files and recovers from backup if interrupted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("atomic")
        val store = FileAgentLogStore(baseDir = dir, ioDispatcher = dispatcher)

        store.recordRun(sampleRun("r1", 1_000L))
        store.recordRun(sampleRun("r2", 2_000L))

        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        val primary = files.single()
        val backup = File(dir, "${primary.name}.bak")
        assertTrue(primary.renameTo(backup))

        val recovered = store.getLogs()
        assertEquals(listOf("r2", "r1"), recovered.map { it.id })
    }

    @Test
    fun `observeLogs emits reactively on record and clearAll`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileAgentLogStore(baseDir = tempFolder.newFolder("observe"), ioDispatcher = dispatcher)
        val emissions = mutableListOf<List<AgentRunLog>>()

        val job = launch {
            store.observeLogs().take(3).toList(emissions)
        }
        advanceUntilIdle()

        store.recordRun(sampleRun("r1", 1_000L))
        advanceUntilIdle()

        store.clearAll()
        advanceUntilIdle()
        job.join()

        assertEquals(3, emissions.size)
        assertTrue(emissions[0].isEmpty())
        assertEquals(listOf("r1"), emissions[1].map { it.id })
        assertTrue(emissions[2].isEmpty())
    }

    private fun sampleRun(
        id: String,
        startedAtMillis: Long,
        prompt: String = "Brief prompt",
        output: String = "Step output",
        costUsd: Double? = 0.0012,
    ) = AgentRunLog(
        id = id,
        topicId = "topic-$id",
        topic = "Topic $id",
        channel = ChannelType.FACEBOOK,
        sharedBriefSummary = "Topic: $id",
        startedAtMillis = startedAtMillis,
        finishedAtMillis = startedAtMillis + 500L,
        steps = listOf(
            AgentStepLogEntry(
                step = AgentStep.DRAFT,
                status = AgentStepStatus.SUCCEEDED,
                prompt = prompt,
                output = output,
                inputTokens = 120,
                outputTokens = 80,
                costUsd = costUsd,
                startedAtMillis = startedAtMillis,
                finishedAtMillis = startedAtMillis + 400L,
            ),
        ),
    )
}
