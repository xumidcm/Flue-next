package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceLuaRuntimeTest {
    @Test
    fun flueWatchTapStartsItsFrameCallbackInTheSameFaceEnvironment() {
        WatchFaceLuaRuntime.create(FLUE_WATCH_SCRIPT).use { runtime ->
            assertEquals(1.0, runtime.evaluateNumber("var_frame"), 0.0)
            assertFalse(runtime.needsFrame())

            assertTrue(runtime.runSnippet("restart_play()").isSuccess)
            assertTrue(runtime.needsFrame())
            assertEquals(1.0, runtime.evaluateNumber("var_frame"), 0.0)

            assertTrue(runtime.stepMilliseconds(1.000).isSuccess)
            assertTrue(runtime.stepMilliseconds(1.075).isSuccess)
            assertEquals(2.0, runtime.evaluateNumber("var_frame"), 0.0)
        }
    }

    @Test
    fun nexusFunctionsDriveLayerPropertiesAndTapCreatesFourPulses() {
        WatchFaceLuaRuntime.create(NEXUS_SCRIPT).use { runtime ->
            assertTrue(runtime.needsFrame())
            assertTrue(runtime.stepMilliseconds(2.000).isSuccess)

            val activeBeforeTap = runtime.evaluateNumber("active_count()")
            assertEquals(12.0, activeBeforeTap, 0.0)
            assertTrue(runtime.evaluateNumber("var_T_x(0, var_TP)").isFinite())

            assertTrue(runtime.runSnippet("nexus_tap(55)").isSuccess)
            assertEquals(4.0, runtime.evaluateNumber("pulse_count()"), 0.0)
        }
    }

    @Test
    fun sandboxMatchesCloxPermissionsAndStopsRunawayCode() {
        WatchFaceLuaRuntime.create("var_ok = 1").use { runtime ->
            for (name in listOf(
                "io", "package", "require", "debug", "luajava", "java",
                "load", "loadstring", "loadfile", "dofile", "collectgarbage",
                "getfenv", "setfenv", "setmetatable", "getmetatable",
                "rawget", "rawset", "rawequal", "coroutine",
            )) {
                assertNull("$name must not be visible to a face", runtime.globalType(name))
            }
            assertEquals("table", runtime.globalType("math"))
            assertEquals("table", runtime.globalType("string"))
            assertEquals("table", runtime.globalType("table"))
            assertEquals("table", runtime.globalType("os"))
            assertEquals("function", runtime.evaluateText("type(os.time)"))
            assertNull(runtime.evaluateText("os.execute"))

            val runaway = runtime.runSnippet("while true do end")
            assertTrue(runaway.isFailure)
            assertTrue(runaway.exceptionOrNull()?.message.orEmpty().contains("instruction limit"))

            val swallowed = runtime.runSnippet(
                "pcall(function() while true do end end); var_after_runaway = 1",
            )
            assertTrue(swallowed.isFailure)
            assertNull(runtime.evaluateText("var_after_runaway"))
        }
    }

    @Test
    fun faceEnvironmentsAreIsolatedAndTagGlobalsRemainReadOnlyInputs() {
        WatchFaceLuaRuntime.create("var_value = 1").use { first ->
            WatchFaceLuaRuntime.create("var_value = 2").use { second ->
                assertTrue(first.runSnippet("var_value = var_value + 4").isSuccess)
                assertEquals(5.0, first.evaluateNumber("var_value"), 0.0)
                assertEquals(2.0, second.evaluateNumber("var_value"), 0.0)

                assertEquals(
                    85.0,
                    first.evaluateNumber("bl + 5") { tag ->
                        if (tag == "bl") WatchTagResolution.Resolved("80")
                        else WatchTagResolution.Unsupported(tag)
                    },
                    0.0,
                )
            }
        }
    }

    @Test
    fun libraryTablesArePrivateToEachFaceSandbox() {
        WatchFaceLuaRuntime.create("var_value = 1").use { first ->
            WatchFaceLuaRuntime.create("var_value = 2").use { second ->
                assertTrue(first.runSnippet("math.floor = nil").isSuccess)
                assertNull(first.evaluateText("math.floor"))
                assertEquals("function", second.evaluateText("type(math.floor)"))
            }
        }
    }

    @Test
    fun expandingStringLibraryCallsAreBoundedBeforeAllocation() {
        val chunk = "a".repeat(200_000)
        WatchFaceLuaRuntime.create("var_value = 1; var_chunk = '$chunk'").use { runtime ->
            val oversized = runtime.runSnippet("var_large = string.rep('abcd', 2000000)")
            assertTrue(oversized.isFailure)
            assertTrue(oversized.exceptionOrNull()?.message.orEmpty().contains("string result limit"))
            assertNull(runtime.evaluateText("var_large"))

            for (source in listOf(
                "var_large = string.format('%s%s%s%s%s%s', var_chunk, var_chunk, var_chunk, var_chunk, var_chunk, var_chunk)",
                "var_large = table.concat({var_chunk, var_chunk, var_chunk, var_chunk, var_chunk, var_chunk})",
                "var_large = string.gsub('......', '.', var_chunk)",
            )) {
                val result = runtime.runSnippet(source)
                assertTrue(source, result.isFailure)
                assertTrue(
                    source,
                    result.exceptionOrNull()?.message.orEmpty().contains("string result limit"),
                )
                assertNull(source, runtime.evaluateText("var_large"))
            }

            assertTrue(runtime.runSnippet("var_half = string.rep('x', 600000)").isSuccess)
            val oversizedConcat = runtime.runSnippet("var_large = var_half .. var_half")
            assertTrue(oversizedConcat.isFailure)
            assertTrue(
                oversizedConcat.exceptionOrNull()?.message.orEmpty().contains("string result limit"),
            )
            assertNull(runtime.evaluateText("var_large"))

            val swallowedAllocation = runtime.runSnippet(
                "pcall(function() return var_half .. var_half end); var_after_allocation = 1",
            )
            assertTrue(swallowedAllocation.isFailure)
            assertNull(runtime.evaluateText("var_after_allocation"))

            val cumulativeAllocation = runtime.runSnippet(
                "var_many = {}; for i = 1, 20 do var_many[i] = string.rep('x', 600000) end",
            )
            assertTrue(cumulativeAllocation.isFailure)
            assertTrue(
                cumulativeAllocation.exceptionOrNull()?.message.orEmpty().contains("string result limit"),
            )

            assertTrue(runtime.runSnippet("var_small = string.rep('ab', 3)").isSuccess)
            assertEquals("ababab", runtime.evaluateText("var_small"))
            assertTrue(runtime.runSnippet("var_small = 'ab' .. 'cd'").isSuccess)
            assertEquals("abcd", runtime.evaluateText("var_small"))
        }
    }

    @Test
    fun cloxClockCallbacksReceiveSecondMinuteAndHourBoundaries() {
        val script = """
            var_second_calls = 0
            var_minute_calls = 0
            var_hour_calls = 0
            var_last_minute = -1
            var_last_second = -1

            function on_second(minute, second)
              var_second_calls = var_second_calls + 1
              var_last_minute = minute
              var_last_second = second
            end
            function on_minute() var_minute_calls = var_minute_calls + 1 end
            function on_hour() var_hour_calls = var_hour_calls + 1 end
        """.trimIndent()

        WatchFaceLuaRuntime.create(script).use { runtime ->
            assertTrue(runtime.stepClock(elapsedSeconds = 1.0, wallTimeMillis = 3_599_900L).isSuccess)
            assertTrue(runtime.stepClock(elapsedSeconds = 1.2, wallTimeMillis = 3_600_100L).isSuccess)
            assertTrue(runtime.stepClock(elapsedSeconds = 1.4, wallTimeMillis = 3_600_900L).isSuccess)

            assertEquals(2.0, runtime.evaluateNumber("var_second_calls"), 0.0)
            assertEquals(2.0, runtime.evaluateNumber("var_minute_calls"), 0.0)
            assertEquals(2.0, runtime.evaluateNumber("var_hour_calls"), 0.0)
            assertEquals(0.0, runtime.evaluateNumber("var_last_minute"), 0.0)
            assertEquals(0.0, runtime.evaluateNumber("var_last_second"), 0.0)
        }
    }

    @Test
    fun repeatedLayerExpressionsReuseOneBoundedCompiledChunk() {
        WatchFaceLuaRuntime.create("var_value = 7").use { runtime ->
            repeat(20) {
                assertEquals(14.0, runtime.evaluateNumber("var_value * 2"), 0.0)
            }
            assertEquals(1, runtime.cachedExpressionCount)

            repeat(WatchFaceLuaRuntime.MAX_CACHED_EXPRESSIONS_FOR_TEST + 32) { index ->
                runtime.evaluateNumber("var_value + $index")
            }
            assertTrue(
                runtime.cachedExpressionCount <= WatchFaceLuaRuntime.MAX_CACHED_EXPRESSIONS_FOR_TEST,
            )
        }
    }

    @Test
    fun cloxTagSplicingWorksInGlobalScriptsTapSnippetsAndMixedLayerExpressions() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            if (tag == "bl") WatchTagResolution.Resolved("80")
            else WatchTagResolution.Unsupported(tag)
        }
        WatchFaceLuaRuntime.create(
            "var_value = {bl} + 5; var_tag = wm_tag('{bl}')",
            resolver,
        ).use { runtime ->
            assertEquals(85.0, runtime.evaluateNumber("var_value", resolver), 0.0)
            assertEquals(80.0, runtime.evaluateNumber("var_tag", resolver), 0.0)
            assertEquals(165.0, runtime.evaluateNumber("var_value + {bl}", resolver), 0.0)

            assertTrue(runtime.runSnippet("var_label = 'Battery {bl}'", resolver).isSuccess)
            assertEquals("Battery 80", runtime.evaluateText("var_label", resolver))
        }
    }

    @Test
    fun dependencySnapshotIncludesGlobalsAssignedInsideFutureCallbacks() {
        WatchFaceLuaRuntime.create(
            """
                function on_second()
                  var_later = 42
                end
            """.trimIndent(),
        ).use { runtime ->
            assertTrue(runtime.referencesFaceState("var_later + 1"))
        }
    }

    @Test
    fun tapSnippetDeclarationsBecomeDynamicDependenciesForFutureFrames() {
        WatchFaceLuaRuntime.create("var_value = 1").use { runtime ->
            assertTrue(runtime.runSnippet("var_tapped = 4").isSuccess)
            assertTrue(runtime.referencesFaceState("var_tapped * 2"))
            assertEquals(8.0, runtime.evaluateNumber("var_tapped * 2"), 0.0)
        }
    }

    @Test
    fun realProvidedFlueWatchScriptRestartsAndAdvancesItsSpriteSheet() {
        val script = providedSample("watchface-samples/flue-watch-script.lua")
        WatchFaceLuaRuntime.create(script).use { runtime ->
            assertTrue(runtime.runSnippet("restart_play()").isSuccess)
            assertTrue(runtime.stepMilliseconds(5.000).isSuccess)
            assertTrue(runtime.stepMilliseconds(5.100).isSuccess)
            assertEquals(3.0, runtime.evaluateNumber("var_frame"), 0.0)
        }
    }

    @Test
    fun realProvidedNexusScriptAnimatesAndRespondsToTap() {
        val script = providedSample("watchface-samples/nexus-2.2-script.lua")
        WatchFaceLuaRuntime.create(script).use { runtime ->
            assertTrue(runtime.stepMilliseconds(8.000).isSuccess)
            val activeBefore = runtime.evaluateNumber(
                "(function() local n=0 for i=0,49 do if var_TP[i].active==1 then n=n+1 end end return n end)()",
            )
            assertEquals(12.0, activeBefore, 0.0)

            assertTrue(runtime.runSnippet("nexus_tap(55)").isSuccess)
            val pulses = runtime.evaluateNumber(
                "(function() local n=0 for i=0,49 do if var_TP[i].active==1 and var_TP[i].type==1 then n=n+1 end end return n end)()",
            )
            assertEquals(4.0, pulses, 0.0)
            assertTrue(runtime.evaluateNumber("var_T_x(0, var_TP)").isFinite())
            assertTrue(runtime.referencesFaceState("var_T_x(0, var_TP)"))
        }
    }

    private fun providedSample(relativePath: String): String {
        return checkNotNull(javaClass.classLoader?.getResource(relativePath)) {
            "missing watch-face sample resource: $relativePath"
        }.readText(Charsets.UTF_8)
    }

    private companion object {
        val FLUE_WATCH_SCRIPT = """
            var_frame = var_frame or 1
            var_ms = var_ms or 0
            var_first = var_first or 1

            function restart_play()
                var_frame = 1
                var_ms = 0
                var_first = 1
                on_millisecond = function(dt)
                    if var_first == 1 then
                        var_first = 0
                        return
                    end
                    if dt > 0.1 then dt = 0.1 end
                    var_ms = var_ms + math.floor(dt * 1000 + 0.5)
                    local nf = 1 + math.floor(var_ms / 50)
                    if nf >= 67 then
                        var_frame = 67
                        var_ms = 0
                        on_millisecond = nil
                    else
                        var_frame = nf
                    end
                end
            end
        """.trimIndent()

        val NEXUS_SCRIPT = """
            local N = 50
            local NORMALS = 12
            var_NOW = var_NOW or 0
            var_TP = var_TP or {}
            for i = 0, N - 1 do var_TP[i] = var_TP[i] or { active = 0, type = 0 } end

            local function claim()
                for i = 0, N - 1 do
                    if var_TP[i].active ~= 1 then return var_TP[i] end
                end
            end

            local function spawn(kind)
                local p = claim()
                if p then
                    p.ox = math.random(-200, 200)
                    p.oy = math.random(-200, 200)
                    p.vx = 100
                    p.vy = 0
                    p.start = var_NOW
                    p.active = 1
                    p.type = kind
                end
            end

            function on_millisecond(dt)
                var_NOW = var_NOW + dt
                local count = 0
                for i = 0, N - 1 do
                    if var_TP[i].active == 1 and var_TP[i].type == 0 then count = count + 1 end
                end
                while count < NORMALS do spawn(0); count = count + 1 end
            end

            function nexus_tap(idx)
                for i = 1, 4 do spawn(1) end
            end

            function var_T_x(i)
                local p = var_TP[i]
                if p.active ~= 1 then return -9999 end
                return p.ox + p.vx * (var_NOW - p.start)
            end

            function active_count()
                local count = 0
                for i = 0, N - 1 do if var_TP[i].active == 1 then count = count + 1 end end
                return count
            end

            function pulse_count()
                local count = 0
                for i = 0, N - 1 do
                    if var_TP[i].active == 1 and var_TP[i].type == 1 then count = count + 1 end
                end
                return count
            end
        """.trimIndent()
    }
}
