package com.flue.launcher.watchface.agent.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootfsInstallerTest {
    private lateinit var root: Path
    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("flue-rootfs-installer")
        manager = WorkspaceManager(root)
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `bash self check failure preserves old rootfs and returns actionable diagnostics`() {
        val workspace = manager.create("self-check")
        Files.createDirectories(workspace.paths.linux.resolve("bin"))
        Files.write(workspace.paths.linux.resolve("bin/bash"), "old-bash".toByteArray())
        manager.markRootfsInstalled(
            workspace.id,
            bashAvailable = true,
            pythonAvailable = false,
            version = "old",
        )
        val archive = tarGzip("bin/bash" to "new-bash")
        val installer = RootfsInstaller(
            manager = manager,
            currentAbi = { "x86_64" },
            selfChecker = RootfsSelfChecker { RootfsSelfCheck(false, false) },
        )

        val result = installer.install(
            workspace.id,
            manifestFor(archive),
            RootfsArchiveFormat.TAR_GZ,
            RootfsArchiveSource { ByteArrayInputStream(archive) },
        )

        assertTrue(result is RootfsInstallResult.Failed)
        val message = (result as RootfsInstallResult.Failed).message
        assertTrue(message.contains("stage=SELF_CHECKING"))
        assertTrue(message.contains("command=proot /bin/bash -lc <self-check>"))
        assertTrue(message.contains("exit=unavailable"))
        assertTrue(message.contains("truncated=false"))
        assertTrue(message.contains("output=<none>"))
        assertEquals(
            "old-bash",
            String(Files.readAllBytes(workspace.paths.linux.resolve("bin/bash")), Charsets.UTF_8),
        )
        assertEquals("old", manager.get(workspace.id)?.rootfs?.version)
    }

    @Test
    fun `verified gzip archive is installed and old rootfs is atomically replaced`() {
        val workspace = manager.create("rootfs")
        Files.write(workspace.paths.linux.resolve("old.txt"), "old".toByteArray())
        val archive = tarGzip(
            "./" to "",
            "bin/bash" to "bash",
            "usr/bin/python3" to "python",
            "etc/issue" to "Ubuntu",
        )
        val manifest = manifestFor(archive)
        val progress = mutableListOf<RootfsInstallStage>()
        val installer = RootfsInstaller(
            manager = manager,
            currentAbi = { "x86_64" },
            selfChecker = RootfsSelfChecker { linux ->
                RootfsSelfCheck(
                    bashAvailable = Files.isRegularFile(linux.resolve("bin/bash")),
                    pythonAvailable = Files.isRegularFile(linux.resolve("usr/bin/python3")),
                )
            },
        )

        val result = installer.install(
            workspaceId = workspace.id,
            manifest = manifest,
            format = RootfsArchiveFormat.TAR_GZ,
            source = RootfsArchiveSource { ByteArrayInputStream(archive) },
            onProgress = { progress += it.stage },
        )

        assertTrue(result is RootfsInstallResult.Installed)
        assertFalse(Files.exists(workspace.paths.linux.resolve("old.txt")))
        assertEquals(
            "Ubuntu",
            String(Files.readAllBytes(workspace.paths.linux.resolve("etc/issue")), Charsets.UTF_8),
        )
        assertTrue(manager.capability(workspace.id).bashAvailable)
        assertTrue(manager.capability(workspace.id).pythonAvailable)
        assertEquals("test-1", manager.get(workspace.id)?.rootfs?.version)
        assertTrue(progress.contains(RootfsInstallStage.VERIFYING))
        assertTrue(progress.contains(RootfsInstallStage.EXTRACTING))
        assertEquals(RootfsInstallStage.INSTALLED, progress.last())
    }

    @Test
    fun `hash mismatch preserves the previous usable rootfs`() {
        val workspace = manager.create("preserve")
        Files.createDirectories(workspace.paths.linux.resolve("bin"))
        Files.write(workspace.paths.linux.resolve("bin/bash"), "old-bash".toByteArray())
        manager.markRootfsInstalled(
            workspace.id,
            bashAvailable = true,
            pythonAvailable = false,
            version = "old",
        )
        val archive = tarGzip("bin/bash" to "new-bash")
        val manifest = manifestFor(archive).copy(archiveSha256 = "0".repeat(64))
        val installer = RootfsInstaller(manager, currentAbi = { "x86_64" })

        val result = installer.install(
            workspace.id,
            manifest,
            RootfsArchiveFormat.TAR_GZ,
            RootfsArchiveSource { ByteArrayInputStream(archive) },
        )

        assertEquals(
            RootfsInstallResult.Invalid(RootfsInstallError.SHA256_MISMATCH),
            result,
        )
        assertEquals(
            "old-bash",
            String(Files.readAllBytes(workspace.paths.linux.resolve("bin/bash")), Charsets.UTF_8),
        )
        assertEquals("old", manager.get(workspace.id)?.rootfs?.version)
    }

    @Test
    fun `archive path traversal is rejected without writing outside workspace`() {
        val workspace = manager.create("escape")
        val archive = tarGzip("../escaped.txt" to "nope")
        val installer = RootfsInstaller(manager, currentAbi = { "x86_64" })

        val result = installer.install(
            workspace.id,
            manifestFor(archive),
            RootfsArchiveFormat.TAR_GZ,
            RootfsArchiveSource { ByteArrayInputStream(archive) },
        )

        assertEquals(
            RootfsInstallResult.Invalid(RootfsInstallError.INVALID_ARCHIVE),
            result,
        )
        assertFalse(Files.exists(workspace.paths.root.resolve("escaped.txt")))
        assertEquals(RootfsStatus.NOT_INSTALLED, manager.get(workspace.id)?.rootfs?.status)
    }

    @Test
    fun `deferred hard link rejects a source reached through a symlinked parent`() {
        val workspace = manager.create("hard-link-source-parent")
        Files.write(workspace.paths.linux.resolve("outside-secret.txt"), "old-rootfs-secret".toByteArray())
        val archive = tarGzipEntries(
            TarTestEntry(name = "bin/bash", content = "new-bash"),
            TarTestEntry(name = "escape", type = '2', linkName = "../linux"),
            TarTestEntry(name = "leaked.txt", type = '1', linkName = "escape/outside-secret.txt"),
        )
        val installer = RootfsInstaller(manager, currentAbi = { "x86_64" })

        val result = installer.install(
            workspace.id,
            manifestFor(archive),
            RootfsArchiveFormat.TAR_GZ,
            RootfsArchiveSource { ByteArrayInputStream(archive) },
        )

        assertEquals(RootfsInstallResult.Invalid(RootfsInstallError.INVALID_ARCHIVE), result)
        assertEquals(
            "old-rootfs-secret",
            String(Files.readAllBytes(workspace.paths.linux.resolve("outside-secret.txt")), Charsets.UTF_8),
        )
        assertFalse(Files.exists(workspace.paths.linux.resolve("leaked.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `online catalog is pinned to the supported arm64 package`() {
        val online = RootfsCatalog.forAbi("arm64-v8a")

        requireNotNull(online)
        assertTrue(online.url.startsWith("https://cdimage.ubuntu.com/ubuntu-base/"))
        assertEquals("24.04.5", online.manifest.version)
        assertEquals(29_936_675L, online.manifest.archiveSizeBytes)
        assertEquals(
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            online.manifest.archiveSha256,
        )
        assertEquals(null, RootfsCatalog.forAbi("armeabi-v7a"))
    }

    @Test
    fun `hard link falls back to a safe file copy when android filesystem rejects links`() {
        val source = root.resolve("usr/bin/perl5.38.2")
        val target = root.resolve("usr/bin/perl")
        Files.createDirectories(source.parent)
        Files.write(source, "perl-binary".toByteArray())
        source.toFile().setExecutable(true, false)

        materializeRootfsHardLink(source, target) { _, _ ->
            throw IOException("hard links unsupported")
        }

        assertEquals("perl-binary", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertTrue(target.toFile().canExecute())
    }

    private fun manifestFor(archive: ByteArray): RootfsManifest = RootfsManifest(
        architecture = "x86_64",
        version = "test-1",
        archiveSha256 = sha256(archive),
        archiveSizeBytes = archive.size.toLong(),
        expandedSizeBytes = 1024L * 1024L,
        signature = "apk-pinned-test",
        requiredPaths = setOf("/bin/bash"),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tarGzip(vararg entries: Pair<String, String>): ByteArray = tarGzipEntries(
        *entries.map { (name, content) ->
            TarTestEntry(
                name = name,
                content = content,
                type = if (name.endsWith('/')) '5' else '0',
            )
        }.toTypedArray(),
    )

    private fun tarGzipEntries(vararg entries: TarTestEntry): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { gzip ->
            entries.forEach { entry ->
                val bytes = if (entry.type == '0') entry.content.toByteArray() else ByteArray(0)
                val header = ByteArray(512)
                putString(header, 0, 100, entry.name)
                putOctal(header, 100, 8, 0b111_101_101)
                putOctal(header, 108, 8, 0)
                putOctal(header, 116, 8, 0)
                putOctal(header, 124, 12, bytes.size.toLong())
                putOctal(header, 136, 12, 0)
                for (index in 148 until 156) header[index] = ' '.code.toByte()
                header[156] = entry.type.code.toByte()
                putString(header, 157, 100, entry.linkName)
                putString(header, 257, 6, "ustar")
                putString(header, 263, 2, "00")
                val checksum = header.sumOf { it.toInt() and 0xff }
                putChecksum(header, checksum)
                gzip.write(header)
                gzip.write(bytes)
                repeat(((512 - bytes.size % 512) % 512)) { gzip.write(0) }
            }
            gzip.write(ByteArray(1024))
        }
        return compressed.toByteArray()
    }

    private data class TarTestEntry(
        val name: String,
        val content: String = "",
        val type: Char = '0',
        val linkName: String = "",
    )

    private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
        value.toByteArray().take(length).forEachIndexed { index, byte -> target[offset + index] = byte }
    }

    private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        putString(target, offset, length, value.toString(8).padStart(length - 1, '0') + '\u0000')
    }

    private fun putChecksum(target: ByteArray, checksum: Int) {
        putString(target, 148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
    }
}
