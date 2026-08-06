package com.amaya.intelligence.data.remote.api

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseBodyLimitsTest {
    @Test
    fun `bounded body accepts payload at limit`() {
        assertEquals("hello", "hello".toResponseBody().readUtf8Limited(5))
    }

    @Test
    fun `bounded body rejects oversized payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            "oversized".toResponseBody().readUtf8Limited(4)
        }
    }
}
