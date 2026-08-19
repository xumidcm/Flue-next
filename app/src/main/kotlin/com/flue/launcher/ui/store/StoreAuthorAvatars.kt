package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.HttpWatchStoreApi
import java.util.concurrent.ConcurrentHashMap

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
    private val cache = ConcurrentHashMap<String, String>()

    /** 缓存里已知的头像地址；还没查过返回 null。同步、可在组合期直接读。 */
    fun cached(username: String): String? =
        cache[username.trim().lowercase()]?.takeIf(String::isNotEmpty)

    fun isResolved(username: String): Boolean =
        cache.containsKey(username.trim().lowercase())

    /**
     * 解析并写入缓存。必须在 IO 上下文调用。
     *
     * @return 头像地址，或 null（没有 / 查不到）。
     */
    fun resolve(api: HttpWatchStoreApi, username: String): String? {
        val key = username.trim().lowercase()
        if (key.isEmpty()) return null
        cache[key]?.let { return it.takeIf(String::isNotEmpty) }
        val resolved = runCatching { api.getCreator(username).avatarUrl }
            .getOrNull()
            ?.trim()
            .orEmpty()
        cache[key] = resolved
        return resolved.takeIf(String::isNotEmpty)
    }

    /** 只用于测试：清掉进程缓存。 */
    internal fun clearForTest() = cache.clear()
}
