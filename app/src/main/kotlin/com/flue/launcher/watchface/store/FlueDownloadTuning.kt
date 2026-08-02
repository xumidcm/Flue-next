package com.flue.launcher.watchface.store

/**
 * 进程级下载调参。
 *
 * [HttpWatchStoreApi] 是在 Composable 里 `remember { HttpWatchStoreApi() }` 出来的，
 * 构造时机早于设置项从 DataStore 读出来，所以开关不能走构造参数——只能是一个
 * 传输层每次下载都去读的活引用。设置页在开关变化时写入这里。
 *
 * 用 @Volatile 而不是 StateFlow：读发生在 IO 线程的下载循环里，只需要「读到最新
 * 值」，不需要订阅。
 */
object FlueDownloadTuning {
    /** 多线程分段下载总开关，默认开。关掉后回到单条连接顺序读。 */
    @Volatile
    var segmentedDownloads: Boolean = true
}
