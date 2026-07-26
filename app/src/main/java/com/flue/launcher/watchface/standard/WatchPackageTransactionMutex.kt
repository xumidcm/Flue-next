package com.flue.launcher.watchface.standard

import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes install/repair transactions for one content-addressed target.
 *
 * The lock is process-local by design: every installer instance in the app
 * shares this registry. Reference counting removes idle keys without the
 * remove-after-unlock race that could otherwise create two locks for one
 * target.
 */
internal object WatchPackageTransactionMutex {
    private class Entry {
        val lock = ReentrantLock()
        var references: Int = 0
    }

    private val guard = Any()
    private val entries = HashMap<String, Entry>()

    fun <T> withTarget(target: File, block: () -> T): T {
        val key = lockKey(target)
        val entry = synchronized(guard) {
            entries.getOrPut(key) { Entry() }.also { it.references++ }
        }
        entry.lock.lock()
        try {
            return block()
        } finally {
            entry.lock.unlock()
            synchronized(guard) {
                entry.references--
                if (entry.references == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }

    private fun lockKey(target: File): String {
        val path = runCatching { target.canonicalPath }
            .getOrElse { target.absolutePath }
        return if (File.separatorChar == '\\') path.lowercase(Locale.ROOT) else path
    }
}
