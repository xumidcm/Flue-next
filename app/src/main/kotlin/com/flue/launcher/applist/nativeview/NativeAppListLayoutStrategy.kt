package com.flue.launcher.applist.nativeview

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

interface NativeAppListLayoutStrategy {
    val semanticKey: Any

    fun createLayoutManager(context: Context): RecyclerView.LayoutManager
}

object LinearNativeAppListLayoutStrategy : NativeAppListLayoutStrategy {
    override val semanticKey: Any = "linear"

    override fun createLayoutManager(context: Context): RecyclerView.LayoutManager =
        LinearLayoutManager(context, RecyclerView.VERTICAL, false)
}
