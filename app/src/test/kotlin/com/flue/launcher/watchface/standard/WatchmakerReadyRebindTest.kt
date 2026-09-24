package com.flue.launcher.watchface.standard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchmakerReadyRebindTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `ready renderer reports readiness to a rebound host without loading again`() {
        val root = temporaryFolder.newFolder("face")
        root.resolve("watch.xml").writeText("<Watch name=\"Ready\"><Layer type=\"text\" text=\"12\" /></Watch>")
        val descriptor = WatchFaceDescriptor(
            "watch:ready", WatchFaceType.WATCHMAKER, "Ready", sourceDirPath = root.absolutePath,
        )
        val view = WatchmakerWatchFaceView(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            // Prepare the real source synchronously, as in the bitmap rendering fixture.
            val source = requireNotNull(view.javaClass.getDeclaredMethod(
                "prepareSource", File::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(view, root, 0L))
            fun set(name: String, value: Any) {
                view.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(view, value)
            }
            set("source", source)
            set("descriptorKey", descriptor.stableKey)
            set("loadedDescriptorKey", descriptor.stableKey)
            set("refreshToken", 7)
            set("loadedRefreshToken", 7)
            val size = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            view.measure(size, size)
            view.layout(0, 0, 320, 320)
            val firstSignals = mutableListOf<Int>()
            fun bind(visible: Boolean, callback: (Int) -> Unit) {
                view.bind(descriptor, 7, visible, null, null,
                    onLoadFailure = { _, error -> throw AssertionError(error) },
                    onRenderReady = callback)
            }
            bind(true) { firstSignals += it }
            assertEquals("A prepared source still needs its first draw", emptyList<Int>(), firstSignals)
            view.draw(Canvas(bitmap))
            assertEquals(listOf(7), firstSignals)

            val reboundSignals = mutableListOf<Int>()
            bind(false) { reboundSignals += it }
            assertEquals(listOf(7), reboundSignals)
            reboundSignals.clear()
            bind(true) { reboundSignals += it }
            assertEquals("Returning must immediately synchronize the ready host", listOf(7), reboundSignals)
            assertSame(source, view.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(view))
        } finally {
            view.dispose()
            bitmap.recycle()
        }
    }
}
