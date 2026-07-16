package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import com.flue.launcher.applist.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppListRowViewBehaviorTest {
    @Test
    fun iconRenderOwnerKeepsBitmapIdentityAndFallsBackWithoutRecycling() {
        val owner = NativeAppListIconRenderOwner()
        val bitmap = solidBitmap(REAL_ICON_COLOR)

        owner.bind(bitmap)

        assertSame(bitmap, owner.bitmapForDraw())
        owner.clear()
        assertNull(owner.bitmapForDraw())
        assertFalse(bitmap.isRecycled)

        owner.bind(bitmap)
        bitmap.recycle()
        assertNull(owner.bitmapForDraw())
    }

    @Test
    fun canvasRowDrawsTheRealBitmapAndUsesFallbackWhenItIsMissing() {
        val view = NativeAppListRowView(RuntimeEnvironment.getApplication())
        val config = drawingConfig()
        val bitmap = solidBitmap(REAL_ICON_COLOR)
        val output = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)

        view.bind(
            row = boundRow(bitmap),
            config = config,
            clickListener = View.OnClickListener {},
        )
        measureAndLayout(view)
        view.draw(Canvas(output))
        assertEquals(REAL_ICON_COLOR, output.getPixel(5, 20))

        output.eraseColor(Color.TRANSPARENT)
        view.bind(
            row = boundRow(null),
            config = config,
            clickListener = View.OnClickListener {},
        )
        view.draw(Canvas(output))
        assertEquals(FALLBACK_COLOR, output.getPixel(5, 20))
    }

    @Test
    fun contentAndCanvasRowRetainTheSubmittedBitmapWithoutCopyingOrRecyclingIt() {
        val entry = appEntry()
        val bitmap = solidBitmap(REAL_ICON_COLOR)
        val content = NativeAppListContent(
            snapshot = AppListSnapshot.create(11L, listOf(entry)),
            iconsByKey = mapOf(entry.iconKey to bitmap),
        )

        assertSame(bitmap, content.iconsByKey[entry.iconKey])
        assertSame(bitmap, content.rows.single().icon)

        val view = NativeAppListRowView(RuntimeEnvironment.getApplication())
        view.bind(
            row = content.rows.single(),
            config = drawingConfig(),
            clickListener = View.OnClickListener {},
        )
        measureAndLayout(view)
        val output = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        view.clearBinding()

        assertFalse(bitmap.isRecycled)
        assertSame(bitmap, content.iconsByKey[entry.iconKey])
    }

    @Test
    fun setConfigKeepsTheExistingLayoutManagerInstance() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication())
        val originalLayoutManager = view.layoutManager

        view.setConfig(
            NativeAppListConfig(
                rowHeightPx = 96,
                iconSizePx = 48,
                initialResetToken = 3L,
            ),
        )

        assertSame(originalLayoutManager, view.layoutManager)
        view.release()
    }

    @Test
    fun paletteArgbValuesAreLegalizedToUnsigned32BitColors() {
        val legalized = NativeAppListConfig(
            palette = NativeAppListPalette(
                rowBackgroundColorArgb = 0x1ff112233L,
                labelColorArgb = -1L,
                pressedOverlayColorArgb = 0x2aa445566L,
                fallbackIconBackgroundColorArgb = 0x1ff778899L,
                fallbackIconForegroundColorArgb = 0x1ffaabbccL,
            ),
        ).legalized()

        assertEquals(0xff112233L, legalized.palette.rowBackgroundColorArgb)
        assertEquals(0xffffffffL, legalized.palette.labelColorArgb)
        assertEquals(0xaa445566L, legalized.palette.pressedOverlayColorArgb)
        assertEquals(0xff778899L, legalized.palette.fallbackIconBackgroundColorArgb)
        assertEquals(0xffaabbccL, legalized.palette.fallbackIconForegroundColorArgb)
    }

    private fun drawingConfig(): NativeAppListConfig = NativeAppListConfig(
        rowHeightPx = 40,
        iconSizePx = 20,
        rowPaddingStartPx = 4,
        rowPaddingTopPx = 0,
        rowPaddingEndPx = 4,
        rowPaddingBottomPx = 0,
        iconLabelGapPx = 4,
        palette = NativeAppListPalette(
            fallbackIconBackgroundColorArgb = FALLBACK_COLOR.toLong() and 0xffffffffL,
        ),
    )

    private fun boundRow(bitmap: Bitmap?): NativeAppListBoundRow<Bitmap> {
        val entry = appEntry()
        return NativeAppListBoundRow(
            snapshotGeneration = 1L,
            stableKey = entry.key,
            entry = entry,
            icon = bitmap,
        )
    }

    private fun appEntry(): AppListEntry = AppListEntry(
        key = AppItemKey("component:com.example/.MainActivity"),
        label = "Example",
        type = AppItemType.APP,
        launchTarget = LaunchTarget.Component(
            packageName = "com.example",
            className = ".MainActivity",
        ),
        iconKey = IconKey("icon:example"),
        folderChildren = emptyList(),
    )

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    private fun measureAndLayout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 40)
    }

    private companion object {
        const val REAL_ICON_COLOR: Int = Color.MAGENTA
        const val FALLBACK_COLOR: Int = Color.GREEN
    }
}
