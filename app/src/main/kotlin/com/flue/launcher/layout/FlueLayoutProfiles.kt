package com.flue.launcher.layout

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.util.FlowCrescentDirection

/** 五种布局的出厂档案。心流固定关闭边缘间距压缩；其他布局保留原有默认。 */
object FlueLayoutProfiles {
    const val HONEYCOMB_ID: String = "preset:honeycomb"
    const val GRID_ID: String = "preset:grid"
    const val LIST_ID: String = "preset:list"
    const val FLOW_ID: String = "preset:flow"
    const val RING_ID: String = "preset:ring"

    val Honeycomb: FlueLayoutProfile = FlueLayoutProfile(
        id = HONEYCOMB_ID,
        name = "蜂窝",
        layoutMode = LayoutMode.Honeycomb,
        fisheyeEnabled = true,
        topRowExtraFisheyeEnabled = false,
        edgeSpacingCompressionEnabled = true,
        fisheyeRangeRows = 4,
        fisheyeStrengthPercent = 100,
        leftSafeInsetPercent = 0,
        crescentDirection = FlowCrescentDirection.Right,
        crescentBulgePercent = 0,
    ).requireValid()

    val Grid: FlueLayoutProfile = Honeycomb.copy(
        id = GRID_ID,
        name = "网格",
        layoutMode = LayoutMode.Grid,
    ).requireValid()

    val List: FlueLayoutProfile = Honeycomb.copy(
        id = LIST_ID,
        name = "列表",
        layoutMode = LayoutMode.List,
    ).requireValid()

    /**
     * 心流出厂即带月牙。
     *
     * 100% = 「峰值正好贴边」，也就是老师那张截图里的样子（首图标 x 的摆幅吃满可用余量）。
     * 以前写 90 是因为百分比当时是「内容宽度 × 0.75」的比例、上面还压着一道余量夹取，
     * 两个上限打架；现在百分比直接就是余量的倍数，出厂值取满格那一档最直观。
     */
    val Flow: FlueLayoutProfile = Honeycomb.copy(
        id = FLOW_ID,
        name = "心流",
        layoutMode = LayoutMode.Flow,
        edgeSpacingCompressionEnabled = false,
        crescentBulgePercent = 100,
    ).requireValid()

    /**
     * 星环与蜂窝 / 网格 / 列表同为「只吃鱼眼六项」的档案，月牙那两项存着不用。
     *
     * 它的上下边缘鱼眼带**复用鱼眼的强度与生效行数**（见
     * `NativeAppListConfig.nativeRingFisheyeProgress`），所以没有专属参数要加 ——
     * 出厂值就是鱼眼那份默认值。两种屏形同一套平铺渲染，没有圆屏专属参数。
     */
    val Ring: FlueLayoutProfile = Honeycomb.copy(
        id = RING_ID,
        name = "星环",
        layoutMode = LayoutMode.Ring,
    ).requireValid()

    val builtIns: kotlin.collections.List<FlueLayoutProfile> =
        listOf(Honeycomb, Grid, List, Flow, Ring)

    fun isBuiltIn(id: String): Boolean =
        id == HONEYCOMB_ID || id == GRID_ID || id == LIST_ID || id == FLOW_ID || id == RING_ID

    /** 某种布局的出厂档案。重置与「存储里一份都没有」时用它。 */
    fun builtInFor(layoutMode: LayoutMode): FlueLayoutProfile = when (layoutMode) {
        LayoutMode.Honeycomb -> Honeycomb
        LayoutMode.Grid -> Grid
        LayoutMode.List -> List
        LayoutMode.Flow -> Flow
        LayoutMode.Ring -> Ring
    }

    /**
     * 按 id 找档案；找不到就退回该布局的出厂档案。
     *
     * 与动效那边的 `resolve` 有一处不同：这里必须**带上 layoutMode** 才能兜底 ——
     * 档案是分布局的，「找不到就退回柔和」在这里没有对应物。
     */
    fun resolve(
        id: String?,
        layoutMode: LayoutMode,
        profiles: kotlin.collections.List<FlueLayoutProfile>,
    ): FlueLayoutProfile =
        profiles.firstOrNull { it.id == id && it.layoutMode == layoutMode }
            ?: profiles.firstOrNull { it.layoutMode == layoutMode && isBuiltIn(it.id) }
            ?: profiles.firstOrNull { it.layoutMode == layoutMode }
            ?: builtInFor(layoutMode)

    /**
     * 把存储里读出来的档案补全成「每种布局至少有一份默认档」。
     *
     * 少一份就补一份出厂的，而不是整份作废 —— 存储被外部编辑过、或者将来新增了
     * 第五种布局时，用户其余三种的调参不该跟着丢。
     */
    fun withMissingBuiltIns(
        profiles: kotlin.collections.List<FlueLayoutProfile>,
    ): kotlin.collections.List<FlueLayoutProfile> {
        val present = profiles.mapTo(mutableSetOf()) { it.id }
        val missing = builtIns.filterNot { it.id in present }
        return if (missing.isEmpty()) profiles else profiles + missing
    }
}
