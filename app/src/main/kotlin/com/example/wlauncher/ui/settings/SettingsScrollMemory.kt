package com.flue.launcher

/** 某一页记下的滚动位置：LazyColumn 的首个可见项与它被卷走的像素数。 */
internal data class SavedScrollPosition(
    val index: Int = 0,
    val offset: Int = 0,
)

/**
 * 设置页层级深度表。
 *
 * 深度只服务一件事：判断这次导航是「往里进」还是「往外退」。往里进要把目标页
 * 摆回顶部（用户是第一次看这一页），往外退必须保留原位。
 *
 * 之所以是手写表而不是拿 backStack 长度推：FEATURE_TOGGLES 这类页面有两个入口
 * （ROOT 进是二级、APPEARANCE 进是三级），单一深度值在原理上描述不了两条路径。
 * 同深度的那几条边靠 [alwaysOpensAtTop] 点名补上。
 */
internal val SettingsDestination.settingsDepth: Int
    get() = when (this) {
        SettingsDestination.ROOT -> 0
        SettingsDestination.WATCH_FACE_MORE,
        SettingsDestination.BACKUP,
        SettingsDestination.SIDE_SCREEN,
        SettingsDestination.NOTIFICATION_SETTINGS,
        SettingsDestination.CONTROL_CENTER_SETTINGS,
        SettingsDestination.GESTURE_SETTINGS,
        SettingsDestination.APP_LIST_BEHAVIOR,
        SettingsDestination.PERFORMANCE_RENDERING,
        SettingsDestination.PERFORMANCE_MOTION,
        SettingsDestination.PERFORMANCE_SYSTEM,
        SettingsDestination.DONATE -> 2
        SettingsDestination.MUSIC_TEXT_ANIMATION,
        SettingsDestination.MOTION_PROFILE,
        // 应用列表背景挂在「更多表盘选项」下面，所以比它再深一层。
        SettingsDestination.APP_LIST_BACKGROUND,
        SettingsDestination.BACKUP_EXPORT,
        SettingsDestination.BACKUP_IMPORT -> 3
        SettingsDestination.TAP_ACTION_CATEGORIES -> 3
        SettingsDestination.TAP_ACTION_CATEGORY -> 4
        else -> 1
    }

/**
 * 这一页不管从哪个入口进都要落回顶部。
 *
 * 只列同深度跳转那几条边：APPEARANCE 里有几行分别进 DISPLAY_SIZE、
 * FEATURE_TOGGLES 和 AOD，目标深度都是 1，`to.settingsDepth > from.settingsDepth`
 * 判不出这是「往里进」，只能把这些目标页点名出来。
 *
 * 注意这个标记**只管进入方向**，不代表「这一页不记位置」——两者曾经被混成一个
 * 判断，见 [SettingsScrollMemory] 的注释。
 */
internal val SettingsDestination.alwaysOpensAtTop: Boolean
    get() = when (this) {
        SettingsDestination.DISPLAY_SIZE,
        SettingsDestination.FEATURE_TOGGLES,
        // APPEARANCE is a sibling entry to AOD; re-entering AOD starts a fresh visit.
        SettingsDestination.AOD -> true
        else -> settingsDepth >= 3
    }

/**
 * 从 [from] 进入 [to] 时，该不该把 [to] 记下的滚动位置丢掉。
 *
 * [poppingBack] 为真表示这是「往回走一格」。退回去的那一页正是用户离开前滑到
 * 某处的页面，这时候清位置就等于把他扔回页顶 —— 老师报的「二级页进三级页再退
 * 出来回到顶上」就是这一族问题，所以回退分支永远返回 false。
 */
internal fun settingsScrollResetsOnEnter(
    from: SettingsDestination,
    to: SettingsDestination,
    poppingBack: Boolean,
): Boolean = when {
    poppingBack -> false
    to.alwaysOpensAtTop -> true
    else -> to.settingsDepth > from.settingsDepth
}

/**
 * 各设置页滚动位置的账本。
 *
 * 刻意用普通 map 而不是 `mutableStateMapOf`：这是恢复用的记账，不是 UI 状态。
 * 用快照 map 时 SettingsPageScaffold 每滚一个像素写一次，就把整个 destination
 * 作废重组一遍（图标密集的隐藏应用页最明显）。
 *
 * [record] 一律照记，不看页面身份。这里曾经短路过
 * （commit 6a7771ae 加的 `if (!target.resetsScrollOnEnter)`）：本意是让
 * FEATURE_TOGGLES / DISPLAY_SIZE 和三级以下的页面「每次进来都在顶部」，做法
 * 却是干脆不记 —— 于是这些页面连「进子页再退回来」都恢复不了。
 * 「回到顶部」该由进入路径决定（[settingsScrollResetsOnEnter] + [forget]），
 * 不该由页面身份一刀切。
 */
internal class SettingsScrollMemory {
    private val positions = mutableMapOf<SettingsDestination, SavedScrollPosition>()

    fun positionOf(page: SettingsDestination): SavedScrollPosition =
        positions[page] ?: SavedScrollPosition()

    fun record(page: SettingsDestination, index: Int, offset: Int) {
        positions[page] = SavedScrollPosition(index = index, offset = offset)
    }

    fun forget(page: SettingsDestination) {
        positions.remove(page)
    }
}
