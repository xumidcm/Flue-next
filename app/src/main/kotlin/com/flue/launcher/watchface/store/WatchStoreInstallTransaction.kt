package com.flue.launcher.watchface.store

import kotlinx.coroutines.CancellationException

internal suspend fun <T> commitMarketInstallCatalog(
    wasAlreadyInstalled: Boolean,
    commit: suspend () -> T,
    rollback: suspend () -> Result<Unit>,
): T = try {
    commit()
} catch (cancelled: CancellationException) {
    // The catalog commit runs as a non-cancellable durable section. If the
    // caller is cancelled immediately after it completes, rolling back the
    // installed directory would leave an Installed catalog entry pointing at
    // missing files. Startup reconciliation will settle either state.
    throw cancelled
} catch (error: Throwable) {
    val rollbackResult = if (wasAlreadyInstalled) null else runCatching { rollback().getOrThrow() }
    throw WatchStoreInstallTransactionException(
        message = if (rollbackResult?.isFailure == true) {
            "表盘索引保存失败且自动回滚失败；下次扫描会重建安装状态"
        } else if (wasAlreadyInstalled) {
            "表盘已存在，但市场索引保存失败；请重试恢复"
        } else {
            "表盘索引保存失败，已恢复安装前状态"
        },
        cause = error,
        rollbackCompleted = rollbackResult?.isSuccess == true,
        rollbackFailure = rollbackResult?.exceptionOrNull(),
    )
}

class WatchStoreInstallTransactionException(
    message: String,
    cause: Throwable,
    val rollbackCompleted: Boolean,
    val rollbackFailure: Throwable?,
) : WatchStoreException(message, cause)
