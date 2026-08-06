package com.amaya.intelligence.data.remote.api

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexAuthManagerTest {
    private val redirectUri = "http://localhost:1455/auth/callback"
    private val state = "expected-state"

    @Test
    fun parsesCallbackFromActiveLogin() {
        val callback = CodexAuthManager.parseManualCallbackUrl(
            "http://localhost:1455/auth/callback?code=auth%2Bcode&state=expected-state",
            redirectUri,
            state
        )

        assertEquals("auth+code", callback?.code)
        assertNull(callback?.error)
    }

    @Test
    fun rejectsCallbackFromAnotherLogin() {
        assertNull(
            CodexAuthManager.parseManualCallbackUrl(
                "http://localhost:1455/auth/callback?code=code&state=other-state",
                redirectUri,
                state
            )
        )
    }

    @Test
    fun rejectsCallbackForAnotherEndpoint() {
        assertNull(
            CodexAuthManager.parseManualCallbackUrl(
                "http://localhost:1455/other?code=code&state=expected-state",
                redirectUri,
                state
            )
        )
    }

    @Test
    fun fallsBackWhenSystemDnsCannotResolve() {
        val expected = InetAddress.getByAddress(byteArrayOf(104, 18, 41, -15))
        val dns = FallbackDns(
            primary = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException(hostname)
            },
            fallback = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(expected)
            }
        )

        assertEquals(listOf(expected), dns.lookup("auth.openai.com"))
    }
}
