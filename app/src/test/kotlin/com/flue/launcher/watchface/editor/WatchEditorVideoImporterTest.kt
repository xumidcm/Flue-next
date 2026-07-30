package com.flue.launcher.watchface.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorVideoImporterTest {
    @Test
    fun ffmpegRuntimeSupportRejects32BitOnlyDevices() {
        assertEquals(
            false,
            WatchEditorVideoImporter.isRuntimeSupported(arrayOf("armeabi-v7a", "armeabi")),
        )
        assertTrue(
            WatchEditorVideoImporter.isRuntimeSupported(arrayOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun ffmpegArgumentsCarryTrimResolutionRateAndBitrate() {
        val options = WatchEditorVideoImportOptions(
            startMs = 1_250L,
            endMs = 5_750L,
            crop = WatchEditorVideoCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f),
            resolutionPreset = WatchEditorVideoResolutionPreset.P480,
            frameRate = 12.5f,
            bitrateKbps = 1_800,
        )

        val arguments = WatchEditorVideoImporter.buildArguments(
            inputFile = File("input.mp4"),
            outputPattern = File("frame_%05d.jpg"),
            info = WatchEditorVideoInfo(durationMs = 10_000L, width = 1920, height = 1080),
            options = options,
        )

        assertEquals("1.250", arguments[arguments.indexOf("-ss") + 1])
        assertEquals("4.500", arguments[arguments.indexOf("-t") + 1])
        assertEquals("1800k", arguments[arguments.indexOf("-b:v") + 1])
        assertEquals(
            "crop=w=1536:h=648:x=192:y=216,scale=w=1138:h=480:flags=lanczos,fps=12.5",
            arguments[arguments.indexOf("-vf") + 1],
        )
        assertEquals(80, options.frameDelayMs)
    }

    @Test
    fun pPresetUsesCroppedShortEdgeWithoutChangingAspectOrUpscaling() {
        val options = WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 1_000L,
            resolutionPreset = WatchEditorVideoResolutionPreset.P1080,
            frameRate = 10f,
            bitrateKbps = 1_800,
        )

        assertEquals(
            WatchEditorVideoTransform(0, 0, 1600, 1200, 1440, 1080),
            resolveVideoTransform(WatchEditorVideoInfo(1_000L, 1600, 1200), options),
        )
        assertEquals(
            WatchEditorVideoTransform(0, 0, 640, 480, 640, 480),
            resolveVideoTransform(WatchEditorVideoInfo(1_000L, 640, 480), options),
        )
    }

    @Test
    fun originalKeepsEvenCroppedPixelDimensions() {
        val options = WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 1_000L,
            crop = WatchEditorVideoCrop(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f),
            resolutionPreset = WatchEditorVideoResolutionPreset.ORIGINAL,
            frameRate = 10f,
            bitrateKbps = 1_800,
        )
        val transform = resolveVideoTransform(WatchEditorVideoInfo(1_000L, 1001, 751), options)
        assertEquals(transform.cropWidth, transform.outputWidth)
        assertEquals(transform.cropHeight, transform.outputHeight)
        assertEquals(0, transform.outputWidth % 2)
        assertEquals(0, transform.outputHeight % 2)
    }

    @Test
    fun originalResolutionUsesTheEditableBitrateField() {
        val info = WatchEditorVideoInfo(
            durationMs = 1_000L,
            width = 1920,
            height = 1080,
            bitrateKbps = 6_400,
        )

        assertEquals(
            1_800,
            resolveVideoBitrateKbps(info, WatchEditorVideoResolutionPreset.ORIGINAL, 1_800),
        )
        assertEquals(
            1_800,
            resolveVideoBitrateKbps(info, WatchEditorVideoResolutionPreset.P720, 1_800),
        )
    }

    @Test
    fun resolutionPresetsUseTheirCapsWithoutRaisingLowSourceBitrate() {
        val high = WatchEditorVideoInfo(1_000L, 1920, 1080, bitrateKbps = 24_000)
        assertEquals(20_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P1080))
        assertEquals(15_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P720))
        assertEquals(10_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P480))
        assertEquals(10_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P360))
        assertEquals(24_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.ORIGINAL))

        val low = high.copy(bitrateKbps = 820)
        WatchEditorVideoResolutionPreset.entries.forEach { preset ->
            assertEquals(820, defaultVideoBitrateKbps(low, preset))
        }
    }
}
