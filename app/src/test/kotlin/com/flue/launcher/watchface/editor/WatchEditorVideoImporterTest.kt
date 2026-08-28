package com.flue.launcher.watchface.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun resolutionPresetsUseTheirDefaultsWithoutRaisingLowSourceBitrate() {
        val high = WatchEditorVideoInfo(1_000L, 1920, 1080, bitrateKbps = 24_000)
        assertEquals(8_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P1080))
        assertEquals(8_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P720))
        assertEquals(4_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P480))
        assertEquals(4_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.P360))
        assertEquals(24_000, defaultVideoBitrateKbps(high, WatchEditorVideoResolutionPreset.ORIGINAL))

        val low = high.copy(bitrateKbps = 820)
        WatchEditorVideoResolutionPreset.entries.forEach { preset ->
            assertEquals(820, defaultVideoBitrateKbps(low, preset))
        }
    }

    /**
     * 源可能是 120/240fps，而 [WatchEditorVideoImportOptions] 的 init 只收 ≤60。
     * UI 必须用夹过的值，否则高帧率素材选「原画」会直接抛异常。
     */
    @Test
    fun importableFrameRateClampsHighSpeedSourcesIntoTheOptionsRange() {
        val base = WatchEditorVideoInfo(1_000L, 1920, 1080)
        assertEquals(null, base.importableFrameRate)
        assertEquals(30f, base.copy(frameRate = 30f).importableFrameRate)
        assertEquals(60f, base.copy(frameRate = 120f).importableFrameRate)
        assertEquals(60f, base.copy(frameRate = 240f).importableFrameRate)
        // 夹过的值必须真的能构造出 options —— 这条断言就是这个属性存在的理由。
        val clamped = base.copy(frameRate = 240f).importableFrameRate!!
        WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 1_000L,
            frameRate = clamped,
            bitrateKbps = 4_000,
        )
    }

    /**
     * 重编码走 mp4，且不能带 mjpeg 那套只对静帧序列有意义的参数。
     *
     * 默认编码器必须是**真 H.264**：表盘的 video 图层由 MediaPlayer 播，实机只有 H.264
     * 播得出来。而编码器必须是 ffmpeg-kit-video **实际包含**的那个 —— 该变体不含 GPL 的
     * libx264，写 `-c:v libx264` 会在真机上 returnCode=1（已实测），所以 AVC 只能走
     * h264_mediacodec；它不可用时导入必须明确失败，不能写入其它编码。
     */
    @Test
    fun reencodeArgumentsProduceH264Mp4WithCompatibilityConstraints() {
        val info = WatchEditorVideoInfo(4_000L, 1920, 1080)
        val options = WatchEditorVideoImportOptions(
            startMs = 500L,
            endMs = 3_500L,
            resolutionPreset = WatchEditorVideoResolutionPreset.P720,
            frameRate = 30f,
            bitrateKbps = 8_000,
        )
        val arguments = WatchEditorVideoImporter.buildReencodeArguments(
            inputFile = File("/tmp/in.mp4"),
            outputFile = File("/tmp/out.mp4"),
            info = info,
            options = options,
        )
        assertTrue(arguments.windowed(2).contains(listOf("-c:v", "h264_mediacodec")))
        assertEquals(false, arguments.contains("libx264"))
        assertTrue(arguments.windowed(2).contains(listOf("-pix_fmt", "yuv420p")))
        assertTrue(arguments.windowed(2).contains(listOf("-b:v", "8000k")))
        assertTrue(arguments.windowed(2).contains(listOf("-profile:v", "baseline")))
        assertTrue(arguments.windowed(2).contains(listOf("-level", "3.1")))
        assertTrue(arguments.windowed(2).contains(listOf("-g", "60")))
        assertTrue(arguments.windowed(2).contains(listOf("-maxrate", "8000k")))
        assertTrue(arguments.windowed(2).contains(listOf("-bufsize", "16000k")))
        assertTrue(arguments.contains("-an"))
        assertTrue(arguments.contains("+faststart"))
        assertEquals(false, arguments.contains("mpeg4"))
        assertTrue(arguments.last().endsWith(".mp4"))
    }

    @Test
    fun onlyH264EncoderIsAllowedAndThereIsNoSoftwareFallback() {
        assertEquals(
            listOf("h264_mediacodec"),
            WatchEditorVideoEncoder.entries.map { it.ffmpegName },
        )
    }

    @Test
    fun inputValidationRejectsInvalidVideoMetadataBeforeConversion() {
        assertThrows(WatchEditorException::class.java) {
            validateVideoInput(WatchEditorVideoInfo(durationMs = 0L, width = 1920, height = 1080))
        }
        assertThrows(WatchEditorException::class.java) {
            validateVideoInput(WatchEditorVideoInfo(durationMs = 601_000L, width = 1920, height = 1080))
        }
        assertThrows(WatchEditorException::class.java) {
            validateVideoInput(WatchEditorVideoInfo(durationMs = 1_000L, width = 8_000, height = 4_000))
        }
        assertThrows(WatchEditorException::class.java) {
            validateVideoInput(WatchEditorVideoInfo(durationMs = 1_000L, width = 1920, height = 1080, frameRate = 241f))
        }
        validateVideoInput(WatchEditorVideoInfo(durationMs = 1_000L, width = 1920, height = 1080, frameRate = 60f))
    }

    @Test
    fun outputValidationRequiresH264Mp4AndExpectedDimensions() {
        validateEncodedVideoMetadata(
            containerMimeType = "video/mp4; codecs=avc1",
            codecMimeType = "video/avc",
            width = 1280,
            height = 720,
            expectedWidth = 1280,
            expectedHeight = 720,
        )
        assertThrows(WatchEditorException::class.java) {
            validateEncodedVideoMetadata("video/webm", "video/avc", 1280, 720, 1280, 720)
        }
        assertThrows(WatchEditorException::class.java) {
            validateEncodedVideoMetadata("video/mp4", "video/hevc", 1280, 720, 1280, 720)
        }
        assertThrows(WatchEditorException::class.java) {
            validateEncodedVideoMetadata("video/mp4", "video/avc", 640, 360, 1280, 720)
        }
    }
}
