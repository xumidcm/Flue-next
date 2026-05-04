package com.flue.launcher.watchface.dingdingcat

import android.content.Context
import com.flue.launcher.watchface.LunchWatchFaceDescriptor

object DingDingCatWatchFaceScanner {
    fun scanImported(context: Context): List<LunchWatchFaceDescriptor> {
        return DingDingCatWatchFaceStorage.scan(context)
    }
}
