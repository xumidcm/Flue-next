package com.flue.launcher.watchface.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 功能开关与未读数的**唯一持有者**（B2）。
 *
 * ## 为什么不是把 28 个新端点全包一层
 *
 * 项目现有的分工是：`WatchStoreRepository` 管公开的浏览 / 下载 / 安装事务，而**登录态相关的
 * 调用由界面自己持一个 `HttpWatchStoreApi` 直接发**（商店页、上传页、编辑器都是
 * `remember { HttpWatchStoreApi() }`）。给那 28 个方法各加一层转发不会带来任何收益 ——
 * 调用点仍是各页面，只是多一层壳。
 *
 * 真正需要「单一持有者」的只有两件事，因为它们**每个页面都要问、而且不该每次都发请求**：
 *
 * - **功能开关**：社交入口要按它隐藏（禁用时上游回 403），几乎每个页面都要读；
 * - **未读数**：铃铛红点要它，手表电量敏感，**不能常驻轮询**。
 *
 * 所以这两件收在这里，其余保持现有分工不变。
 *
 * 依赖用**函数**而不是 `HttpWatchStoreApi`：那是个具体类，测试里没法替身。
 */
class WatchStoreFeatureFlagCache(
    private val fetch: suspend () -> WatchStoreFeatureFlags,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val mutex = Mutex()
    private var cached: WatchStoreFeatureFlags? = null
    private var fetchedAtMs: Long = Long.MIN_VALUE

    /**
     * 取开关。TTL 内直接给缓存。
     *
     * **请求失败时返回上一次的值、没有就返回全开**：拿不到配置不该把界面砍掉，真被禁会在
     * 调用时收到 403，那时再提示比一上来就藏掉入口更诚实。
     */
    suspend fun flags(nowMs: Long = System.currentTimeMillis()): WatchStoreFeatureFlags =
        mutex.withLock {
            val fresh = cached?.takeIf { nowMs - fetchedAtMs in 0 until ttlMs }
            if (fresh != null) return@withLock fresh
            val loaded = runCatching { fetch() }.getOrNull()
            if (loaded != null) {
                cached = loaded
                fetchedAtMs = nowMs
                loaded
            } else {
                cached ?: WatchStoreFeatureFlags()
            }
        }

    /** 切换市场地址、或退出登录之后要丢掉缓存。 */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        fetchedAtMs = Long.MIN_VALUE
    }

    companion object {
        /** 开关很少变，5 分钟够了。 */
        const val DEFAULT_TTL_MS: Long = 5L * 60L * 1_000L

        fun of(api: HttpWatchStoreApi, ttlMs: Long = DEFAULT_TTL_MS): WatchStoreFeatureFlagCache =
            WatchStoreFeatureFlagCache(fetch = { api.getFeatureFlags() }, ttlMs = ttlMs)
    }
}

/**
 * 未读通知数，带节流。
 *
 * 手表电量敏感，所以：**只在被问到时才可能发请求**（页面可见时由 UI 调用），而且两次真实
 * 请求之间至少隔 [throttleMs]。节流窗口内直接给上次的数 —— 红点晚 30 秒亮没有代价，
 * 后台常驻轮询有。
 */
class WatchStoreUnreadCounter(
    private val fetch: suspend (token: String) -> Int,
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
) {
    private val mutex = Mutex()
    private var lastCount: Int = 0
    private var lastFetchedAtMs: Long = Long.MIN_VALUE

    suspend fun count(token: String, nowMs: Long = System.currentTimeMillis()): Int =
        mutex.withLock {
            if (nowMs - lastFetchedAtMs in 0 until throttleMs) return@withLock lastCount
            val loaded = runCatching { fetch(token) }.getOrNull()
            if (loaded != null) {
                // 只有请求真的成功才推进时间戳：失败也推进的话，一次网络抖动会让红点
                // 静默停摆整个节流窗口。
                lastCount = loaded.coerceAtLeast(0)
                lastFetchedAtMs = nowMs
            }
            lastCount
        }

    /** 用户点了「全部已读」之后立刻归零，不用等下一次拉取。 */
    suspend fun markAllRead(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        lastCount = 0
        lastFetchedAtMs = nowMs
    }

    /** 退出登录：清掉计数与节流窗口，换号之后第一次问就该真发请求。 */
    suspend fun reset() = mutex.withLock {
        lastCount = 0
        lastFetchedAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_THROTTLE_MS: Long = 30L * 1_000L

        fun of(api: HttpWatchStoreApi, throttleMs: Long = DEFAULT_THROTTLE_MS): WatchStoreUnreadCounter =
            WatchStoreUnreadCounter(fetch = { token -> api.getUnreadNotificationCount(token) }, throttleMs = throttleMs)
    }
}
