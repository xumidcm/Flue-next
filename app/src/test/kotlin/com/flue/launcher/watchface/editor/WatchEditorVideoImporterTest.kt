package com.flue.launcher.watchface.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorVideoImporterTest {
    @Test
    fun ffmpegArgumentsCarryTrimResolutionRateAndBitrate() {
        val options = WatchEditorVideoImportOptions(
            startMs = 1_250L,
            endMs = 5_750L,
            maxWidth = 512,
            maxHeight = 384,
            frameRate = 12.5f,
            bitrateKbps = 1_800,
        )

        val arguments = WatchEditorVideoImporter.buildArguments(
            inputFile = File("input.mp4"),
            outputPattern = File("frame_%05d.jpg"),
            options = options,
        )

        assertEquals("1.250", arguments[arguments.indexOf("-ss") + 1])
        assertEquals("4.500", arguments[arguments.indexOf("-t") + 1])
        assertEquals("1800k", arguments[arguments.indexOf("-b:v") + 1])
        assertTrue(
            arguments[arguments.indexOf("-vf") + 1].contains(
                "fps=12.5,scale=w=512:h=384:force_original_aspect_ratio=decrease",
            ),
        )
        assertEquals(80, options.frameDelayMs)
    }
}
