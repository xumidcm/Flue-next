package com.flue.launcher.data.model

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val componentKey: String = "$packageName/$activityName"
)
