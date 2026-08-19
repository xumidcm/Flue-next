package com.flue.launcher.watchface.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorVideoImporterTest {
    /**
     * 直通（字节拷贝）只允许 H.264 源。
     *
     * 表盘的 video 图层由 MediaPlayer 播，实机只有 H.264 播得出来。直通不重编码，
     * 源是什么编码进包就还是什么编码 —— 所以编码本身必须是闸门的一部分。
     * 「读不到编码」按不通过处理：猜错的代价是上表黑屏，多转一次只是慢一点。
     */
    @Test
    fun passThroughRequiresAnH264SourceEvenWhenNothingElseChanged() {
        fun info(codecMime: String?) = WatchEditorVideoInfo(
            durationMs = 4_000L,
            width = 720,
            height = 720,
            bitrateKbps = 4_000,
            frameRate = 30f,
            codecMime = codecMime,
            containerMimeType = "video/mp4",
        )
        // 用户什么都没改：时间轴整段、无裁剪、帧率码率与源一致。
        val untouched = WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 4_000L,
            resolutionPreset = WatchEditorVideoResolutionPreset.ORIGINAL,
            frameRate = 30f,
            bitrateKbps = 4_000,
        )
        assertTrue(canPassThroughVideo(info(WatchEditorVideoInfo.H264_MIME), untouched))
        assertEquals(false, canPassThroughVideo(info("video/hevc"), untouched))
        assertEquals(false, canPassThroughVideo(info("video/x-vnd.on2.vp9"), untouched))
        assertEquals(false, canPassThroughVideo(info(null), untouched))
    }

    /**
     * 直通还要求容器已经是 MP4。
     *
     * `WM_FORMAT_1.0.md` §1 把 `videos/` 写成「video assets (MP4)」。H.264 装在
     * mkv/webm 里 MediaPlayer 照样能播，所以在 Flue 上看不出问题 —— 包一拿出去就是
     * 别的渲染器打不开的容器。读不到容器信息时按不通过处理，退回重编码（输出恒为 mp4）。
     */
    @Test
    fun passThroughAlsoRequiresAnMp4Container() {
        fun info(containerMime: String?) = WatchEditorVideoInfo(
            durationMs = 4_000L,
            width = 720,
            height = 720,
            bitrateKbps = 4_000,
            frameRate = 30f,
            codecMime = WatchEditorVideoInfo.H264_MIME,
            containerMimeType = containerMime,
        )
        val untouched = WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 4_000L,
            resolutionPreset = WatchEditorVideoResolutionPreset.ORIGINAL,
            frameRate = 30f,
            bitrateKbps = 4_000,
        )
        assertTrue(canPassThroughVideo(info("video/mp4"), untouched))
        // 带参数的 MIME 也要认。
        assertTrue(canPassThroughVideo(info("video/mp4; codecs=avc1"), untouched))
        assertTrue(canPassThroughVideo(info("video/x-m4v"), untouched))
        assertEquals(false, canPassThroughVideo(info("video/x-matroska"), untouched))
        assertEquals(false, canPassThroughVideo(info("video/webm"), untouched))
        assertEquals(false, canPassThroughVideo(info(null), untouched))
    }

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
     * h264_mediacodec；它不可用时由调用方按 LADDER 退到 mpeg4。
     */
    @Test
    fun reencodeArgumentsProduceMp4WithAnEncoderThePackageActuallyShips() {
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
        // 默认档 = 阶梯首项 = 真 H.264。
        assertTrue(arguments.windowed(2).contains(listOf("-c:v", "h264_mediacodec")))
        // GPL 编码器不在包里，出现即是回归。
        assertEquals(false, arguments.contains("libx264"))
        assertTrue(arguments.windowed(2).contains(listOf("-pix_fmt", "yuv420p")))
        assertTrue(arguments.windowed(2).contains(listOf("-b:v", "8000k")))
        assertTrue(arguments.contains("-an"))
        assertTrue(arguments.contains("+faststart"))
        // mjpeg 专属参数不该出现在视频轨这条路上。
        assertEquals(false, arguments.contains("-start_number"))
        assertEquals(false, arguments.contains("-qmin"))
        assertTrue(arguments.last().endsWith(".mp4"))
    }

    /**
     * 阶梯的形状本身就是契约：首项必须是 H.264，兜底必须是不依赖硬件的纯软件编码器，
     * 且每一档都能算出一份完整参数。少了兜底，没有 AVC 硬编的机器会整个导不进来。
     */
    @Test
    fun encoderLadderTriesRealH264FirstAndKeepsASoftwareFallback() {
        assertEquals(
            listOf("h264_mediacodec", "mpeg4"),
            WatchEditorVideoEncoder.LADDER.map { it.ffmpegName },
        )
        val info = WatchEditorVideoInfo(2_000L, 1280, 720)
        val options = WatchEditorVideoImportOptions(
            startMs = 0L,
            endMs = 2_000L,
            resolutionPreset = WatchEditorVideoResolutionPreset.P720,
            frameRate = 24f,
            bitrateKbps = 4_000,
        )
        WatchEditorVideoEncoder.LADDER.forEach { encoder ->
            val arguments = WatchEditorVideoImporter.buildReencodeArguments(
                inputFile = File("/tmp/in.mp4"),
                outputFile = File("/tmp/out.mp4"),
                info = info,
                options = options,
                encoder = encoder,
            )
            assertTrue(arguments.windowed(2).contains(listOf("-c:v", encoder.ffmpegName)))
            assertTrue(arguments.windowed(2).contains(listOf("-pix_fmt", encoder.pixelFormat)))
            assertTrue(arguments.last().endsWith(".mp4"))
        }
    }
}
