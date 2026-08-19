package com.flue.launcher.ui.navigation

/**
 * 启动器屏幕状态机，对应 state-face / state-apps / state-app 等状态。
 * 每个状态切换都会触发对应的 scale + blur + opacity 动画。
 */
enum class ScreenState {
    /** 表盘/时钟界面 */
    Face,

    /** 应用列表（蜂窝或列表视图） */
    Apps,

    /** 单个应用内部视图 */
    App,

    /** 智能侧屏 */
    Stack,

    /** 通知中心 */
    Notifications,

    /** 独立小组件页 */
    Widgets,

    /** 控制中心 */
    ControlCenter,

    /** 桌面设置 */
    Settings
}

/** 应用抽屉布局模式 */
enum class LayoutMode {
    Honeycomb,
    Grid,
    List,

    /**
     * 心流：等列网格 + 月牙错位。
     *
     * 排布本身是规则网格（见 `util/FlowGridMath.kt` 的 `generateFlowRows`），
     * 月牙那条弧是绘制期按视口中心算的 translationX，不进布局。
     */
    Flow,

    /**
     * 星环：铺满整屏的正交方格，圆屏上四条边一起往屏心收拢。
     *
     * 排布是等列正方格（见 `applist/nativeview/NativeRingGeometry.kt`），四边那道弧是
     * 绘制期按格心离屏心的**径向**距离算的位移，同样不进布局。方屏退化成平铺网格。
     */
    Ring
}
