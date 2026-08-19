package com.flue.launcher.watchface.jbwatch

import com.flue.launcher.watchface.standard.WatchTagResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JbWatchLuaExpressionRuntimeTest {
    @Test
    fun evaluatesProtectedJbWatchStepAndDistanceTextExpressions() {
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertEquals(
                "61",
                runtime.evaluateText(STEP_EXPRESSION, stepsResolver(1_234)),
            )
            assertEquals(
                "1000,0",
                runtime.evaluateText(STEP_EXPRESSION, stepsResolver(20_000)),
            )
            assertEquals(
                "720m",
                runtime.evaluateText(DISTANCE_EXPRESSION, stepsResolver(1_000)),
            )
            assertEquals(
                "1,4km",
                runtime.evaluateText(DISTANCE_EXPRESSION, stepsResolver(2_000)),
            )
        }
    }

    @Test
    fun leavesPlainJbWatchTextForTheExistingTemplatePath() {
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertNull(runtime.evaluateText("Steps {ssc}", stepsResolver(1_234)))
            assertNull(runtime.evaluateText("{ssc}", stepsResolver(1_234)))
            assertNull(runtime.evaluateText("Monday", stepsResolver(1_234)))
            assertNull(runtime.evaluateText("not available", stepsResolver(1_234)))
            assertNull(runtime.evaluateText("true story", stepsResolver(1_234)))
        }
    }

    @Test
    fun evaluatesJbDateAliasesInsideAndOutsideQuotedLuaText() {
        val resolver: (String) -> WatchTagResolution = { tag ->
            when (tag.lowercase()) {
                "time" -> WatchTagResolution.Resolved("08:09")
                "ddm" -> WatchTagResolution.Resolved("08")
                "dmm" -> WatchTagResolution.Resolved("Aug")
                else -> WatchTagResolution.Unsupported(tag)
            }
        }
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertEquals("08:09!", runtime.evaluateText("'{time}' .. '!'", resolver))
            assertEquals("08:09!", runtime.evaluateText("{time} .. '!'", resolver))
            assertEquals("08 Aug", runtime.evaluateText("'{ddm} {dmm}'", resolver))
        }
    }

    @Test
    fun evaluatesProvidedDayOfYearExpression() {
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertEquals(
                "321",
                runtime.evaluateText(DAY_OF_YEAR_EXPRESSION) { tag ->
                    if (tag.equals("ddy", ignoreCase = true)) WatchTagResolution.Resolved("323")
                    else WatchTagResolution.Unsupported(tag)
                },
            )
        }
    }

    @Test
    fun evaluatesProvidedMoonPhaseExpressionWithQuotedTag() {
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertEquals(
                " Wax.Gibbous",
                runtime.evaluateText(MOON_PHASE_EXPRESSION) { tag ->
                    if (tag.equals("wmp", ignoreCase = true)) WatchTagResolution.Resolved("4")
                    else WatchTagResolution.Unsupported(tag)
                },
            )
        }
    }

    @Test
    fun evaluatesProvidedStepExpressionWithTrailingWhitespace() {
        JbWatchLuaExpressionRuntime.create().use { runtime ->
            assertEquals("61", runtime.evaluateText("$STEP_EXPRESSION   ", stepsResolver(1_234)))
        }
    }

    @Test
    fun evaluatesNumericAndVisibilityAttributesAndStopsAfterClose() {
        val runtime = JbWatchLuaExpressionRuntime.create()
        val resolver = stepsResolver(2_000)

        assertEquals(72.0, runtime.evaluateNumber("math.floor({ssc} * 0.036)", resolver)!!, 0.0)
        assertTrue(runtime.evaluateBoolean("{ssc} >= 1000 and true or false", resolver) == true)
        assertFalse(runtime.evaluateBoolean("{ssc} < 1000 and true or false", resolver) == true)
        assertNull(runtime.evaluateNumber("broken(", resolver))

        runtime.close()

        assertNull(runtime.evaluateText(STEP_EXPRESSION, resolver))
        assertNull(runtime.evaluateNumber("{ssc} * 2", resolver))
        assertNull(runtime.evaluateBoolean("{ssc} > 0", resolver))
    }

    private fun stepsResolver(steps: Int): (String) -> WatchTagResolution = { tag ->
        if (tag.equals("ssc", ignoreCase = true)) {
            WatchTagResolution.Resolved(steps.toString())
        } else {
            WatchTagResolution.Unsupported(tag)
        }
    }

    private companion object {
        const val STEP_EXPRESSION =
            "({ssc}/20) < 1000 and math.floor({ssc}/20) " +
                "or string.gsub(string.format('%.1f', ({ssc}/20)), \"%.\", \",\")"

        const val DISTANCE_EXPRESSION =
            "({ssc} * 0.72) >= 1000 " +
                "and string.gsub(string.format(\"%.1fkm\", ({ssc} * 0.72)/1000), \"%.\", \",\") " +
                "or math.floor({ssc} * 0.72) .. \"m\""

        const val DAY_OF_YEAR_EXPRESSION =
            "(((((((322-{ddy})<0))) and (((322-{ddy})+322)) or ((322-{ddy})))))"

        const val MOON_PHASE_EXPRESSION =
            "'{wmp}' == '1' and ' New' or '{wmp}' == '2' and 'Wax.Crescent' " +
                "or '{wmp}' == '3' and 'First Quarter' or '{wmp}' == '4' and ' Wax.Gibbous' " +
                "or '{wmp}' == '5' and 'Full' or '{wmp}' == '6' and 'Wan.Gibbous' " +
                "or '{wmp}' == '7' and 'Third Quarter' or '{wmp}' == '8' and 'Wan.Crescent' " +
                "or '{wmp}' == '9' and ' Old'"
    }
}
