package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LlmPricingTest {

    @Test
    fun `cost follows research example for 1000 input and 500 output tokens`() {
        // docs/research-llm.md mục 7.1: Flash-Lite 0,000300 USD; GPT-6 Luna 0,000350 USD.
        assertEquals(300L, LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash-lite", 1_000, 500))
        assertEquals(350L, LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "gpt-6-luna", 1_000, 500))
    }

    @Test
    fun `default writing model has a price`() {
        // 1.000 × 0,30 + 500 × 2,50 (USD/1M token) = 0,00155 USD.
        assertEquals(1_550L, LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash", 1_000, 500))
    }

    @Test
    fun `Gemini 2_5 Flash replacement has the same text price`() {
        // Từ 01/12/2026 request gemini-2.5-flash được chuyển sang gemini-3.5-flash-lite, tính theo giá model đó.
        assertEquals(
            LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash", 1_000, 500),
            LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-3.5-flash-lite", 1_000, 500),
        )
    }

    @Test
    fun `cost is rounded half up once per call`() {
        // 1 input token Flash-Lite = 0,1 micro-USD → 0; 5 token = 0,5 → 1.
        assertEquals(0L, LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash-lite", 1, 0))
        assertEquals(1L, LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash-lite", 5, 0))
        assertEquals(0L, LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash-lite", 0, 0))
    }

    @Test
    fun `OpenAI long context price applies above 272k input tokens`() {
        assertEquals(27_200L, LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "gpt-6-luna", 272_000, 0))
        // 272.001 × 0,20 USD/1M = 54.400,2 micro-USD.
        assertEquals(54_400L, LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "gpt-6-luna", 272_001, 0))
        // 272.001 × 2,00 + 1.000 × 7,50 (USD/1M token) = 544.002 + 7.500 micro-USD.
        assertEquals(551_502L, LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "gpt-6.1-terra", 272_001, 1_000))
    }

    @Test
    fun `largest token counts do not overflow`() {
        // 2.147.483.647 × (2,00 + 7,50) USD/1M = 20.401.094.646,5 micro-USD, làm tròn lên.
        val cost = LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "gpt-6.1-terra", Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(20_401_094_647L, cost)
    }

    @Test
    fun `unknown model or provider has no price instead of zero`() {
        assertNull(LlmPricing.estimateCostMicros(LlmProvider.OPENAI, "my-fine-tuned-model", 1_000, 500))
        assertNull(LlmPricing.estimateCostMicros(LlmProvider.ANTHROPIC, "gpt-6-luna", 1_000, 500))
        assertNull(LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "GEMINI-2.5-FLASH", 1_000, 500))
    }

    @Test
    fun `negative token counts have no price`() {
        assertNull(LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash", -1, 0))
        assertNull(LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash", 0, -1))
    }

    @Test
    fun `Gemini models prefix is the same model`() {
        assertEquals("gemini-2.5-flash", LlmPricing.canonicalModelId(LlmProvider.GEMINI, "models/gemini-2.5-flash"))
        assertEquals(
            LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "gemini-2.5-flash", 10, 10),
            LlmPricing.estimateCostMicros(LlmProvider.GEMINI, "models/gemini-2.5-flash", 10, 10),
        )
        // Tiền tố chỉ có nghĩa với Gemini.
        assertEquals("models/gpt-6-luna", LlmPricing.canonicalModelId(LlmProvider.OPENAI, "models/gpt-6-luna"))
    }

    @Test
    fun `every suggested text model in the catalog has a price`() {
        // Router chưa hỗ trợ sinh ảnh nên chỉ model text cần giá.
        for (provider in LlmModelCatalog.supportedProviders) {
            for (task in listOf(LlmTask.WRITE_POST, LlmTask.CLASSIFY_COMMENT)) {
                for (model in LlmModelCatalog.suggestions(provider, task)) {
                    assertNotNull("$provider/$model thiếu giá", LlmPricing.priceOf(provider, model))
                }
            }
        }
    }

    @Test
    fun `pricing version is an ISO date shown in the UI`() {
        assertEquals(LlmPricing.VERSION, LocalDate.parse(LlmPricing.VERSION).toString())
    }
}
