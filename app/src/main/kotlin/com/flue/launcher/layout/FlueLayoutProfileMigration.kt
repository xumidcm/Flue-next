package com.flue.launcher.layout

/**
 * 扁平鱼眼偏好 → 布局档案的一次性迁移。
 *
 * 改动前鱼眼是六个**全局**偏好键，四种布局共用一份。升级上来时要把它们原样写进
 * 每一种布局的默认档案 —— 否则老师调好的参数在升级那一刻全部退回出厂值，
 * 而这正是「按布局分开存」这件事最容易翻车的地方。
 *
 * 每个参数都可为 null，表示「这个键从来没写过」，那就沿用出厂值。不要用 0 / false
 * 当缺省哨兵：`fisheyeEnabled = false` 和「没设置过」是两件事。
 *
 * 纯函数、不碰 DataStore，方便单测（见 `FlueLayoutProfileMigrationTest`）。
 * 心流那两项不参与迁移：旧版本没有心流，没有可迁的值。
 */
fun migratedLayoutProfiles(
    fisheyeEnabled: Boolean? = null,
    topRowExtraFisheyeEnabled: Boolean? = null,
    edgeSpacingCompressionEnabled: Boolean? = null,
    fisheyeRangeRows: Int? = null,
    fisheyeStrengthPercent: Int? = null,
    leftSafeInsetPercent: Int? = null,
    narrowColumns: Int? = null,
    appListScalePercent: Int? = null,
): List<FlueLayoutProfile> = FlueLayoutProfiles.builtIns.map { preset ->
    preset.copy(
        fisheyeEnabled = fisheyeEnabled ?: preset.fisheyeEnabled,
        topRowExtraFisheyeEnabled = topRowExtraFisheyeEnabled
            ?: preset.topRowExtraFisheyeEnabled,
        edgeSpacingCompressionEnabled = edgeSpacingCompressionEnabled
            ?: preset.edgeSpacingCompressionEnabled,
        fisheyeRangeRows = fisheyeRangeRows ?: preset.fisheyeRangeRows,
        fisheyeStrengthPercent = fisheyeStrengthPercent ?: preset.fisheyeStrengthPercent,
        leftSafeInsetPercent = leftSafeInsetPercent ?: preset.leftSafeInsetPercent,
        narrowColumns = narrowColumns ?: preset.narrowColumns,
        appListScalePercent = appListScalePercent ?: preset.appListScalePercent,
    ).sanitized().requireValid()
}
