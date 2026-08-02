package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchArchiveInstallerTest {
    @Test
    fun installsAStandardWatchXmlWithoutLegacyWatchPxml() = withTemporaryDirectory { root ->
        val archive = File(root, "simple.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "preview.jpg" to byteArrayOf(1, 2, 3),
            "images/.hand.png" to byteArrayOf(4, 5, 6),
        )

        val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

        assertTrue(installed.watchXml.isFile)
        assertTrue(installed.preview?.isFile == true)
        assertEquals("Simple", installed.definition.name)
        assertEquals(WatchmakerDefinitionVersion.FORMAT_1, installed.definition.version)
        assertEquals(WatchFaceShape.ROUND, installed.definition.shape)
        assertEquals(listOf("image", "text"), installed.definition.layers.map { it.type })
        assertFalse(File(installed.rootDirectory, "watch.pxml").exists())
    }

    @Test
    fun installsProtectedWatchmakerDefinitionAndDecodesProtectedImages() =
        withTemporaryDirectory { root ->
            val archive = File(root, "protected.watch")
            writeSyntheticProtectedFixture(archive)

            val installer = WatchArchiveInstaller()
            val installed = installer.install(archive, File(root, "installed"))
            val decodedImage = File(installed.rootDirectory, "images/.face.ppng")
            val sentinelModifiedAt = 1_700_000_000_000L
            assertTrue(installed.watchXml.setLastModified(sentinelModifiedAt))
            assertTrue(decodedImage.setLastModified(sentinelModifiedAt))
            val reloaded = installer.install(archive, File(root, "installed"))

            assertEquals("Protected", installed.definition.name)
            assertEquals(2, installed.definition.layers.size)
            assertEquals(WatchFaceShape.SQUARE, installed.definition.shape)
            assertEquals("Protected", WatchmakerDefinitionParser().parse(installed.watchXml).name)
            assertEquals("Protected", reloaded.definition.name)
            assertEquals(sentinelModifiedAt, installed.watchXml.lastModified())
            assertEquals(sentinelModifiedAt, decodedImage.lastModified())
            assertTrue(
                decodedImage.readBytes().startsWithBytes(PNG_HEADER),
            )
            assertTrue(
                File(installed.rootDirectory, "images/.photo.pjpg")
                    .readBytes()
                    .startsWithBytes(JPEG_HEADER),
            )
            assertEquals(installed.watchXml.canonicalFile, WatchPreparedMarker.read(installed.rootDirectory))
        }

    @Test
    fun protectedDefinitionSupportsAttributeCaseAndUniqueRootPxmlForNestedShell() =
        withTemporaryDirectory { root ->
            val archive = File(root, "protected-nested.watch")
            writeZip(
                archive,
                "face/watch.xml" to
                    "<Watch name=\"Shell\" Protection=\"Y\"><Layer type=\"image\" /></Watch>"
                        .toByteArray(),
                "watch.pxml" to encodeProtectedXml(SYNTHETIC_PROTECTED_XML.toByteArray()),
                "images/.face.ppng" to xorWatchmaker(SYNTHETIC_PNG_BYTES),
                "images/.photo.pjpg" to xorWatchmaker(SYNTHETIC_JPEG_BYTES),
            )

            val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

            assertEquals("Protected", installed.definition.name)
            assertEquals(2, installed.definition.layers.size)
            assertEquals("face/watch.xml", installed.watchXml.relativeTo(installed.rootDirectory).invariantSeparatorsPath)
        }

    @Test
    fun alreadyDecodedDefinitionMayRetainProtectionFlagWithoutPxml() =
        withTemporaryDirectory { root ->
            val archive = File(root, "decoded-protection-flag.watch")
            writeZip(
                archive,
                "watch.xml" to SYNTHETIC_PROTECTED_XML.toByteArray(),
                "images/.face.ppng" to SYNTHETIC_PNG_BYTES,
                "images/.photo.pjpg" to SYNTHETIC_JPEG_BYTES,
            )

            val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

            assertEquals("Protected", installed.definition.name)
            assertEquals(2, installed.definition.layers.size)
        }

    @Test
    fun normalizedBackslashDirectoryIsExtractedAsDirectory() = withTemporaryDirectory { root ->
        val archive = File(root, "directory-marker.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "fonts\\" to byteArrayOf(),
            "fonts\\digits.ttf" to byteArrayOf(0, 1, 0, 0),
        )

        val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

        assertTrue(File(installed.rootDirectory, "fonts").isDirectory)
        assertTrue(File(installed.rootDirectory, "fonts/digits.ttf").isFile)
    }

    @Test
    fun missingMarkerRepairsFixedLegacyFixtureOnceThenUsesTheMarkerFastPath() =
        withTemporaryDirectory { root ->
            val legacy = File(root, "legacy").apply { mkdirs() }
            writeLegacyProtectedFixture(legacy)
            val decodedImage = File(legacy, "images/.face.ppng")
            val expectedImage = SYNTHETIC_PNG_BYTES
            val installer = WatchArchiveInstaller()

            val repaired = installer.repairInstalledPackage(legacy)

            assertEquals("Protected", WatchmakerDefinitionParser().parse(repaired).name)
            assertTrue(decodedImage.readBytes().contentEquals(expectedImage))
            assertEquals(repaired.canonicalFile, WatchPreparedMarker.read(legacy))

            // A committed immutable package must not be traversed again. This
            // invalid extension would fail the legacy repair path immediately.
            File(legacy, "images/not-part-of-the-committed-package.ppng")
                .writeBytes(byteArrayOf(1, 2, 3, 4))
            val sentinelModifiedAt = 1_700_000_000_000L
            assertTrue(decodedImage.setLastModified(sentinelModifiedAt))

            assertEquals(repaired.canonicalFile, installer.repairInstalledPackage(legacy).canonicalFile)
            assertEquals(sentinelModifiedAt, decodedImage.lastModified())
        }

    @Test
    fun legacyRepairDoesNotTrustPlaceholderLayersAndFindsUniqueRootPxml() =
        withTemporaryDirectory { root ->
            val legacy = File(root, "legacy").apply { mkdirs() }
            File(legacy, "face/watch.xml").apply {
                parentFile.mkdirs()
                writeText(
                    "<Watch name=\"Shell\" Protection=\"yes\"><Layer type=\"image\" /></Watch>",
                )
            }
            File(legacy, "watch.pxml").writeBytes(
                encodeProtectedXml(SYNTHETIC_PROTECTED_XML.toByteArray()),
            )
            File(legacy, "images/.face.ppng").apply {
                parentFile.mkdirs()
                writeBytes(xorWatchmaker(SYNTHETIC_PNG_BYTES))
            }
            File(legacy, "images/.photo.pjpg")
                .writeBytes(xorWatchmaker(SYNTHETIC_JPEG_BYTES))

            val repaired = WatchArchiveInstaller().repairInstalledPackage(legacy)
            val definition = WatchmakerDefinitionParser().parse(repaired)

            assertEquals("Protected", definition.name)
            assertEquals(2, definition.layers.size)
            assertEquals("face/watch.xml", repaired.relativeTo(legacy).invariantSeparatorsPath)
        }

    @Test
    fun legacyRepairRejectsAmbiguousWatchXmlInsteadOfPickingTraversalOrder() =
        withTemporaryDirectory { root ->
            val legacy = File(root, "legacy").apply { mkdirs() }
            File(legacy, "one/watch.xml").apply {
                parentFile.mkdirs()
                writeText(FORMAT_ONE_XML)
            }
            File(legacy, "two/watch.xml").apply {
                parentFile.mkdirs()
                writeText(FORMAT_ONE_XML.replace("Simple", "Second"))
            }

            assertArchiveFailure(WatchArchiveFailureReason.AMBIGUOUS_WATCH_XML) {
                WatchArchiveInstaller().repairInstalledPackage(legacy)
            }
        }

    @Test
    fun currentPreparedMarkerIsVersionedAndMalformedOrOldMarkersTriggerRepair() =
        withTemporaryDirectory { root ->
            val legacy = File(root, "legacy").apply { mkdirs() }
            writeLegacyProtectedFixture(legacy)
            val marker = File(legacy, ".flue/prepared").apply {
                parentFile.mkdirs()
                writeText("flue-watch-prepared\nversion=0\nwatchXml=broken\n")
            }

            val repaired = WatchArchiveInstaller().repairInstalledPackage(legacy)

            assertEquals(repaired.canonicalFile, WatchPreparedMarker.read(legacy))
            assertTrue(marker.readText().contains("version=${WatchPreparedMarker.CURRENT_VERSION}"))
        }

    @Test
    fun versionOneMarkerCannotBypassFullProtectedImageValidation() =
        withTemporaryDirectory { root ->
            val legacy = File(root, "legacy").apply { mkdirs() }
            File(legacy, "watch.xml").writeText(SYNTHETIC_PROTECTED_XML)
            File(legacy, "images/.face.ppng").apply {
                parentFile.mkdirs()
                writeBytes(PNG_HEADER + byteArrayOf(1, 2, 3, 4))
            }
            val encodedWatchXml = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("watch.xml".toByteArray(Charsets.UTF_8))
            File(legacy, ".flue/prepared").apply {
                parentFile.mkdirs()
                writeText(
                    "flue-watch-prepared\nversion=1\nwatchXml=$encodedWatchXml\n",
                    Charsets.US_ASCII,
                )
            }

            assertArchiveFailure(WatchArchiveFailureReason.INVALID_PROTECTED_ASSET) {
                WatchArchiveInstaller().repairInstalledPackage(legacy)
            }
        }

    @Test
    fun contentAddressedInstallAndLegacyRepairAreSerializedAcrossInstallerInstances() =
        withTemporaryDirectory { root ->
            val archive = File(root, "protected.watch")
            writeSyntheticProtectedFixture(archive)
            val installRoot = File(root, "installed")
            val legacy = File(root, "legacy").apply { mkdirs() }
            writeLegacyProtectedFixture(legacy)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val start = CountDownLatch(1)
                val futures = (0 until 8).map { index ->
                    executor.submit<Pair<InstalledWatchArchive, File>> {
                        start.await(5, TimeUnit.SECONDS)
                        val installer = WatchArchiveInstaller(nowEpochMillis = { 10_000L + index })
                        installer.install(archive, installRoot) to installer.repairInstalledPackage(legacy)
                    }
                }
                start.countDown()
                val results = futures.map { it.get(15, TimeUnit.SECONDS) }

                assertEquals(1, results.map { it.first.rootDirectory.canonicalPath }.toSet().size)
                assertTrue(results.all { it.first.definition.name == "Protected" })
                assertTrue(results.all { WatchmakerDefinitionParser().parse(it.second).name == "Protected" })
                assertTrue(File(legacy, "images/.face.ppng").readBytes().contentEquals(SYNTHETIC_PNG_BYTES))
                assertEquals(
                    results.first().second.canonicalFile,
                    WatchPreparedMarker.read(legacy),
                )
                assertTrue(installRoot.listFiles().orEmpty().none { it.name.startsWith(".install-") })
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun transactionMutexNeverAllowsTwoWritersIntoTheSameTarget() =
        withTemporaryDirectory { root ->
            val executor = Executors.newFixedThreadPool(8)
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val start = CountDownLatch(1)
            try {
                val futures = (0 until 24).map {
                    executor.submit {
                        start.await(5, TimeUnit.SECONDS)
                        WatchPackageTransactionMutex.withTarget(File(root, "same-target")) {
                            val count = active.incrementAndGet()
                            maximumActive.accumulateAndGet(count) { left, right -> maxOf(left, right) }
                            try {
                                Thread.sleep(5)
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                }
                start.countDown()
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertEquals(1, maximumActive.get())
        }

    @Test
    fun localProtectedCorpusInspectsPreparesAndReloadsWithoutDoubleDecoding() =
        withTemporaryDirectory { root ->
            val downloadRoot = File(System.getProperty("user.home"), "下载")
            val corpus = listOf(
                Triple(File(downloadRoot, "211_wf_176_jb.watch"), "176jb f Copy", 10),
                Triple(File(downloadRoot, "250_wf_215_portugues_jb.watch"), "wf376jb  portugus  Copy", 27),
            )
            assumeTrue("Local protected .watch corpus is not available", corpus.all { it.first.isFile })
            val loader = WatchArchiveInstaller()

            corpus.forEach { (archive, expectedName, expectedLayers) ->
                val manifest = loader.inspect(archive)
                assertTrue(manifest.protected)
                assertTrue(manifest.protectedDefinitionEntry != null)

                val installRoot = File(root, archive.nameWithoutExtension)
                val installed = loader.prepare(archive, installRoot)
                val reloaded = loader.prepare(archive, installRoot)

                assertEquals(expectedName, installed.definition.name)
                assertEquals(expectedLayers, installed.definition.layers.size)
                assertEquals(expectedLayers, reloaded.definition.layers.size)
            }
        }

    @Test
    fun protectedDefinitionStillRejectsDoctypeAfterBase64Decoding() =
        withTemporaryDirectory { root ->
            val archive = File(root, "protected-xxe.watch")
            writeZip(
                archive,
                "watch.xml" to "<Watch protection=\"y\" />".toByteArray(),
                "watch.pxml" to encodeProtectedXml(
                    "<!DOCTYPE Watch [<!ENTITY x SYSTEM \"file:///not-allowed\">]>".toByteArray() +
                        "<Watch name=\"&x;\"/>".toByteArray(),
                ),
            )

            assertArchiveFailure(WatchArchiveFailureReason.INVALID_WATCH_XML) {
                WatchArchiveInstaller().install(archive, File(root, "installed"))
            }
        }

    @Test
    fun rejectsProtectedImageThatOnlyHasAValidHeader() = withTemporaryDirectory { root ->
        val archive = File(root, "truncated-protected-image.watch")
        writeZip(
            archive,
            "watch.xml" to SYNTHETIC_SHELL_XML.toByteArray(),
            "watch.pxml" to encodeProtectedXml(SYNTHETIC_PROTECTED_XML.toByteArray()),
            "images/.face.ppng" to xorWatchmaker(PNG_HEADER + byteArrayOf(1, 2, 3, 4)),
        )

        assertArchiveFailure(WatchArchiveFailureReason.INVALID_PROTECTED_ASSET) {
            WatchArchiveInstaller().install(archive, File(root, "installed"))
        }
        assertTrue(
            File(root, "installed").listFiles().orEmpty().none { it.name.startsWith("watch_") },
        )
    }

    @Test
    fun installsSafeArchiveWhenProviderCannotDisableDoctype() = withTemporaryDirectory { root ->
        val archive = File(root, "fallback.watch")
        writeZip(archive, "watch.xml" to FORMAT_ONE_XML.toByteArray())

        val installed = withUnsupportedDoctypeFeature {
            WatchArchiveInstaller().install(archive, File(root, "installed"))
        }

        assertEquals("Simple", installed.definition.name)
        assertTrue(installed.watchXml.isFile)
    }

    @Test
    fun contentAddressedInstallIsIdempotentAndDoesNotReplaceAnotherFace() =
        withTemporaryDirectory { root ->
            val firstArchive = File(root, "first.watch")
            val secondArchive = File(root, "second.watch")
            writeZip(firstArchive, "watch.xml" to FORMAT_ONE_XML.toByteArray())
            writeZip(
                secondArchive,
                "watch.xml" to FORMAT_ONE_XML.replace("Simple", "Second").toByteArray(),
            )
            var now = 1_000L
            val installer = WatchArchiveInstaller(nowEpochMillis = { now })
            val installRoot = File(root, "installed")

            val first = installer.install(firstArchive, installRoot)
            now = 2_000L
            val same = installer.install(firstArchive, installRoot)
            now = 3_000L
            val second = installer.install(secondArchive, installRoot)

            assertEquals(first.stableId, same.stableId)
            assertTrue(first.rootDirectory.canonicalFile == same.rootDirectory.canonicalFile)
            assertTrue(first.stableId != second.stableId)
            assertEquals("Simple", first.definition.name)
            assertEquals("Second", second.definition.name)
            assertEquals(1_000L, first.installedAtEpochMs)
            assertEquals(1_000L, same.installedAtEpochMs)
            assertEquals(3_000L, second.installedAtEpochMs)
        }

    @Test
    fun nestedWatchXmlKeepsAssetsOutsideItsParentDirectory() =
        withTemporaryDirectory { root ->
            val archive = File(root, "nested.watch")
            writeZip(
                archive,
                "face/watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "face/preview.jpg" to byteArrayOf(1, 2, 3),
                "shared/fonts/digits.ttf" to byteArrayOf(4, 5, 6),
            )

            val installed = WatchArchiveInstaller().install(archive, File(root, "installed"))

            assertEquals("face/watch.xml", installed.watchXml.relativeTo(installed.rootDirectory).invariantSeparatorsPath)
            assertEquals("face/preview.jpg", installed.preview?.relativeTo(installed.rootDirectory)?.invariantSeparatorsPath)
            assertTrue(File(installed.rootDirectory, "shared/fonts/digits.ttf").isFile)
        }

    @Test
    fun reportsTypedFailureWhenInstallRootIsAFile() = withTemporaryDirectory { root ->
        val archive = File(root, "simple.watch")
        writeZip(archive, "watch.xml" to FORMAT_ONE_XML.toByteArray())
        val installRoot = File(root, "installed").apply { writeText("not a directory") }

        assertArchiveFailure(WatchArchiveFailureReason.INSTALL_FAILED) {
            WatchArchiveInstaller().install(archive, installRoot)
        }
    }

    @Test
    fun rejectsPathTraversalBeforeAnythingIsExtracted() = withTemporaryDirectory { root ->
        val archive = File(root, "escape.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "../outside.txt" to byteArrayOf(1),
        )

        assertArchiveFailure(WatchArchiveFailureReason.INVALID_ENTRY_NAME) {
            WatchArchiveInstaller().install(archive, File(root, "installed"))
        }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateEntriesForWindowsFilesystems() =
        withTemporaryDirectory { root ->
            val archive = File(root, "duplicate.watch")
            writeZip(
                archive,
                "watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "WATCH.XML" to FORMAT_ONE_XML.toByteArray(),
            )

            assertArchiveFailure(WatchArchiveFailureReason.DUPLICATE_ENTRY) {
                WatchArchiveValidator().inspect(archive)
            }
        }

    @Test
    fun rejectsFileAndDirectoryPathConflictsBeforeExtraction() =
        withTemporaryDirectory { root ->
            val archive = File(root, "path-conflict.watch")
            writeZip(
                archive,
                "watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "images" to byteArrayOf(1),
                "images/hand.png" to byteArrayOf(2),
            )

            assertArchiveFailure(WatchArchiveFailureReason.DUPLICATE_ENTRY) {
                WatchArchiveValidator().inspect(archive)
            }
        }

    @Test
    fun rejectsMissingWatchXml() = withTemporaryDirectory { root ->
        val archive = File(root, "missing.watch")
        writeZip(archive, "preview.jpg" to byteArrayOf(1))

        assertArchiveFailure(WatchArchiveFailureReason.MISSING_WATCH_XML) {
            WatchArchiveValidator().inspect(archive)
        }
    }

    @Test
    fun rejectsDeclaredOrStreamingExpansionOverBudget() = withTemporaryDirectory { root ->
        val archive = File(root, "large.watch")
        writeZip(
            archive,
            "watch.xml" to FORMAT_ONE_XML.toByteArray(),
            "images/large.bin" to ByteArray(4_096) { 7 },
        )
        val limits = WatchArchiveLimits(
            maximumArchiveBytes = 1_000_000,
            maximumImageBytes = 1_024,
            maximumExpandedBytes = 2_048,
            maximumCompressionRatio = 10_000.0,
        )

        assertArchiveFailure(WatchArchiveFailureReason.ENTRY_TOO_LARGE) {
            WatchArchiveValidator(limits).inspect(archive)
        }
    }

    @Test
    fun appliesPerResourceLimitsInsteadOfTheOldGlobalTwentyFourMiBLimit() =
        withTemporaryDirectory { root ->
            val archive = File(root, "typed-limits.watch")
            val fontBytes = ByteArray(4_096).apply {
                this[0] = 0
                this[1] = 1
                this[2] = 0
                this[3] = 0
            }
            writeZip(
                archive,
                "watch.xml" to FORMAT_ONE_XML.toByteArray(),
                "fonts/large.ttf" to fontBytes,
            )
            val limits = WatchArchiveLimits(
                maximumArchiveBytes = 1_000_000,
                maximumXmlOrScriptBytes = 1_024,
                maximumFontBytes = 8_192,
                maximumImageBytes = 2_048,
                maximumMediaBytes = 16_384,
                maximumOtherFileBytes = 2_048,
                maximumExpandedBytes = 32_768,
                maximumCompressionRatio = 10_000.0,
            )

            val manifest = WatchArchiveValidator(limits).inspect(archive)

            assertEquals(
                WatchArchiveEntryKind.FONT,
                manifest.entries.single { it.normalizedName.endsWith(".ttf") }.kind,
            )
        }

    @Test
    fun rejectsXmlThatExceedsTheXmlAndScriptBudget() = withTemporaryDirectory { root ->
        val archive = File(root, "large-xml.watch")
        writeZip(
            archive,
            "watch.xml" to ("<Watch name=\"x\">" + " ".repeat(4_096) + "</Watch>").toByteArray(),
        )
        val limits = WatchArchiveLimits(
            maximumArchiveBytes = 1_000_000,
            maximumXmlOrScriptBytes = 1_024,
            maximumExpandedBytes = 32_768,
            maximumCompressionRatio = 10_000.0,
        )

        assertArchiveFailure(WatchArchiveFailureReason.ENTRY_TOO_LARGE) {
            WatchArchiveValidator(limits).inspect(archive)
        }
    }

    @Test
    fun parserPreservesStudioHierarchyThemesAndUnknownAttributes() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply { writeText(FORMAT_TWO_XML) }

            val definition = WatchmakerDefinitionParser().parse(xml)

            assertEquals(WatchmakerDefinitionVersion.FORMAT_2, definition.version)
            assertEquals("Ocean", definition.themes.single().name)
            assertEquals("group-1", definition.layers[1].parentId)
            assertEquals("kept", definition.layers[1].attributes["future_attr"])
            assertTrue(definition.unsupportedLayerTypes.isEmpty())
        }

    @Test
    fun parserAcceptsCaseVariantsUsedByCommunityExporters() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    """
                    <watch build_source="studio" name="case-variant">
                      <themesets><themeset name="Ocean" /></themesets>
                      <layer id="text-1" type="text" />
                    </watch>
                    """.trimIndent(),
                )
            }

            val definition = WatchmakerDefinitionParser().parse(xml)

            assertEquals("Ocean", definition.themes.single().name)
            assertEquals("text", definition.layers.single().type)
        }

    @Test
    fun parserDoesNotAssumeMissingOrUnknownShapeIsRound() = withTemporaryDirectory { root ->
        val missing = File(root, "missing-shape.xml").apply {
            writeText("<Watch name=\"Missing\" />")
        }
        val unfamiliar = File(root, "unfamiliar-shape.xml").apply {
            writeText("<Watch name=\"Future\" shape=\"hexagon\" />")
        }

        assertEquals(WatchFaceShape.UNKNOWN, WatchmakerDefinitionParser().parse(missing).shape)
        assertEquals(WatchFaceShape.UNKNOWN, WatchmakerDefinitionParser().parse(unfamiliar).shape)
    }

    @Test
    fun compatibilityReportAcceptsSafeDynamicMathButRejectsScripts() =
        withTemporaryDirectory { root ->
            val xml = File(root, "expressions.xml").apply {
                writeText(
                    """
                    <Watch name="Expressions">
                      <Layer type="text" text="{dh23z}:{dmz}" />
                      <Layer type="ring" rotation="{blp}*36/100" />
                      <Layer type="image" opacity="{charging} == 1 and 100 or 15" />
                      <Layer type="image_tap" action="script:var_chime=(var_chime+1)%2" />
                    </Watch>
                    """.trimIndent(),
                )
            }

            val unsupported = WatchmakerDefinitionParser().parse(xml)
                .compatibilityReport
                .unsupportedExpressions

            assertEquals(
                setOf(
                    "script:var_chime=(var_chime+1)%2",
                ),
                unsupported,
            )
            assertFalse("{dh23z}:{dmz}" in unsupported)
        }

    @Test
    fun compatibilityReportDoesNotClaimUnimplementedLayerFamilies() =
        withTemporaryDirectory { root ->
            val xml = File(root, "unsupported-layers.xml").apply {
                writeText(
                    """
                    <Watch name="Unsupported layers">
                      <Layer type="chart" />
                      <Layer type="video" />
                      <Layer type="map" />
                      <Layer type="svg" />
                      <Layer type="image_tap" />
                      <Layer type="image_gif" />
                      <Layer type="series" />
                      <Layer type="calendar" />
                    </Watch>
                    """.trimIndent(),
                )
            }

            // video 已经能真正播放（Stage 分段 + TextureView），离屏与降级路径
            // 走静态首帧；map 仍然缺一个实时地图桥。
            assertEquals(
                setOf("map"),
                WatchmakerDefinitionParser().parse(xml).unsupportedLayerTypes,
            )
        }

    @Test
    fun parserAcceptsSafeXmlWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply { writeText(FORMAT_ONE_XML) }

            val definition = withUnsupportedDoctypeFeature {
                WatchmakerDefinitionParser().parse(xml)
            }

            assertEquals("Simple", definition.name)
            assertEquals(listOf("image", "text"), definition.layers.map { it.type })
        }

    @Test
    fun parserStillRejectsDoctypeWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText("<!DOCTYPE Watch SYSTEM \"file:///not-allowed\"><Watch name=\"unsafe\"/>")
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserStillRejectsEntityDeclarationWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText("<!ENTITY secret SYSTEM \"file:///not-allowed\"><Watch name=\"unsafe\"/>")
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ENTITY"))
        }

    @Test
    fun parserDoesNotExpandInternalEntityWhenProviderCannotDisableDoctype() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    "<!DOCTYPE Watch [<!ENTITY secret \"expanded\">]>" +
                        "<Watch name=\"&secret;\"/>",
                )
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserRejectsEbcDicXmlBeforeFallbackProviderCanExpandEntities() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml")
            xml.writeBytes(
                (
                    "<?xml version=\"1.0\" encoding=\"Cp037\"?><!DOCTYPE Watch " +
                        "[<!ENTITY secret \"expanded\">]><Watch name=\"&secret;\"/>"
                    ).toByteArray(Charset.forName("IBM037")),
            )

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("encoding"))
        }

    @Test
    fun parserRejectsUtf16DoctypeBeforeFallbackParserRuns() =
        withTemporaryDirectory { root ->
            val xml = File(root, "watch.xml").apply {
                writeText(
                    "<!DOCTYPE Watch><Watch name=\"unsafe\"/>",
                    charset("UTF-16LE"),
                )
            }

            val result = runCatching {
                withUnsupportedDoctypeFeature { WatchmakerDefinitionParser().parse(xml) }
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DOCTYPE"))
        }

    @Test
    fun parserKeepsStudioLayersAndPromotesMalformedParentsToRoots() =
        withTemporaryDirectory { root ->
        val xml = File(root, "watch.xml").apply {
            writeText(
                """
                <Watch build_source="studio" name="cycle">
                  <Layer id="valid" type="image" />
                  <Layer id="missing" parentId="absent" type="image" />
                  <Layer id="self" parentId="self" type="group" />
                  <Layer id="a" parentId="b" type="group" />
                  <Layer id="b" parentId="a" type="group" />
                </Watch>
                """.trimIndent(),
            )
        }

        val definition = WatchmakerDefinitionParser().parse(xml)

        assertEquals(5, definition.layers.size)
        assertEquals(
            listOf(null, null, null, null, null),
            resolveWatchLayerParentIndices(definition.layers),
        )
    }

    @Test
    fun parserFindsLayersInsideStudioWrapperElements() = withTemporaryDirectory { root ->
        val xml = File(root, "watch.xml").apply {
            writeText(
                """
                <Watch build_source="studio" name="wrapped">
                  <Layers>
                    <Layer id="dial" type="image" path="dial.png" />
                    <Folder>
                      <Layer id="time" type="text" text="{dh23z}:{dmz}" />
                    </Folder>
                  </Layers>
                </Watch>
                """.trimIndent(),
            )
        }

        val definition = WatchmakerDefinitionParser().parse(xml)

        assertEquals(listOf("dial", "time"), definition.layers.map { it.id })
    }

    @Test
    fun parserRejectsDoctypeAndExternalEntities() = withTemporaryDirectory { root ->
        val xml = File(root, "watch.xml").apply {
            writeText(
                """
                <!DOCTYPE Watch [<!ENTITY secret SYSTEM "file:///not-allowed">]>
                <Watch name="&secret;"><Layer type="text" text="x" /></Watch>
                """.trimIndent(),
            )
        }

        assertTrue(runCatching { WatchmakerDefinitionParser().parse(xml) }.isFailure)
    }

    @Test
    fun parserRejectsXIncludeAndExternalSchemaReferences() = withTemporaryDirectory { root ->
        val xInclude = File(root, "xinclude.xml").apply {
            writeText(
                "<Watch xmlns:xi=\"http://www.w3.org/2001/XInclude\">" +
                    "<xi:include href=\"file:///not-allowed\"/>" +
                    "</Watch>",
            )
        }
        val schema = File(root, "schema.xml").apply {
            writeText(
                "<Watch xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                    "xsi:noNamespaceSchemaLocation=\"https://not-allowed/schema.xsd\"/>",
            )
        }

        assertTrue(runCatching { WatchmakerDefinitionParser().parse(xInclude) }.isFailure)
        assertTrue(runCatching { WatchmakerDefinitionParser().parse(schema) }.isFailure)
    }

    private fun assertArchiveFailure(
        reason: WatchArchiveFailureReason,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected WatchArchiveException($reason)")
        } catch (error: WatchArchiveException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeSyntheticProtectedFixture(file: File) {
        writeZip(
            file,
            "watch.xml" to SYNTHETIC_SHELL_XML.toByteArray(),
            "watch.pxml" to encodeProtectedXml(SYNTHETIC_PROTECTED_XML.toByteArray()),
            "images/.face.ppng" to xorWatchmaker(SYNTHETIC_PNG_BYTES),
            "images/.photo.pjpg" to xorWatchmaker(SYNTHETIC_JPEG_BYTES),
        )
    }

    private fun writeLegacyProtectedFixture(directory: File) {
        File(directory, "watch.xml").writeText(SYNTHETIC_SHELL_XML)
        File(directory, "watch.pxml").writeBytes(
            encodeProtectedXml(SYNTHETIC_PROTECTED_XML.toByteArray()),
        )
        File(directory, "images/.face.ppng").apply {
            parentFile.mkdirs()
            writeBytes(xorWatchmaker(SYNTHETIC_PNG_BYTES))
        }
        File(directory, "images/.photo.pjpg").apply {
            parentFile.mkdirs()
            writeBytes(xorWatchmaker(SYNTHETIC_JPEG_BYTES))
        }
    }

    private fun encodeProtectedXml(bytes: ByteArray): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return encoded.map { character ->
            when (character) {
                'D' -> 'g'
                'g' -> 'D'
                '4' -> 'L'
                'L' -> '4'
                else -> character
            }
        }.joinToString("").toByteArray()
    }

    private fun xorWatchmaker(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        bytes.forEachIndexed { index, byte ->
            output.write(byte.toInt() xor WATCHMAKER_XOR_KEY[index % WATCHMAKER_XOR_KEY.size].toInt())
        }
        return output.toByteArray()
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flue-watch-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        val JPEG_HEADER = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val WATCHMAKER_XOR_KEY = "SWHn-".toByteArray()
        val SYNTHETIC_PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAAXNSR0IArs4c6QAAAARnQU1B" +
                "AACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAVSURBVBhXY/jPAEQNIIrhPxD8/w8A" +
                "Q9QJeKxchO4AAAAASUVORK5CYII=",
        )
        val SYNTHETIC_JPEG_BYTES = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof" +
                "Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwh" +
                "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAAR" +
                "CAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
                "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK" +
                "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
                "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl" +
                "5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
                "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYk" +
                "NOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
                "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk" +
                "5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD1Dwj4c0O58F6FPPo2nSzS6dbvJJJaozOx" +
                "jUkkkZJJ70UUVwVfjfqeXW/iS9Wf/9k=",
        )
        const val SYNTHETIC_SHELL_XML = "<Watch name=\"Shell\" protection=\"y\" />"
        val SYNTHETIC_PROTECTED_XML =
            """
            <Watch name="Protected" shape="S" protection="y">
              <Layer type="image" path=".face.ppng" width="512" height="512" />
              <Layer type="text" text="{dh23z}:{dmz}" />
            </Watch>
            """.trimIndent()
        val FORMAT_ONE_XML =
            """
            <Watch name="Simple" shape="R" bg_color="000000">
              <Layer type="image" path=".hand.png" width="32" height="128" rotation="{drm}" />
              <Layer type="text" text="{dh24z}:{dmz}" text_size="32" color="ffffff" />
            </Watch>
            """.trimIndent()

        val FORMAT_TWO_XML =
            """
            <Watch build_source="studio" name="Studio" shape="R" ucolor_default="12aff6">
              <ThemeSets>
                <ThemeSet name="Ocean" ucolor="12aff6" ucolor2="1842f8" ucolor3="641ffa" />
              </ThemeSets>
              <Layer id="group-1" type="group" name="Hands" />
              <Layer id="hand-1" parentId="group-1" type="image" path="hand.png" future_attr="kept">
                <Ambient opacity="60" />
              </Layer>
            </Watch>
            """.trimIndent()
    }
}
