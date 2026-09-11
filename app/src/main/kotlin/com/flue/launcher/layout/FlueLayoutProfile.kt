package com.flue.launcher.layout

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.FlowShapeCurve
import java.util.UUID

const val FLUE_LAYOUT_SCHEMA_VERSION: Int = 3

const val FLUE_LAYOUT_NARROW_COLUMNS_MIN: Int = 1
const val FLUE_LAYOUT_NARROW_COLUMNS_MAX: Int = 5
const val FLUE_LAYOUT_APP_LIST_SCALE_MIN: Int = 50
const val FLUE_LAYOUT_APP_LIST_SCALE_MAX: Int = 200

/** 生效行数：从中心往外数几行参与缩放。 */
const val FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MIN: Int = 1
const val FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX: Int = 8

/** 缩小幅度（百分比）：最外圈缩到多小。 */
const val FLUE_LAYOUT_FISHEYE_STRENGTH_MIN: Int = 0
const val FLUE_LAYOUT_FISHEYE_STRENGTH_MAX: Int = 200

/** 左侧安全区：左边留出不放图标的宽度比例。 */
const val FLUE_LAYOUT_LEFT_SAFE_INSET_MIN: Int = 0
const val FLUE_LAYOUT_LEFT_SAFE_INSET_MAX: Int = 50

/** 月牙弧度：占「上限鼓出量」的百分比，0 = 不鼓。 */
/**
 * 副本命名：`名字 2` / `名字 3` …，而不是「副本」「副本副本」。
 *
 * 老师报的问题是「复制出来全叫副本，也没法手动改名」。改名入口另外补，这里先把**自动命名**
 * 修好：
 *
 * - 先剥掉已有的「 副本」后缀与结尾的数字，拿到干净的基名 —— 否则复制三次就成了
 *   「心流 副本 副本 副本」；
 * - 再从 2 开始找第一个没被占用的序号。序号从 2 起是因为原件本身算 1。
 *
 * 与档案类型无关（布局档案与动效档案共用），所以放在这里当自由函数。
 */
fun flueUniqueProfileName(
    sourceName: String,
    existingNames: Collection<String>,
    maxLength: Int = FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH,
): String {
    val taken = existingNames.map { it.trim() }.toHashSet()
    val base = sourceName.trim()
        .removeSuffix("副本").trim()
        .trimEnd { it.isDigit() }
        .trim()
        .ifBlank { sourceName.trim().ifBlank { "档案" } }
    var index = 2
    while (true) {
        val candidate = "$base $index".take(maxLength)
        if (candidate !in taken) return candidate
        index++
        // 理论上不会走到这里；给一个上界免得极端数据把界面卡住。
        if (index > 999) return "$base $index".take(maxLength)
    }
}

const val FLUE_LAYOUT_CRESCENT_BULGE_MIN: Int = 0

/**
 * 月牙弧度上限。
 *
 * 100% 的含义是「峰值那几行正好贴住可用区边界，谁都不越界」。老师要更深的弧，所以放到 150%
 * —— 超过 100% 的那一段刻意让上下两端那几行被推出可视区左侧（圆屏上本来就在圆外，
 * 而且行会随滚动走回中段）。换算在 `NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION`。
 */
const val FLUE_LAYOUT_CRESCENT_BULGE_MAX: Int = 150

const val FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH: Int = 48

/**
 * 一套布局参数档案：鱼眼六项 + 心流两项，绑定一种 [LayoutMode]。
 *
 * 为什么要档案：鱼眼原本是**全局扁平偏好**，四种布局共用一份 —— 蜂窝调好的缩小幅度
 * 切到列表就成了另一种观感，老师报的「按布局分开存」就是这件事。结构照
 * [com.flue.launcher.motion.FlueMotionProfile] 同构搬过来，于是设置页那套现成的
 * 复制 / 重命名 / 删除 / 导入导出 UI 可以整段复用。
 *
 * 与动效档案的一处**刻意不同**：这里的内置档案是**可编辑**的。动效档案的内置项只读、
 * 要改先复制一份；但鱼眼那几个滑块历来是拖了就生效，改成只读会让老师每次调参都先
 * 建副本。所以 [FlueLayoutProfiles.isBuiltIn] 在这里只用来挡**删除**（每种布局至少留
 * 一份默认档），不挡编辑。
 */
data class FlueLayoutProfile(
    val schemaVersion: Int = FLUE_LAYOUT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    /** 这套参数属于哪种布局。切布局时按它选档案。 */
    val layoutMode: LayoutMode,
    /** 总开关：关掉之后所有图标一样大。 */
    val fisheyeEnabled: Boolean,
    /**
     * 首行额外缩放。原名 `materialHoneycombTopFisheyeEnabled`，改名对齐语义 ——
     * 它做的事是「最上面那一行再缩一档」，只在 MD3 蜂窝生效。
     */
    val topRowExtraFisheyeEnabled: Boolean,
    /** 边缘间距压缩：图标缩小时一并收紧行距。 */
    val edgeSpacingCompressionEnabled: Boolean,
    val fisheyeRangeRows: Int,
    val fisheyeStrengthPercent: Int,
    val leftSafeInsetPercent: Int,
    /** 心流专属：月牙朝向。其他布局存着不用，切回心流时还在。 */
    val crescentDirection: FlowCrescentDirection,
    /** 心流专属：月牙弧度。 */
    val crescentBulgePercent: Int,
    /** 网格类布局的窄屏列数。 */
    val narrowColumns: Int = 3,
    /** 列表和图标的整体缩放。 */
    val appListScalePercent: Int = 100,
    val flowShapeCurve: FlowShapeCurve = FlowShapeCurve.Crescent,
) {
    fun requireValid(): FlueLayoutProfile {
        require(schemaVersion == FLUE_LAYOUT_SCHEMA_VERSION) {
            "Unsupported layout profile schema: $schemaVersion"
        }
        require(id.isNotBlank() && id.length <= 96) { "Layout profile id is invalid" }
        require(name.isNotBlank() && name.length <= FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH) {
            "Layout profile name is invalid"
        }
        require(
            fisheyeRangeRows in
                FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MIN..FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX,
        ) {
            "fisheyeRangeRows must be in " +
                "$FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MIN..$FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX"
        }
        require(
            fisheyeStrengthPercent in
                FLUE_LAYOUT_FISHEYE_STRENGTH_MIN..FLUE_LAYOUT_FISHEYE_STRENGTH_MAX,
        ) {
            "fisheyeStrengthPercent must be in " +
                "$FLUE_LAYOUT_FISHEYE_STRENGTH_MIN..$FLUE_LAYOUT_FISHEYE_STRENGTH_MAX"
        }
        require(
            leftSafeInsetPercent in
                FLUE_LAYOUT_LEFT_SAFE_INSET_MIN..FLUE_LAYOUT_LEFT_SAFE_INSET_MAX,
        ) {
            "leftSafeInsetPercent must be in " +
                "$FLUE_LAYOUT_LEFT_SAFE_INSET_MIN..$FLUE_LAYOUT_LEFT_SAFE_INSET_MAX"
        }
        require(
            crescentBulgePercent in
                FLUE_LAYOUT_CRESCENT_BULGE_MIN..FLUE_LAYOUT_CRESCENT_BULGE_MAX,
        ) {
            "crescentBulgePercent must be in " +
                "$FLUE_LAYOUT_CRESCENT_BULGE_MIN..$FLUE_LAYOUT_CRESCENT_BULGE_MAX"
        }
        require(narrowColumns in FLUE_LAYOUT_NARROW_COLUMNS_MIN..FLUE_LAYOUT_NARROW_COLUMNS_MAX) {
            "narrowColumns must be in $FLUE_LAYOUT_NARROW_COLUMNS_MIN..$FLUE_LAYOUT_NARROW_COLUMNS_MAX"
        }
        require(appListScalePercent in FLUE_LAYOUT_APP_LIST_SCALE_MIN..FLUE_LAYOUT_APP_LIST_SCALE_MAX) {
            "appListScalePercent must be in $FLUE_LAYOUT_APP_LIST_SCALE_MIN..$FLUE_LAYOUT_APP_LIST_SCALE_MAX"
        }
        require(flowShapeCurve.x1 in 0f..1f && flowShapeCurve.x2 in 0f..1f)
        require(flowShapeCurve.y1 in 0f..1f && flowShapeCurve.y2 in 0f..1f)
        return this
    }

    /**
     * 把越界值夹回区间而不是抛 —— 迁移与导入路径要的是「尽量留住老师的设置」，
     * 一个字段超界不该让整份档案作废。[requireValid] 仍然守着代码内部的构造。
     */
    fun sanitized(): FlueLayoutProfile = copy(
        schemaVersion = FLUE_LAYOUT_SCHEMA_VERSION,
        edgeSpacingCompressionEnabled = layoutMode != LayoutMode.Flow && edgeSpacingCompressionEnabled,
        name = name.trim().take(FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH).ifBlank { "布局" },
        fisheyeRangeRows = fisheyeRangeRows.coerceIn(
            FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MIN,
            FLUE_LAYOUT_FISHEYE_RANGE_ROWS_MAX,
        ),
        fisheyeStrengthPercent = fisheyeStrengthPercent.coerceIn(
            FLUE_LAYOUT_FISHEYE_STRENGTH_MIN,
            FLUE_LAYOUT_FISHEYE_STRENGTH_MAX,
        ),
        leftSafeInsetPercent = leftSafeInsetPercent.coerceIn(
            FLUE_LAYOUT_LEFT_SAFE_INSET_MIN,
            FLUE_LAYOUT_LEFT_SAFE_INSET_MAX,
        ),
        crescentBulgePercent = crescentBulgePercent.coerceIn(
            FLUE_LAYOUT_CRESCENT_BULGE_MIN,
            FLUE_LAYOUT_CRESCENT_BULGE_MAX,
        ),
        narrowColumns = narrowColumns.coerceIn(
            FLUE_LAYOUT_NARROW_COLUMNS_MIN,
            FLUE_LAYOUT_NARROW_COLUMNS_MAX,
        ),
        appListScalePercent = appListScalePercent.coerceIn(
            FLUE_LAYOUT_APP_LIST_SCALE_MIN,
            FLUE_LAYOUT_APP_LIST_SCALE_MAX,
        ),
        flowShapeCurve = flowShapeCurve.sanitized(),
    )

    fun customCopy(
        customName: String = "$name 副本",
        customId: String = "custom:${UUID.randomUUID()}",
    ): FlueLayoutProfile = copy(
        id = customId,
        name = customName.trim().take(FLUE_LAYOUT_PROFILE_NAME_MAX_LENGTH),
    ).requireValid()
}
