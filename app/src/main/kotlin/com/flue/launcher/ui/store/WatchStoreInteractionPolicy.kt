package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreInstallState

/** 溢出菜单里跟「账号」有关的那几行。 */
internal enum class WatchStoreAccountMenuAction {
    LOGIN_OR_REGISTER,
    MY_UPLOADS,
}

/**
 * 账号相关菜单项按**登录态**给，而不是按「市场列表是否加载成功」。
 *
 * 以前三个调用点全都写 `showManagement = marketLoadSucceeded`，完全不看登录态：网络通了就摆
 * 「我的上传 / 上传表盘」，未登录点进去只能看见一句「登录后查看我的上传」；而真正需要的
 * 「登录 / 注册」入口在整个市场页里一个都没有（老师原话：没有登录时右上角菜单要有登录注册
 * 选项，点击直接跳转登录）。
 *
 * 未登录时**只给一行**「登录 / 注册」而不是把三行都摆上：手表弹层一屏放不了几行，
 * 摆两个点了都要先登录的入口只是让人多点一次。
 */
internal fun watchStoreAccountMenuActions(signedIn: Boolean): List<WatchStoreAccountMenuAction> =
    if (signedIn) {
        listOf(WatchStoreAccountMenuAction.MY_UPLOADS)
    } else {
        listOf(WatchStoreAccountMenuAction.LOGIN_OR_REGISTER)
    }

/**
 * 表盘卡那条**定高单行**槽位里，什么时候该显示下载量。
 *
 * 下载量和安装状态挤在同一个 20/22dp 的槽位里（槽位定高是为了让宫格卡在下载完成后不塌陷），
 * 所以「显示下载量」只能是状态的函数：
 *
 * - `NotInstalled` / `Installed`：静止态，行里只有一句短状态，下载量能并排放下。
 *   以前 `Installed` 分支只画「已安装」三个字、完全不碰下载量 —— 就是老师报的
 *   「已安装的表盘不会显示下载量」。
 * - 下载中 / 校验中 / 安装中 / 失败：这一行被进度（转圈 + 百分比 + 取消）或错误信息 + 重试
 *   占满了，再塞一个下载量只会把两边都挤成省略号。这几态本来就是短暂的。
 */
internal fun watchStoreInstallStateShowsDownloadCount(state: WatchStoreInstallState): Boolean =
    when (state) {
        WatchStoreInstallState.NotInstalled,
        is WatchStoreInstallState.Installed,
        -> true
        is WatchStoreInstallState.Downloading,
        is WatchStoreInstallState.Validating,
        is WatchStoreInstallState.Installing,
        is WatchStoreInstallState.Failed,
        -> false
    }
