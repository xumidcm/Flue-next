package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgentToolSchedulerTest {
    @Test
    fun `read batch suspends caller so UI dispatcher remains responsive`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val scheduler = WatchAgentToolScheduler(WatchAgentToolExecutor { call ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                WatchAgentToolResult(call.id, call.name, 1L)
            })
            val running = launch(ui) { scheduler.execute(listOf(WatchAgentToolCall("read", "inspect_face"))) }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val uiEvent = async(ui) { true }
                assertTrue("UI event must run before the read is released", withTimeoutOrNull(500) { uiEvent.await() } == true)
            } finally {
                release.countDown()
                running.join()
            }
        }
    }

    @Test
    fun `cancelling batch interrupts reads and never reaches following write`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val wrote = AtomicBoolean(false)
            val scheduler = WatchAgentToolScheduler(WatchAgentToolExecutor { call ->
                if (call.name == "inspect_face") {
                    started.countDown()
                    try {
                        release.await(5, TimeUnit.SECONDS)
                    } catch (failure: InterruptedException) {
                        interrupted.countDown()
                        throw failure
                    }
                } else wrote.set(true)
                WatchAgentToolResult(call.id, call.name, 1L)
            })
            val running = launch(ui) {
                scheduler.execute(listOf(WatchAgentToolCall("read", "inspect_face"), WatchAgentToolCall("write", "write_document")))
            }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                running.cancel()
                assertTrue("Cancellation must interrupt the running read", interrupted.await(1, TimeUnit.SECONDS))
                running.join()
                assertTrue("Cancelled batch must not execute its write barrier", !wrote.get())
            } finally {
                release.countDown()
                running.join()
            }
        }
    }

    @Test
    fun `preview and writes resume on caller dispatcher after background reads`() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-editor-ui") }.asCoroutineDispatcher().use { ui ->
            val threads = java.util.concurrent.ConcurrentHashMap<String, Thread>()
            val uiThread = withContext(ui) { Thread.currentThread() }
            val scheduler = WatchAgentToolScheduler(WatchAgentToolExecutor { call ->
                threads[call.id] = Thread.currentThread()
                WatchAgentToolResult(call.id, call.name, 1L)
            })
            val calls = listOf(WatchAgentToolCall("read", "inspect_face"), WatchAgentToolCall("preview", "preview_face"), WatchAgentToolCall("write", "write_document"))
            val results = withContext(ui) { scheduler.execute(calls) }
            assertEquals(calls.map { it.id }, results.map { it.callId })
            assertTrue(threads["read"] !== uiThread)
            assertEquals(uiThread, threads["preview"])
            assertEquals(uiThread, threads["write"])
        }
    }
}
