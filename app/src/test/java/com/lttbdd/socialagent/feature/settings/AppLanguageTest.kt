package com.lttbdd.socialagent.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `empty tags mean system default`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
    }

    @Test
    fun `primary tag is matched ignoring region and case`() {
        assertEquals(AppLanguage.VIETNAMESE, AppLanguage.fromTags("vi"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("EN,vi"))
    }

    @Test
    fun `unsupported language falls back to system default`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("fr-FR"))
    }
}
