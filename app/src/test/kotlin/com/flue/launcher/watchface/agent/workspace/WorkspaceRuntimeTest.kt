package com.flue.launcher.watchface.agent.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WorkspaceRuntimeTest {
    private lateinit var root: Path
    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("flue-workspace-test")
        manager = WorkspaceManager(root)
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `bundled proot executables are extracted so ProcessBuilder can execute them`() {
        val buildScript = sequenceOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
            File("../app/build.gradle.kts"),
        ).firstOrNull(File::isFile)?.readText() ?: error("app/build.gradle.kts not found")

        assertTrue(buildScript.contains("jniLibs {"))
        assertTrue(buildScript.contains("useLegacyPackaging = true"))
    }

    @Test
    fun `new workspace creates isolated managed directories and exposes capability`() {
        val workspace = manager.create("表盘实验", quotaBytes = 2_048L)

        assertTrue(workspace.id.isNotBlank())
        assertEquals("表盘实验", workspace.displayName)
        assertEquals(RootfsStatus.NOT_INSTALLED, workspace.rootfs.status)
        assertEquals(2_048L, workspace.quota.limitBytes)
        assertEquals(null, workspace.binding)
        assertEquals(
            WorkspaceCapability(
                workspaceId = workspace.id,
                rootfsInstalled = false,
                bashAvailable = false,
                pythonAvailable = false,
                reason = CapabilityReason.ROOTFS_NOT_INSTALLED,
            ),
            manager.capability(workspace.id),
        )
        assertTrue(Files.isDirectory(workspace.paths.linux))
        assertTrue(Files.isDirectory(workspace.paths.home))
        assertTrue(Files.isDirectory(workspace.paths.workspace))
    }

    @Test
    fun `backup cleanup failure after rootfs commit keeps the installed candidate`() {
        var cleanupAttempted = false
        var backupPath: Path? = null
        manager = WorkspaceManager(root) { backup ->
            cleanupAttempted = true
            backupPath = backup
            Files.deleteIfExists(backup.resolve("old-removed-before-failure.txt"))
            throw IOException("simulated partial backup cleanup failure")
        }
        val workspace = manager.create("rootfs-commit")
        Files.write(workspace.paths.linux.resolve("old-removed-before-failure.txt"), "old".toByteArray())
        Files.write(workspace.paths.linux.resolve("old-survivor.txt"), "old".toByteArray())
        manager.markRootfsInstalled(
            workspace.id,
            bashAvailable = true,
            pythonAvailable = false,
            version = "old",
        )
        val candidate = workspace.paths.root.resolve(".rootfs-install-test")
        Files.createDirectories(candidate.resolve("bin"))
        Files.write(candidate.resolve("bin/bash"), "new-bash".toByteArray())

        val installed = manager.replaceRootfs(
            workspaceId = workspace.id,
            stagingRootfs = candidate,
            bashAvailable = true,
            pythonAvailable = false,
            architecture = "x86_64",
            version = "new",
            archiveSha256 = "a".repeat(64),
        )

        assertTrue(cleanupAttempted)
        assertEquals("new", installed.rootfs.version)
        assertEquals("new", manager.get(workspace.id)?.rootfs?.version)
        assertEquals("new-bash", String(Files.readAllBytes(workspace.paths.linux.resolve("bin/bash"))))
        assertFalse(Files.exists(workspace.paths.linux.resolve("old-survivor.txt")))
        assertTrue(Files.exists(requireNotNull(backupPath).resolve("old-survivor.txt")))
    }

    @Test
    fun `binding is scoped by watch scope and can be changed`() {
        val first = manager.create("一号")
        val second = manager.create("二号")

        assertEquals(first.id, manager.bind("face-a", first.id).workspaceId)
        assertEquals(first.id, manager.bindingFor("face-a")?.workspaceId)
        assertEquals(second.id, manager.bind("face-a", second.id).workspaceId)
        assertEquals(second.id, manager.bindingFor("face-a")?.workspaceId)
        assertEquals(null, manager.bindingFor("face-b"))
    }

    @Test
    fun `workspace can be renamed and deletion clears every binding`() {
        val workspace = manager.create("旧名称")
        manager.bind("face-a", workspace.id)
        manager.bind("face-b", workspace.id)

        assertEquals("新名称", manager.rename(workspace.id, "新名称").displayName)
        assertTrue(manager.delete(workspace.id))
        assertEquals(null, manager.get(workspace.id))
        assertEquals(null, manager.bindingFor("face-a"))
        assertEquals(null, manager.bindingFor("face-b"))
        assertFalse(Files.exists(workspace.paths.root))
    }

    @Test
    fun `relative paths are sanitized and escaping paths are rejected`() {
        assertEquals("folder/file.txt", WorkspacePathPolicy.sanitizeRelative("folder\\file.txt"))
        assertEquals("folder/file.txt", WorkspacePathPolicy.sanitizeRelative("./folder/./file.txt"))

        listOf("../secret", "folder/../../secret", "/absolute", "C:\\secret", "", "folder/" + '\u0000' + "file")
            .forEach { path ->
                try {
                    WorkspacePathPolicy.sanitizeRelative(path)
                    fail("Expected path rejection for $path")
                } catch (_: WorkspacePathViolation) {
                    // expected
                }
            }
    }

    @Test
    fun `controlled import enforces path policy and quota`() {
        val workspace = manager.create("导入", quotaBytes = 4L)
        val source = root.resolve("source.txt")
        Files.write(source, "12345".toByteArray())

        try {
            manager.importFile(workspace.id, source, "../escape.txt")
            fail("Expected path rejection")
        } catch (_: WorkspacePathViolation) {
            // expected
        }

        val result = manager.importFile(workspace.id, source, "workspace/file.txt")
        assertEquals(ImportResult.QUOTA_EXCEEDED, result)
        assertFalse(Files.exists(workspace.paths.workspace.resolve("file.txt")))
    }

    @Test
    fun `workspace directory listing enters folders and deletion refreshes quota`() {
        val workspace = manager.create("文件", quotaBytes = 1_024L)
        Files.createDirectories(workspace.paths.workspace.resolve("src"))
        Files.write(workspace.paths.workspace.resolve("src/main.kt"), "hello".toByteArray())
        Files.write(workspace.paths.workspace.resolve("readme.md"), "readme".toByteArray())

        val rootEntries = manager.listDirectory(workspace.id)
        assertEquals(listOf("src", "readme.md"), rootEntries.map { it.name })
        assertTrue(rootEntries.first().isDirectory)
        assertEquals("src/main.kt", manager.listDirectory(workspace.id, "workspace/src").single().relativePath)
        assertTrue(manager.deleteWorkspaceEntry(workspace.id, "workspace/src/main.kt"))
        assertFalse(Files.exists(workspace.paths.workspace.resolve("src/main.kt")))
        assertTrue((manager.get(workspace.id)?.quota?.usedBytes ?: 0L) >= 6L)
    }

    @Test
    fun `workspace deletion rejects protected and escaping paths`() {
        val workspace = manager.create("保护", quotaBytes = 1_024L)
        Files.write(workspace.paths.workspace.resolve("safe.txt"), byteArrayOf(1))
        listOf("linux", "workspace", "../workspace/safe.txt").forEach { path ->
            try {
                manager.deleteWorkspaceEntry(workspace.id, path)
                fail("Expected rejection for $path")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `rootfs manifest verifier checks sha signature and abi`() {
        val archive = root.resolve("rootfs.tar.gz")
        Files.write(archive, "rootfs".toByteArray())
        val digest = sha256(archive)
        val manifest = RootfsManifest(
            architecture = "x86_64",
            version = "2026.09",
            archiveSha256 = digest,
            archiveSizeBytes = Files.size(archive),
            expandedSizeBytes = 10L,
            signature = "sig",
        )
        val verifier = RootfsManifestVerifier { it.signature == "sig" }

        assertEquals(
            RootfsValidationResult.Valid(manifest),
            verifier.validate(manifest, archive, currentAbi = "x86_64"),
        )
        assertTrue(
            verifier.validate(manifest.copy(archiveSha256 = "0".repeat(64)), archive, "x86_64")
                is RootfsValidationResult.Invalid,
        )
        assertTrue(
            verifier.validate(manifest, archive, "arm64-v8a")
                is RootfsValidationResult.Invalid,
        )
        assertTrue(
            verifier.validate(manifest.copy(signature = "bad"), archive, "x86_64")
                is RootfsValidationResult.Invalid,
        )
    }

    @Test
    fun `default shell runner never executes and reports missing rootfs`() {
        val workspace = manager.create("命令", quotaBytes = 1_024L)
        val runner = SafeProotShellRunner(manager)

        val result = runner.run(
            ShellCommandRequest(
                workspaceId = workspace.id,
                command = "echo should-not-run",
                dryRun = false,
            ),
        )

        assertEquals(ShellRunResult.RootfsNotInstalled(workspace.id), result)
        assertTrue(runner.capability(workspace.id).bashAvailable.not())
    }

    @Test
    fun `dry run is available only after explicit approval`() {
        val workspace = manager.create("dry", quotaBytes = 1_024L)
        manager.markRootfsInstalled(workspace.id, bashAvailable = true, pythonAvailable = true)
        val denied = SafeProotShellRunner(
            manager = manager,
            approvalStrategy = BashApprovalStrategy { BashApproval.DENY },
        ).run(ShellCommandRequest(workspace.id, "rm -rf /", dryRun = true))
        assertEquals(ShellRunResult.RejectedApproval, denied)

        val allowed = SafeProotShellRunner(
            manager = manager,
            approvalStrategy = BashApprovalStrategy { BashApproval.ALLOW },
        ).run(ShellCommandRequest(workspace.id, "printf ok", dryRun = true))
        assertEquals(
            ShellRunResult.DryRun(workspace.id, "printf ok", workspace.paths.workspace),
            allowed,
        )
    }

    @Test
    fun `agent shell runner reads the latest persisted approval mode for every command`() {
        val workspace = manager.create("dynamic-policy", quotaBytes = 1_024L)
        manager.markRootfsInstalled(workspace.id, bashAvailable = true, pythonAvailable = false)
        var permissions = WorkspacePermissionSettings(approvalMode = BashApprovalMode.EACH_COMMAND)
        val runner = SafeProotShellRunner(
            manager = manager,
            permissionProvider = { permissions },
            processGroupRunner = CancellableProcessGroupRunner { spec ->
                object : CancellableProcessGroup {
                    override val runId: String = spec.runId
                    override fun cancel() = Unit
                    override fun await(): ShellRunResult = ShellRunResult.Completed(runId, 0, "ok", false)
                }
            },
        )

        assertEquals(
            ShellRunResult.AwaitingApproval,
            runner.run(ShellCommandRequest(workspace.id, "printf first")),
        )

        permissions = permissions.copy(approvalMode = BashApprovalMode.AUTOMATIC)

        assertTrue(runner.run(ShellCommandRequest(workspace.id, "printf second")) is ShellRunResult.Completed)
    }

    @Test
    fun `android proot command binds only the managed workspace and invokes bash`() {
        val workspace = manager.create("terminal")
        val nativeDir = root.resolve("native")
        Files.createDirectories(nativeDir)
        val spec = ProcessGroupSpec(
            runId = "run",
            workspaceId = workspace.id,
            rootfs = workspace.paths.linux,
            home = workspace.paths.home,
            workingDirectory = workspace.paths.workspace,
            command = "printf ok",
            timeoutSeconds = 60,
            maxOutputBytes = 1024,
        )

        val command = androidProotCommand(
            spec,
            nativeDir,
            networkAllowed = false,
            kernelFsMounts = listOf("/dev", "/proc", "/sys"),
        )

        assertEquals(nativeDir.resolve("libproot_exec.so").toString(), command.first())
        assertTrue(command.windowed(2).contains(listOf("-r", workspace.paths.linux.toString())))
        assertTrue(command.windowed(2).contains(listOf("-b", "${workspace.paths.workspace}:/workspace")))
        assertTrue(command.windowed(2).contains(listOf("-b", "/proc")))
        assertTrue(command.windowed(2).contains(listOf("-b", "/sys")))
        assertTrue(command.windowed(2).contains(listOf("-b", "/dev")))
        assertTrue(command.contains("/bin/bash"))
        assertEquals("printf ok", command.last())
        assertFalse(command.contains("/sdcard"))
    }

    @Test
    fun `android proot command binds the current face mirror and reports network`() {
        val workspace = manager.create("terminal")
        val nativeDir = root.resolve("native")
        Files.createDirectories(nativeDir)
        val spec = ProcessGroupSpec(
            runId = "run-face",
            workspaceId = workspace.id,
            rootfs = workspace.paths.linux,
            home = workspace.paths.home,
            workingDirectory = workspace.paths.workspace,
            faceMirrorDirectory = workspace.paths.face,
            command = "printf ok",
            timeoutSeconds = 60,
            maxOutputBytes = 1024,
        )

        val command = androidProotCommand(spec, nativeDir, networkAllowed = true, kernelFsMounts = emptyList())

        assertTrue(command.windowed(2).contains(listOf("-b", "${workspace.paths.face}:/workspace/face")))
        assertTrue(command.contains("FLUE_NETWORK_ALLOWED=1"))
    }

    @Test
    fun `face mirror sync read and path policy are revision safe`() {
        val workspace = manager.create("mirror")
        assertTrue(
            manager.syncFaceMirror(
                workspace.id,
                "<watch/>",
                "print('ok')",
                mapOf("images/hero.png" to byteArrayOf(1, 2), "preview.jpg" to byteArrayOf(3, 4)),
            ),
        )
        val snapshot = manager.readFaceMirror(workspace.id) ?: error("mirror missing")
        assertEquals("<watch/>", snapshot.watchXml)
        assertEquals("print('ok')", snapshot.globalScript)
        assertEquals(listOf("images/hero.png", "preview.jpg"), snapshot.resources)
        assertTrue(Files.isRegularFile(manager.resolveWorkspaceFile(workspace.id, "face/images/hero.png")))
        assertTrue(Files.isRegularFile(manager.resolveWorkspaceFile(workspace.id, "face/preview.jpg")))
        try {
            manager.resolveWorkspaceFile(workspace.id, "../other/secret")
            fail("path traversal must be rejected")
        } catch (_: WorkspacePathViolation) {
            // expected
        }
    }

    @Test
    fun `android rootfs self checker preserves actionable proot failure without secrets`() {
        var capturedSpec: ProcessGroupSpec? = null
        val fakeRunner = CancellableProcessGroupRunner { spec ->
            capturedSpec = spec
            object : CancellableProcessGroup {
                override val runId: String = spec.runId
                override fun cancel() = Unit
                override fun await(): ShellRunResult = ShellRunResult.Completed(
                    runId = runId,
                    exitCode = 127,
                    stdout = "Authorization=Bearer secret-token\n" + "proot failure ".repeat(400),
                    truncated = true,
                )
            }
        }
        val constructor = AndroidRootfsSelfChecker::class.java.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(
                arrayOf(Path::class.java, CancellableProcessGroupRunner::class.java),
            )
        } ?: throw AssertionError(
            "AndroidRootfsSelfChecker must allow the real proot path to be injected for verification",
        )
        constructor.isAccessible = true
        val checker = constructor.newInstance(root.resolve("native"), fakeRunner) as RootfsSelfChecker
        val candidate = root.resolve("candidate")
        Files.createDirectories(candidate)

        val result = checker.check(candidate)

        assertFalse(result.bashAvailable)
        assertTrue(capturedSpec?.command?.contains("test -x /bin/bash") == true)
        val failure = result.javaClass.getMethod("getFailure").invoke(result)
            ?: fail("failed self check must retain diagnostics")
        assertEquals(
            RootfsInstallStage.SELF_CHECKING,
            failure.javaClass.getMethod("getStage").invoke(failure),
        )
        assertEquals(
            "proot /bin/bash -lc <self-check>",
            failure.javaClass.getMethod("getCommand").invoke(failure),
        )
        assertEquals(127, failure.javaClass.getMethod("getExitCode").invoke(failure))
        assertEquals(true, failure.javaClass.getMethod("getTruncated").invoke(failure))
        val output = failure.javaClass.getMethod("getOutput").invoke(failure) as String
        assertFalse(output.contains("secret-token"))
        assertTrue(output.contains("<redacted>"))
        assertTrue(output.length <= 2_048)
    }

    @Test
    fun `process startup failures are reported as unavailable`() {
        val workspace = manager.create("startup")
        manager.markRootfsInstalled(workspace.id, bashAvailable = true, pythonAvailable = false)
        val runner = SafeProotShellRunner(
            manager = manager,
            approvalStrategy = BashApprovalStrategy { BashApproval.ALLOW },
            processGroupRunner = CancellableProcessGroupRunner { error("native proot missing") },
        )

        assertEquals(
            ShellRunResult.RuntimeUnavailable(workspace.id),
            runner.run(ShellCommandRequest(workspace.id, "printf ok")),
        )
    }

    @Test
    fun `caller supplied run id can be used to stop a manual terminal command`() {
        val workspace = manager.create("manual")
        manager.markRootfsInstalled(workspace.id, bashAvailable = true, pythonAvailable = false)
        var startedSpec: ProcessGroupSpec? = null
        val runner = SafeProotShellRunner(
            manager = manager,
            approvalStrategy = BashApprovalStrategy { BashApproval.ALLOW },
            processGroupRunner = CancellableProcessGroupRunner { spec ->
                startedSpec = spec
                object : CancellableProcessGroup {
                    override val runId: String = spec.runId
                    override fun cancel() = Unit
                    override fun await(): ShellRunResult = ShellRunResult.Completed(runId, 0, "ok", false)
                }
            },
        )

        runner.run(ShellCommandRequest(workspace.id, "printf ok", runId = "manual-run"))

        assertEquals("manual-run", startedSpec?.runId)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
