package com.flue.launcher.watchface.agent.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class RootfsManifest(
    val architecture: String,
    val version: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val expandedSizeBytes: Long,
    val signature: String,
    val requiredPaths: Set<String> = setOf("/bin/bash", "/usr/bin/python3"),
)

sealed interface RootfsValidationResult {
    data class Valid(val manifest: RootfsManifest) : RootfsValidationResult
    data class Invalid(val reason: RootfsValidationError) : RootfsValidationResult
}

enum class RootfsValidationError {
    ARCHITECTURE_MISMATCH,
    UNSUPPORTED_ARCHITECTURE,
    ARCHIVE_NOT_FOUND,
    ARCHIVE_SIZE_MISMATCH,
    MALFORMED_SHA256,
    SHA256_MISMATCH,
    SIGNATURE_INVALID,
    INVALID_MANIFEST,
}

/** A signature verifier is injected so cryptographic policy stays out of workspace storage. */
fun interface RootfsManifestVerifier {
    fun verifySignature(manifest: RootfsManifest): Boolean

    fun validate(
        manifest: RootfsManifest,
        archive: Path,
        currentAbi: String,
        supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64"),
    ): RootfsValidationResult {
        if (manifest.architecture !in supportedAbis) {
            return RootfsValidationResult.Invalid(RootfsValidationError.UNSUPPORTED_ARCHITECTURE)
        }
        if (manifest.architecture != currentAbi) {
            return RootfsValidationResult.Invalid(RootfsValidationError.ARCHITECTURE_MISMATCH)
        }
        if (manifest.version.isBlank() || manifest.archiveSizeBytes < 0L || manifest.expandedSizeBytes < 0L ||
            !Regex("^[a-fA-F0-9]{64}$").matches(manifest.archiveSha256)
        ) {
            return RootfsValidationResult.Invalid(RootfsValidationError.INVALID_MANIFEST)
        }
        if (!Files.isRegularFile(archive)) {
            return RootfsValidationResult.Invalid(RootfsValidationError.ARCHIVE_NOT_FOUND)
        }
        if (Files.size(archive) != manifest.archiveSizeBytes) {
            return RootfsValidationResult.Invalid(RootfsValidationError.ARCHIVE_SIZE_MISMATCH)
        }
        val actual = sha256(archive)
        if (!actual.equals(manifest.archiveSha256, ignoreCase = true)) {
            return RootfsValidationResult.Invalid(RootfsValidationError.SHA256_MISMATCH)
        }
        if (!verifySignature(manifest)) {
            return RootfsValidationResult.Invalid(RootfsValidationError.SIGNATURE_INVALID)
        }
        return RootfsValidationResult.Valid(manifest)
    }

    companion object {
        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
