package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackFormTest {
    @Test
    fun encodesFieldsAsFormBody() {
        val body = FeedbackForm.encode(listOf("entry.1" to "오류 신고", "entry.2" to "a b&c=d", "entry.3" to ""))
        assertEquals("entry.1=%EC%98%A4%EB%A5%98+%EC%8B%A0%EA%B3%A0&entry.2=a+b%26c%3Dd&entry.3=", body)
    }

    @Test
    fun submitUrlUsesFormId() {
        assertEquals("https://docs.google.com/forms/d/e/ABC/formResponse", FeedbackForm.submitUrl("ABC"))
    }

    @Test
    fun infoLineHasVersionAndDeviceAndOptionalCrash() {
        val plain = FeedbackForm.info("1.0.3", 9, "Samsung SM-X", "14", null)
        assertEquals("DSnote 1.0.3 (9) / Samsung SM-X / Android 14", plain)
        val withCrash = FeedbackForm.info("1.0.3", 9, "Samsung SM-X", "14", "  trace  ")
        assertTrue(withCrash.endsWith("[최근 오류 기록]\ntrace"))
    }

    @Test
    fun clipKeepsShortAndCutsLong() {
        assertEquals("abc", FeedbackForm.clip("abc", 5))
        assertEquals("abcde…", FeedbackForm.clip("abcdefg", 5))
    }
}
