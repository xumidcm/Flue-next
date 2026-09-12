package com.flue.launcher.watchface.agent.workspace

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class ShellCommandRequest(
    val workspaceId: String,
    val command: String,
    val timeoutSeconds: Long = 60L,
    val maxOutputBytes: Long = 64L * 1024L,
    val dryRun: Boolean = false,
    val runId: String? = null,
)

enum class BashApproval {
    ALLOW,
    DENY,
    REQUIRE_CONFIRMATION,
}

fun interface BashApprovalStrategy {
    fun decide(request: ShellCommandRequest): BashApproval
}

data class ProcessGroupSpec(
    val runId: String,
    val workspaceId: String,
    val rootfs: Path,
    val home: Path,
    val workingDirectory: Path,
    val faceMirrorDirectory: Path? = null,
    val command: String,
    val timeoutSeconds: Long,
    val maxOutputBytes: Long,
)

interface CancellableProcessGroup {
    val runId: String
    fun cancel()
    fun await(): ShellRunResult
}

fun interface CancellableProcessGroupRunner {
    fun start(spec: ProcessGroupSpec): CancellableProcessGroup
}

sealed interface ShellRunResult {
    data class DryRun(val workspaceId: String, val command: String, val workingDirectory: Path) : ShellRunResult
    data class Completed(
        val runId: String,
        val exitCode: Int,
        val stdout: String,
        val truncated: Boolean,
    ) : ShellRunResult
    data class RootfsNotInstalled(val workspaceId: String) : ShellRunResult
    data class WorkspaceNotFound(val workspaceId: String) : ShellRunResult
    data class RuntimeUnavailable(val workspaceId: String) : ShellRunResult
    data class InvalidRequest(val reason: String) : ShellRunResult
    data object RejectedApproval : ShellRunResult
    data object AwaitingApproval : ShellRunResult
}

interface ProotShellRunner {
    fun run(request: ShellCommandRequest): ShellRunResult
    fun cancel(runId: String): Boolean
    fun capability(workspaceId: String): WorkspaceCapability
}

fun androidProotCommand(
    spec: ProcessGroupSpec,
    nativeLibraryDir: Path,
    networkAllowed: Boolean,
    kernelFsMounts: List<String> = listOf("/dev", "/proc", "/sys")
        .filter { Files.exists(Paths.get(it)) },
): List<String> = buildList {
    add(nativeLibraryDir.resolve("libproot_exec.so").toString())
    add("--root-id")
    add("--link2symlink")
    add("--kill-on-exit")
    add("-r")
    add(spec.rootfs.toString())
    add("-w")
    add("/workspace")
    add("-b")
    add("${spec.workingDirectory}:/workspace")
    spec.faceMirrorDirectory?.let { face ->
        add("-b")
        add("$face:/workspace/face")
    }
    add("-b")
    add("${spec.home}:/root")
    kernelFsMounts.forEach { kernelPath ->
        add("-b")
        add(kernelPath)
    }
    if (networkAllowed && Files.isRegularFile(Paths.get("/etc/resolv.conf"))) {
        add("-b")
        add("/etc/resolv.conf:/etc/resolv.conf")
    }
    add("/usr/bin/env")
    add("-i")
    add("HOME=/root")
    add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    add("TERM=xterm-256color")
    add("LANG=C.UTF-8")
    add("LC_ALL=C.UTF-8")
    add("CI=true")
    add("NO_COLOR=1")
    add("PAGER=cat")
    add("FLUE_NETWORK_ALLOWED=${if (networkAllowed) 1 else 0}")
    add("/bin/bash")
    add("-l")
    add("-c")
    add("cd -- \"\$1\" && eval \"\$2\"")
    add("flue")
    add("/workspace")
    add(spec.command)
}

/** Verifies the candidate Rootfs through the same bundled Proot path used by the terminal. */
class AndroidRootfsSelfChecker(
    nativeLibraryDir: Path,
    private val runner: CancellableProcessGroupRunner =
        AndroidProotProcessGroupRunner(nativeLibraryDir) { false },
) : RootfsSelfChecker {
    override fun check(linux: Path): RootfsSelfCheck {
        val workspaceRoot = linux.parent ?: return failedSelfCheck(output = "invalid_rootfs_parent")
        val home = workspaceRoot.resolve("home")
        val working = workspaceRoot.resolve("workspace")
        Files.createDirectories(home)
        Files.createDirectories(working)
        val result = try {
            runner.start(
                ProcessGroupSpec(
                    runId = "rootfs-check-${UUID.randomUUID()}",
                    workspaceId = "rootfs-check",
                    rootfs = linux,
                    home = home,
                    workingDirectory = working,
                    command = "test -x /bin/bash && command -v bash >/dev/null || exit 120; " +
                        "for tool in python3 apt-get yum pip3; do " +
                        "if command -v \"${'$'}tool\" >/dev/null 2>&1; then printf 'FLUE_%s=1\\n' \"${'$'}(printf '%s' \"${'$'}tool\" | tr '[:lower:]-' '[:upper:]_')\"; " +
                        "else printf 'FLUE_%s=0\\n' \"${'$'}(printf '%s' \"${'$'}tool\" | tr '[:lower:]-' '[:upper:]_')\"; fi; done",
                    timeoutSeconds = 20L,
                    maxOutputBytes = 16L * 1024L,
                ),
            ).await()
        } catch (failure: RuntimeException) {
            return failedSelfCheck(output = "runner_error=${failure.javaClass.simpleName}")
        } catch (failure: IOException) {
            return failedSelfCheck(output = "runner_error=${failure.javaClass.simpleName}")
        }
        if (result !is ShellRunResult.Completed) {
            return failedSelfCheck(output = "runner_result=${result.javaClass.simpleName}")
        }
        val diagnostic = sanitizeRootfsSelfCheckOutput(result.stdout)
        if (result.exitCode != 0) {
            return failedSelfCheck(
                exitCode = result.exitCode,
                output = diagnostic.text,
                truncated = result.truncated || diagnostic.truncated,
            )
        }
        return RootfsSelfCheck(
            bashAvailable = true,
            pythonAvailable = result.stdout.contains("FLUE_PYTHON=1"),
            aptAvailable = result.stdout.contains("FLUE_APT_GET=1"),
            yumAvailable = result.stdout.contains("FLUE_YUM=1"),
            pipAvailable = result.stdout.contains("FLUE_PIP3=1"),
        )
    }

    private fun failedSelfCheck(
        exitCode: Int? = null,
        output: String = "",
        truncated: Boolean = false,
    ): RootfsSelfCheck = RootfsSelfCheck(
        bashAvailable = false,
        pythonAvailable = false,
        failure = RootfsSelfCheckFailure(
            stage = RootfsInstallStage.SELF_CHECKING,
            command = ROOTFS_SELF_CHECK_COMMAND_LABEL,
            exitCode = exitCode,
            output = output,
            truncated = truncated,
        ),
    )
}

private data class SanitizedRootfsOutput(val text: String, val truncated: Boolean)

private fun sanitizeRootfsSelfCheckOutput(raw: String): SanitizedRootfsOutput {
    val normalized = buildString(raw.length) {
        raw.forEach { character ->
            append(if (character == '\n' || character == '\r' || character == '\t' || !character.isISOControl()) character else '?')
        }
    }
    val redacted = ROOTFS_SECRET_LINE.replace(normalized) { match ->
        val key = match.groupValues[1].trim().replace(Regex("\\s+"), "_")
        "$key=<redacted>"
    }
    val truncated = redacted.length > ROOTFS_DIAGNOSTIC_LIMIT
    return SanitizedRootfsOutput(
        text = if (truncated) redacted.take(ROOTFS_DIAGNOSTIC_LIMIT) else redacted,
        truncated = truncated,
    )
}

private const val ROOTFS_DIAGNOSTIC_LIMIT = 2_048
private val ROOTFS_SECRET_LINE = Regex(
    pattern = "(?im)^\\s*(authorization|proxy[-_ ]?authorization|api[-_ ]?key|access[-_ ]?token|token|secret|password)\\s*[:=].*$",
)

/** Starts the bundled Android proot executable without exposing Android app storage as a bind mount. */
class AndroidProotProcessGroupRunner(
    private val nativeLibraryDir: Path,
    private val networkAllowed: () -> Boolean,
) : CancellableProcessGroupRunner {
    override fun start(spec: ProcessGroupSpec): CancellableProcessGroup {
        val executable = nativeLibraryDir.resolve("libproot_exec.so")
        val loader = nativeLibraryDir.resolve("libproot_loader.so")
        require(Files.isRegularFile(executable)) { "Bundled proot executable is unavailable" }
        require(Files.isRegularFile(loader)) { "Bundled proot loader is unavailable" }

        val tempDirectory = spec.home.resolve(".proot-tmp-${spec.runId}")
        Files.createDirectories(tempDirectory)
        val process = ProcessBuilder(androidProotCommand(spec, nativeLibraryDir, networkAllowed()))
            .directory(spec.workingDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.toString()
                environment()["PROOT_TMP_DIR"] = tempDirectory.toString()
                environment()["TMPDIR"] = tempDirectory.toString()
            }
            .start()
        return AndroidProotProcessGroup(spec, process, tempDirectory)
    }
}

private class AndroidProotProcessGroup(
    private val spec: ProcessGroupSpec,
    private val process: Process,
    private val tempDirectory: Path,
) : CancellableProcessGroup {
    private val outputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flue-proot-${spec.runId}").apply { isDaemon = true }
    }
    private val output: Future<CapturedOutput> = outputExecutor.submit<CapturedOutput> {
        val maximum = spec.maxOutputBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val retained = ByteArrayOutputStream(minOf(maximum, 8_192))
        var truncated = false
        process.inputStream.use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = maximum - retained.size()
                if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                if (count > remaining) truncated = true
            }
        }
        CapturedOutput(retained.toString(Charsets.UTF_8.name()), truncated)
    }

    override val runId: String = spec.runId

    override fun cancel() {
        process.destroy()
        if (process.isAlive && !process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    override fun await(): ShellRunResult {
        val completedInTime = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
        if (!completedInTime) cancel()
        val captured = runCatching { output.get(5, TimeUnit.SECONDS) }
            .getOrElse { CapturedOutput("", truncated = true) }
        outputExecutor.shutdownNow()
        deleteExactTree(tempDirectory)
        val suffix = if (completedInTime) "" else "\n[Flue: command timed out]"
        return ShellRunResult.Completed(
            runId = runId,
            exitCode = if (completedInTime) process.exitValue() else 124,
            stdout = captured.text + suffix,
            truncated = captured.truncated,
        )
    }

    private data class CapturedOutput(val text: String, val truncated: Boolean)
}

private fun deleteExactTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/**
 * Deliberately has no process implementation by default. A future proot adapter
 * must be injected and is still constrained to the managed workspace paths.
 */
class SafeProotShellRunner(
    private val manager: WorkspaceManager,
    private val approvalStrategy: BashApprovalStrategy = WorkspaceToolPolicy(),
    private val processGroupRunner: CancellableProcessGroupRunner? = null,
    private val permissionProvider: (() -> WorkspacePermissionSettings)? = null,
) : ProotShellRunner {
    private val activeProcesses = ConcurrentHashMap<String, CancellableProcessGroup>()

    override fun run(request: ShellCommandRequest): ShellRunResult {
        if (request.command.isBlank()) return ShellRunResult.InvalidRequest("Command cannot be blank")
        if (request.timeoutSeconds <= 0L) return ShellRunResult.InvalidRequest("Timeout must be positive")
        if (request.maxOutputBytes <= 0L) return ShellRunResult.InvalidRequest("Output limit must be positive")
        val workspace = manager.get(request.workspaceId)
            ?: return ShellRunResult.WorkspaceNotFound(request.workspaceId)
        val capability = manager.capability(request.workspaceId)
        if (!capability.rootfsInstalled) return ShellRunResult.RootfsNotInstalled(request.workspaceId)
        if (!capability.bashAvailable) return ShellRunResult.RuntimeUnavailable(request.workspaceId)
        val approval = permissionProvider?.let { provider ->
            runCatching { provider().approvalStrategy().decide(request) }.getOrDefault(BashApproval.DENY)
        } ?: approvalStrategy.decide(request)
        when (approval) {
            BashApproval.DENY -> return ShellRunResult.RejectedApproval
            BashApproval.REQUIRE_CONFIRMATION -> return ShellRunResult.AwaitingApproval
            BashApproval.ALLOW -> Unit
        }
        if (request.dryRun) {
            return ShellRunResult.DryRun(request.workspaceId, request.command, workspace.paths.workspace)
        }
        val runner = processGroupRunner ?: return ShellRunResult.RuntimeUnavailable(request.workspaceId)
        val runId = request.runId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val process = runCatching {
            runner.start(
                ProcessGroupSpec(
                    runId = runId,
                    workspaceId = request.workspaceId,
                    rootfs = workspace.paths.linux,
                    home = workspace.paths.home,
                    workingDirectory = workspace.paths.workspace,
                    faceMirrorDirectory = workspace.paths.face,
                    command = request.command,
                    timeoutSeconds = request.timeoutSeconds,
                    maxOutputBytes = request.maxOutputBytes,
                ),
            )
        }.getOrElse { return ShellRunResult.RuntimeUnavailable(request.workspaceId) }
        activeProcesses[process.runId] = process
        return try {
            process.await()
        } finally {
            activeProcesses.remove(process.runId)
        }
    }

    override fun cancel(runId: String): Boolean = activeProcesses.remove(runId)?.let {
        it.cancel()
        true
    } ?: false

    override fun capability(workspaceId: String): WorkspaceCapability = manager.capability(workspaceId)
}
