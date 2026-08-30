package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.HttpWatchStoreApi
import java.util.concurrent.ConcurrentHashMap

internal data class StoreAuthorAvatarSnapshot(
    val url: String?,
    val version: Long,
)

/** Process cache for creator avatars, with per-author de-duplication and expiry. */
internal class StoreAuthorAvatarCache(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private data class Entry(
        val snapshot: StoreAuthorAvatarSnapshot,
        val fetchedAtEpochMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val versionCounters = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        require(maxAgeMs > 0L) { "maxAgeMs must be positive" }
    }

    fun cached(
        username: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): StoreAuthorAvatarSnapshot? {
        val key = key(username)
        return entries[key]
            ?.takeIf { isFresh(it, nowEpochMs) }
            ?.snapshot
    }

    fun resolve(
        username: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        fetch: () -> String?,
    ): StoreAuthorAvatarSnapshot? {
        val key = key(username)
        if (key.isEmpty()) return null
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            entries[key]
                ?.takeIf { isFresh(it, nowEpochMs) }
                ?.snapshot
                ?.let { return it }
            val avatarUrl = runCatching { fetch() }
                .getOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val nextVersion = (versionCounters[key] ?: 0L) + 1L
            versionCounters[key] = nextVersion
            val snapshot = StoreAuthorAvatarSnapshot(avatarUrl, nextVersion)
            entries[key] = Entry(snapshot, nowEpochMs)
            return snapshot
        }
    }

    fun invalidate(username: String) {
        val key = key(username)
        if (key.isNotEmpty()) entries.remove(key)
    }

    fun clear() {
        entries.clear()
        versionCounters.clear()
    }

    private fun isFresh(entry: Entry, nowEpochMs: Long): Boolean =
        nowEpochMs >= entry.fetchedAtEpochMs &&
            nowEpochMs - entry.fetchedAtEpochMs < maxAgeMs

    private fun key(username: String): String = username.trim().lowercase()

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 5L * 60L * 1_000L
    }
}

/**
 * 按用户名解析头像地址，进程内缓存。
 *
 * ## 为什么评论区必须绕一趟
 *
 * 上游 `GET /watchfaces/:id/comments` 的 `user` 对象**只有 `id` 和 `username`**
 * （`api/src/routes/features.js` 的 SELECT 里就是 `u.id, u.username`），没有 `avatar_url`。
 * 所以评论行的 `authorAvatarUrl` 永远是 null，[StoreAvatar] 一路退回首字母圆 —— 表现就是
 * 老师说的「评论的人只会显示用户名」。头像本身是有的，只是要从 `GET /creators/:username`
 * 另取一次。
 *
 * ## 为什么缓存在进程里而不是每行各自请求
 *
 * 一条评论区里同一个人常出现多次，翻回详情页又要重来。按用户名缓存之后，一个人一次请求，
 * 打开评论二级页也不会变成 N 个并发请求。
 *
 * **也缓存失败**：查不到（用户注销、地址在站外被拒、网络不通）时记一个「没有头像」而不是
 * 留空重试 —— 否则每次重组都会为同一个查不到的人再打一次网络。
 */
internal object StoreAuthorAvatars {

    /** value 用 [String] 包一层：空串代表「查过了，没有头像」，与「还没查」区分开。 */
    private val cache = StoreAuthorAvatarCache()

    /** 缓存里已知的头像地址；还没查过返回 null。同步、可在组合期直接读。 */
    fun cached(username: String): String? =
        cache.cached(username)?.url

    /** 用于 Compose key：同 URL 重新取图时，版本也必须变化。 */
    fun cachedVersion(username: String): Long =
        cache.cached(username)?.version ?: 0L

    fun isResolved(username: String): Boolean =
        cache.cached(username) != null

    fun invalidate(username: String) = cache.invalidate(username)

    /**
     * 解析并写入缓存。必须在 IO 上下文调用。
     *
     * @return 头像地址，或 null（没有 / 查不到）。
     */
    fun resolve(api: HttpWatchStoreApi, username: String): String? {
        return resolveSnapshot(api, username)?.url
    }

    fun resolveSnapshot(api: HttpWatchStoreApi, username: String): StoreAuthorAvatarSnapshot? =
        cache.resolve(username) { api.getCreator(username).avatarUrl }

    /** 只用于测试：清掉进程缓存。 */
    internal fun clearForTest() = cache.clear()
}
