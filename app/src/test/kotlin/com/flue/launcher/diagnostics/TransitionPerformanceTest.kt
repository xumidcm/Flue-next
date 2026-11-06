package com.flue.launcher.diagnostics

import com.flue.launcher.watchface.agent.provider.AgentJsonParser
import com.flue.launcher.watchface.agent.provider.AgentJsonValue
import org.junit.Assert.*
import org.junit.Test

class TransitionPerformanceTest {
    @Test fun delayedFramesAndStageWorkKeepTheirStartingScene() {
        val collector = TransitionPerformanceCollector(1L)
        collector.markScene(TransitionPerformance.Scene.OPEN, 10L)
        collector.markScene(TransitionPerformance.Scene.REVERSE, 100L)
        collector.endScene(TransitionPerformance.Scene.OPEN, 110L)
        collector.record(TransitionPerformance.Stage.LIST_CAPTURE, 20L, 3_000_000L)
        collector.recordFrame(20L, 20_000_000L, 16_000_000L, 2)
        collector.recordFrame(120L, 8_000_000L, 16_000_000L, 0)
        collector.recordFrame(0L, 1_000_000L, 16_000_000L, 0)
        val scenes = (AgentJsonParser.parse(collector.toJson()) as AgentJsonValue.Obj).obj("scenes")!!
        val open = scenes.obj("open")!!
        assertEquals(2L, open.long("dropped_callbacks"))
        assertEquals(100L, open.long("over_budget_percent"))
        assertEquals(1L, open.obj("stages")!!.obj("list_capture")!!.long("count"))
        assertEquals(1L, scenes.obj("reverse")!!.obj("stages")!!.obj("frame_total")!!.long("count"))
        assertEquals(0L, scenes.obj("idle")!!.obj("stages")!!.obj("frame_total")!!.long("count"))
    }

    @Test fun p95UsesAllSamplesAndResetsBetweenIntervals() {
        val accumulator = FrameIntervalAccumulator()
        repeat(95) { accumulator.record(10_000_000L, 16_000_000L, 0) }
        repeat(5) { accumulator.record(40_000_000L, 16_000_000L, 0) }
        val sample = accumulator.take(1000L)
        assertEquals(10.5, sample.p95Ms, .001)
        assertEquals(11.5, sample.averageMs, .001)
        assertEquals(5.0, sample.jankPercent, .001)
        assertEquals(0.0, accumulator.take(1000L).p95Ms, 0.0)
        accumulator.record(1_000_000_000L, 16_000_000L, 0)
        assertEquals(1000.0, accumulator.take(1000L).p95Ms, 0.0)
    }

    @Test fun disabledInstrumentationDoesNotStartTimingOrAllocateACollector() {
        TransitionPerformance.collector = null
        assertEquals(0L, TransitionPerformance.start())
        TransitionPerformance.finish(TransitionPerformance.Stage.LIST_BLUR, 0L)
        TransitionPerformance.markScene(TransitionPerformance.Scene.OPEN)
        assertNull(TransitionPerformance.collector)
    }
}
