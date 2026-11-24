package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionRepositoryTest {
    private val store = FakeTaskModelStore()
    private val keys = FakeKeyAvailability(LlmProvider.GEMINI, LlmProvider.OPENAI)
    private val repository = ModelSelectionRepository(store, TaskModelResolver(store, keys), keys)

    @Test
    fun `snapshot reports key state per provider and a resolution for every task`() = runTest {
        keys.configured -= LlmProvider.OPENAI
        val snapshot = repository.snapshot()
        assertEquals(mapOf(LlmProvider.GEMINI to true, LlmProvider.OPENAI to false), snapshot.keyConfigured)
        assertEquals(LlmTask.entries.toSet(), snapshot.tasks.keys)
        assertEquals(TaskModelResolution.NotSelected, snapshot.tasks[LlmTask.GENERATE_IMAGE])
    }

    @Test
    fun `saves a valid selection trimmed and independently per task`() = runTest {
        assertEquals(SaveModelResult.Saved, repository.save(LlmTask.WRITE_POST, LlmProvider.OPENAI, "  gpt-6-luna \n"))
        assertEquals(TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna"), store.values[LlmTask.WRITE_POST])
        assertTrue(LlmTask.CLASSIFY_COMMENT !in store.values)
        val snapshot = repository.snapshot()
        assertEquals(
            TaskModelResolution.Ready(LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"), isDefault = false),
            snapshot.tasks[LlmTask.WRITE_POST],
        )
        assertEquals(
            TaskModelResolution.Ready(LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"), isDefault = true),
            snapshot.tasks[LlmTask.CLASSIFY_COMMENT],
        )
    }

    @Test
    fun `refuses provider without key and writes nothing`() = runTest {
        keys.configured -= LlmProvider.OPENAI
        assertEquals(SaveModelResult.MissingApiKey, repository.save(LlmTask.WRITE_POST, LlmProvider.OPENAI, "gpt-6-luna"))
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `refuses invalid models and writes nothing`() = runTest {
        assertEquals(
            SaveModelResult.Invalid(ModelValidation.EMPTY),
            repository.save(LlmTask.WRITE_POST, LlmProvider.GEMINI, "   "),
        )
        assertEquals(
            SaveModelResult.Invalid(ModelValidation.INCOMPATIBLE_WITH_TASK),
            repository.save(LlmTask.GENERATE_IMAGE, LlmProvider.GEMINI, "gemini-2.5-flash"),
        )
        assertEquals(
            SaveModelResult.Invalid(ModelValidation.INVALID_FORMAT),
            repository.save(LlmTask.WRITE_POST, LlmProvider.GEMINI, "bad id"),
        )
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `reset removes only that task and returns it to the default`() = runTest {
        repository.save(LlmTask.WRITE_POST, LlmProvider.OPENAI, "gpt-6-luna")
        repository.save(LlmTask.CLASSIFY_COMMENT, LlmProvider.OPENAI, "gpt-6-luna")
        repository.reset(LlmTask.WRITE_POST)
        assertTrue(LlmTask.WRITE_POST !in store.values)
        assertTrue(LlmTask.CLASSIFY_COMMENT in store.values)
        val resolution = repository.snapshot().tasks.getValue(LlmTask.WRITE_POST) as TaskModelResolution.Ready
        assertEquals("gemini-2.5-flash", resolution.configuration.model)
        assertTrue(resolution.isDefault)
    }
}
