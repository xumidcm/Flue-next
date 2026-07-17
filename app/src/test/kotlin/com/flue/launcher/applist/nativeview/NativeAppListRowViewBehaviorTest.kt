package com.flue.launcher.applist.nativeview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.MotionEvent
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import com.flue.launcher.applist.model.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppListRowViewBehaviorTest {
    @Test
    fun honeycombLayoutManagerCentersTheFirstRowAndKeepsAttachedViewsBounded() {
        val entries = (0 until 100).map { appEntry("component:honey-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.HONEYCOMB,
                    iconSizePx = 64,
                    rowHeightPx = 66,
                    honeycombCellSizePx = 66f,
                    honeycombNarrowColumns = 3,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(HoneycombNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 320, 320)

        val manager = view.layoutManager as NativeHoneycombLayoutManager
        val first = manager.findViewByPosition(0)
        assertTrue(first != null)
        assertEquals(160f, requireNotNull(first).top + first.height / 2f, 1f)
        assertTrue("initial childCount=${view.childCount}", view.childCount < 36)

        val betweenIcons = manager.findNearestVisiblePosition(
            x = 160f,
            y = 210f,
            maximumDistancePx = Float.MAX_VALUE,
        )
        assertTrue(betweenIcons >= 0)
        assertEquals(
            -1,
            manager.findNearestVisiblePosition(160f, 210f, maximumDistancePx = 2f),
        )

        view.scrollBy(0, 300)
        assertNull(manager.findViewByPosition(0))
        assertTrue("scrolled childCount=${view.childCount}", view.childCount < 36)
        view.release()
    }

    @Test
    fun honeycombDragSurvivesWhenItsSourceChildIsDetachedByRecyclerLayout() {
        val entries = (0 until 100).map { appEntry("component:drag-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.HONEYCOMB,
                    iconSizePx = 64,
                    rowHeightPx = 66,
                    honeycombCellSizePx = 66f,
                    honeycombNarrowColumns = 3,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(HoneycombNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 320, 320)
        val manager = view.layoutManager as NativeHoneycombLayoutManager
        val source = requireNotNull(manager.findViewByPosition(0))
        val sourceX = source.left + source.width / 2f
        val sourceY = source.top + source.height / 2f

        dispatch(view, MotionEvent.ACTION_DOWN, x = sourceX, y = sourceY, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatch(
            view,
            MotionEvent.ACTION_MOVE,
            x = sourceX,
            y = sourceY + 22f,
            eventTime = 621L,
        )
        assertEquals(0f, source.alpha, 0f)
        // Custom LayoutManagers detach/scrap children during data relayout. The
        // gesture must remain owned by a stable-key overlay, not this child.
        manager.removeView(source)
        assertNull(source.parent)
        val target = view.getChildAt(view.childCount / 2)

        dispatch(
            view,
            MotionEvent.ACTION_MOVE,
            x = target.left + target.width / 2f,
            y = target.top + target.height / 2f,
            eventTime = 700L,
        )

        assertTrue(commands.any { it is AppListCommand.Move })
        dispatch(view, MotionEvent.ACTION_UP, x = 160f, y = 160f, eventTime = 701L)
        view.release()
        activity.finish()
    }

    @Test
    fun honeycombGeometryChangeCancelsAnActiveOverlayBeforeApplyingNewMetrics() {
        val entries = (0 until 20).map { appEntry("component:geometry-$it") }
        val commands = mutableListOf<AppListCommand>()
        val initialConfig = drawingConfig().copy(
            presentation = NativeAppListPresentation.HONEYCOMB,
            iconSizePx = 64,
            rowHeightPx = 66,
            honeycombCellSizePx = 66f,
            honeycombNarrowColumns = 3,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(initialConfig)
            setLayoutStrategy(HoneycombNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 320, 320)
        val source = requireNotNull(view.layoutManager?.findViewByPosition(0))
        val sourceX = source.left + source.width / 2f
        val sourceY = source.top + source.height / 2f
        dispatch(view, MotionEvent.ACTION_DOWN, x = sourceX, y = sourceY, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatch(
            view,
            MotionEvent.ACTION_MOVE,
            x = sourceX,
            y = sourceY + 22f,
            eventTime = 621L,
        )
        assertEquals(0f, source.alpha, 0f)

        view.setConfig(
            initialConfig.copy(
                iconSizePx = 76,
                rowHeightPx = 78,
                honeycombCellSizePx = 78f,
            ),
        )

        assertEquals(1f, source.alpha, 0f)
        val movesBefore = commands.count { it is AppListCommand.Move }
        dispatch(view, MotionEvent.ACTION_MOVE, x = 200f, y = 200f, eventTime = 700L)
        assertEquals(movesBefore, commands.count { it is AppListCommand.Move })
        view.release()
        activity.finish()
    }

    @Test
    fun geometryChangeCancelsAPendingLongPressBeforeTheOverlayStarts() {
        val entry = appEntry("component:pending-geometry")
        val commands = mutableListOf<AppListCommand>()
        val initialConfig = drawingConfig().copy(
            centerEdgeItems = false,
            rowHeightPx = 40,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(initialConfig)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, listOf(entry)),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 80)

        dispatch(view, MotionEvent.ACTION_DOWN, x = 30f, y = 20f, eventTime = 0L)
        view.setConfig(initialConfig.copy(iconSizePx = initialConfig.iconSizePx + 4))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))

        assertFalse(commands.any { it is AppListCommand.ShowActions })
        view.release()
        activity.finish()
    }

    @Test
    fun adapterConfigUpdateDoesNotNotifyAFullDatasetRebind() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val entries = listOf(appEntry("component:config-a"), appEntry("component:config-b"))
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        var rangeChanges = 0
        adapter.registerAdapterDataObserver(
            object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    rangeChanges++
                }
            },
        )

        adapter.setConfig(
            NativeAppListResolvedConfig.from(
                drawingConfig().copy(entryProgress = 0.5f),
            ),
        )

        assertEquals(0, rangeChanges)
    }

    @Test
    fun cachedNativeRowUsesLatestGeometryWhenItReattachesWithoutAFullRebind() {
        val entries = (0 until 10).map { appEntry("component:cache-$it") }
        val initialConfig = drawingConfig().copy(
            centerEdgeItems = false,
            rowHeightPx = 40,
            iconSizePx = 20,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initialConfig)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 80)
        view.scrollBy(0, 50)
        assertNull(view.layoutManager?.findViewByPosition(0))

        view.setConfig(initialConfig.copy(rowHeightPx = 52, iconSizePx = 30))
        view.scrollToPosition(0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 80)

        assertEquals(52, requireNotNull(view.layoutManager?.findViewByPosition(0)).height)
        view.release()
    }

    @Test
    fun rowComposesFlowDragAndDiffTranslationsWithoutOwnersOverwritingEachOther() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())

        row.setFlowTranslationY(10f)
        row.setMoveTranslation(-3f, -20f)
        row.setDragTranslationY(5f)
        assertEquals(-3f, row.translationX, 0f)
        assertEquals(-5f, row.translationY, 0f)
        assertEquals(-15f, row.positionTranslationYWithoutFlow(), 0f)

        row.setMoveTranslation(0f, 0f)
        assertEquals(15f, row.translationY, 0f)
    }

    @Test
    fun nativeViewUsesTheMoveOnlySharedBatchAnimator() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication())

        assertTrue(view.itemAnimator is NativeStableKeyItemAnimator)
        assertEquals(180L, view.itemAnimator?.moveDuration)

        view.release()
    }

    @Test
    fun nativeViewOwnsLongPressThenStartsStableKeyDragWithoutDuplicateMenu() {
        val entries = listOf(
            appEntry(key = "component:a"),
            appEntry(key = "component:b"),
            appEntry(key = "component:c"),
        )
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    centerEdgeItems = false,
                    rowHeightPx = 40,
                    fisheyeEnabled = false,
                ),
            )
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 120)
        assertEquals(3, view.childCount)

        dispatch(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        assertEquals(
            listOf(AppListCommand.ShowActions(AppItemKey("component:a"))),
            commands,
        )

        dispatch(view, MotionEvent.ACTION_MOVE, y = 42f, eventTime = 621L)
        dispatch(view, MotionEvent.ACTION_MOVE, y = 65f, eventTime = 700L)
        dispatch(view, MotionEvent.ACTION_UP, y = 65f, eventTime = 701L)

        assertEquals(1, commands.count { it is AppListCommand.ShowActions })
        assertTrue(commands.contains(AppListCommand.DismissActions))
        assertTrue(
            commands.contains(
                AppListCommand.Move(
                    key = AppItemKey("component:a"),
                    beforeKey = AppItemKey("component:c"),
                ),
            ),
        )
        assertEquals(0f, view.getChildAt(0).translationY, 0f)
        view.release()
        activity.finish()
    }

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
        val config = resolvedDrawingConfig()
        val bitmap = solidBitmap(REAL_ICON_COLOR)
        val output = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)

        view.bind(
            row = boundRow(bitmap),
            config = config,
        )
        measureAndLayout(view)
        view.draw(Canvas(output))
        assertEquals(REAL_ICON_COLOR, output.getPixel(5, 20))

        output.eraseColor(Color.TRANSPARENT)
        view.bind(
            row = boundRow(null),
            config = config,
        )
        view.draw(Canvas(output))
        assertEquals(FALLBACK_COLOR, output.getPixel(5, 20))
    }

    @Test
    fun appleStyleClipsRealBitmapToTheNativeCircularIconShape() {
        val view = NativeAppListRowView(RuntimeEnvironment.getApplication())
        val output = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        view.bind(
            row = boundRow(solidBitmap(REAL_ICON_COLOR)),
            config = NativeAppListResolvedConfig.from(
                drawingConfig().copy(iconShape = NativeAppListIconShape.CIRCLE),
            ),
        )
        measureAndLayout(view)

        view.draw(Canvas(output))

        assertEquals(Color.TRANSPARENT, output.getPixel(5, 11))
        assertEquals(REAL_ICON_COLOR, output.getPixel(14, 20))
    }

    @Test
    fun recyclerDrawsStableTopAndBottomFadeMasksInItsOwnCanvasLayer() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    topFadeRangePx = 20f,
                    bottomFadeRangePx = 20f,
                    palette = drawingConfig().palette.copy(
                        fadeEdgeColorArgb = 0xffff0000L,
                    ),
                ),
            )
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 100)
        val output = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        view.draw(Canvas(output))

        assertTrue(Color.alpha(output.getPixel(50, 0)) > 0)
        assertEquals(Color.TRANSPARENT, output.getPixel(50, 50))
        assertTrue(Color.alpha(output.getPixel(50, 99)) > 0)
        view.release()
    }

    @Test
    fun pullingPastLegacyThresholdAtTheTopRequestsTheWatchFaceOnce() {
        var requests = 0
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onScrollToTop = { requests++ }
            setConfig(drawingConfig().copy(fisheyeEnabled = false, centerEdgeItems = false))
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(
                        1L,
                        listOf(appEntry("a"), appEntry("b"), appEntry("c")),
                    ),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 120)

        dispatch(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        dispatch(view, MotionEvent.ACTION_MOVE, y = 93f, eventTime = 100L)
        dispatch(view, MotionEvent.ACTION_UP, y = 93f, eventTime = 101L)

        assertEquals(1, requests)
        view.release()
        activity.finish()
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
            config = resolvedDrawingConfig(),
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
    fun attachedRowsReceiveFisheyeVisualsAndResetWhenTheEffectIsDisabled() {
        val entries = (0 until 6).map { index ->
            appEntry(key = "component:item-$index")
        }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    centerEdgeItems = false,
                    fisheyeEnabled = true,
                    fisheyeRange = 1f,
                    fisheyeStrength = 1f,
                ),
            )
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 120)

        assertTrue(view.childCount >= 3)
        val scales = (0 until view.childCount).map { view.getChildAt(it).scaleX }
        assertTrue(scales.max() > scales.min())

        view.setConfig(
            drawingConfig().copy(
                centerEdgeItems = false,
                fisheyeEnabled = false,
            ),
        )

        (0 until view.childCount).forEach { index ->
            assertEquals(1f, view.getChildAt(index).scaleX, 0f)
            assertEquals(1f, view.getChildAt(index).alpha, 0f)
        }
        view.release()
    }

    @Test
    fun centeredEdgePaddingLetsTheFirstAndLastRowsReachViewportCenter() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication())
        view.setConfig(
            drawingConfig().copy(
                rowHeightPx = 40,
                centerEdgeItems = true,
            ),
        )

        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 120)

        assertEquals(40, view.paddingTop)
        assertEquals(40, view.paddingBottom)
        assertFalse(view.clipToPadding)
        view.release()
    }

    @Test
    fun nonLinearStrategyKeepsOwnershipOfItsChildTransforms() {
        val customStrategy = object : NativeAppListLayoutStrategy {
            override val semanticKey: Any = "custom-test"

            override fun createLayoutManager(
                context: android.content.Context,
                configProvider: () -> NativeAppListConfig,
            ): androidx.recyclerview.widget.RecyclerView.LayoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(context)
        }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setLayoutStrategy(customStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, listOf(appEntry())),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 80)
        val child = view.getChildAt(0)
        child.scaleX = 0.7f
        child.scaleY = 0.7f
        child.alpha = 0.6f

        view.layout(0, 0, 100, 81)

        assertEquals(0.7f, child.scaleX, 0f)
        assertEquals(0.7f, child.scaleY, 0f)
        assertEquals(0.6f, child.alpha, 0f)
        view.release()
    }

    @Test
    fun sameSnapshotNeedsANewerIconRevisionWhileANewSnapshotMayResetIt() {
        val entry = appEntry()
        val bitmap = solidBitmap(REAL_ICON_COLOR)
        val snapshot = AppListSnapshot.create(10L, listOf(entry))
        val view = NativeAppListView(RuntimeEnvironment.getApplication())

        assertTrue(
            view.submit(
                NativeAppListContent(
                    snapshot = snapshot,
                    iconsByKey = emptyMap(),
                    iconRevision = 0L,
                ),
            ),
        )
        assertTrue(
            view.submit(
                NativeAppListContent(
                    snapshot = snapshot,
                    iconsByKey = mapOf(entry.iconKey to bitmap),
                    iconRevision = 1L,
                ),
            ),
        )
        assertFalse(
            view.submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(9L, listOf(entry)),
                    iconsByKey = mapOf(entry.iconKey to bitmap),
                    iconRevision = 2L,
                ),
            ),
        )
        assertTrue(
            view.submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(11L, listOf(entry)),
                    iconsByKey = mapOf(entry.iconKey to bitmap),
                    iconRevision = 0L,
                ),
            ),
        )
        view.release()
    }

    @Test
    fun viewHolderReusesItsCreatedListenerAndClicksTheLatestBoundRow() {
        val parent = androidx.recyclerview.widget.RecyclerView(
            RuntimeEnvironment.getApplication(),
        ).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        }
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 100, 80)
        val commands = mutableListOf<AppListCommand>()
        val holder = NativeAppListAdapter.AppItemViewHolder.create(
            parent = parent,
            commandSink = commands::add,
        )
        parent.addView(holder.itemView)
        measureAndLayout(holder.itemView)
        val config = resolvedDrawingConfig()

        holder.bind(boundRow(null, key = "component:first"), config)
        val firstListener = clickListenerOf(holder.itemView)
        holder.bind(boundRow(null, key = "component:second"), config)
        assertEquals(AppItemKey("component:second"), holder.boundKeyOrNull())

        assertSame(firstListener, clickListenerOf(holder.itemView))
        holder.itemView.performClick()
        assertEquals(
            AppItemKey("component:second"),
            (commands.single() as AppListCommand.Open).key,
        )

        holder.clear()
        assertNull(holder.boundKeyOrNull())
        assertNull(clickListenerOf(holder.itemView))
        holder.bind(boundRow(null, key = "component:third"), config)
        assertSame(firstListener, clickListenerOf(holder.itemView))
    }

    @Test
    fun longPressRequestsActionsForTheLatestStableKey() {
        val parent = androidx.recyclerview.widget.RecyclerView(
            RuntimeEnvironment.getApplication(),
        )
        val commands = mutableListOf<AppListCommand>()
        val holder = NativeAppListAdapter.AppItemViewHolder.create(parent, commands::add)
        val config = resolvedDrawingConfig()

        holder.bind(boundRow(null, key = "component:first"), config)
        holder.bind(boundRow(null, key = "component:latest"), config)

        assertTrue(holder.itemView.performLongClick())
        assertEquals(
            AppListCommand.ShowActions(AppItemKey("component:latest")),
            commands.single(),
        )
    }

    @Test
    fun leftSafeInsetIsExcludedFromTheNativeRowHitRegion() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
        row.bind(
            boundRow(null),
            NativeAppListResolvedConfig.from(drawingConfig().copy(leftSafeInsetPx = 20)),
        )
        measureAndLayout(row)
        val outside = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 5f, 20f, 0)
        val inside = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 30f, 20f, 0)

        assertFalse(row.onTouchEvent(outside))
        assertTrue(row.onTouchEvent(inside))

        outside.recycle()
        inside.recycle()
    }

    @Test
    fun adapterClearSynchronouslyDropsCurrentRowsAndBitmapReferences() {
        val entry = appEntry()
        val bitmap = solidBitmap(REAL_ICON_COLOR)
        val adapter = NativeAppListAdapter(commandSink = {})
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, listOf(entry)),
                iconsByKey = mapOf(entry.iconKey to bitmap),
                iconRevision = 0L,
            ),
        )
        assertSame(bitmap, adapter.currentList.single().icon)

        adapter.clearSynchronously()

        assertTrue(adapter.currentList.isEmpty())
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun recycledHolderDoesNotLeakLinearVisualsIntoTheNextLayoutStrategy() {
        val parent = androidx.recyclerview.widget.RecyclerView(
            RuntimeEnvironment.getApplication(),
        )
        val adapter = NativeAppListAdapter(commandSink = {})
        val holder = NativeAppListAdapter.AppItemViewHolder.create(parent, commandSink = {})
        holder.itemView.apply {
            scaleX = 0.55f
            scaleY = 0.55f
            alpha = 0.6f
            translationX = 9f
            translationY = 11f
            rotation = 7f
        }

        adapter.onViewRecycled(holder)

        assertEquals(1f, holder.itemView.scaleX)
        assertEquals(1f, holder.itemView.scaleY)
        assertEquals(1f, holder.itemView.alpha)
        assertEquals(0f, holder.itemView.translationX)
        assertEquals(0f, holder.itemView.translationY)
        assertEquals(0f, holder.itemView.rotation)
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

    private fun resolvedDrawingConfig(): NativeAppListResolvedConfig =
        NativeAppListResolvedConfig.from(drawingConfig())

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

    private fun boundRow(
        bitmap: Bitmap?,
        key: String = "component:com.example/.MainActivity",
    ): NativeAppListBoundRow<Bitmap> {
        val entry = appEntry(key)
        return NativeAppListBoundRow(
            snapshotGeneration = 1L,
            stableKey = entry.key,
            entry = entry,
            icon = bitmap,
        )
    }

    private fun appEntry(
        key: String = "component:com.example/.MainActivity",
    ): AppListEntry = AppListEntry(
        key = AppItemKey(key),
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

    private fun dispatch(
        view: View,
        action: Int,
        y: Float,
        eventTime: Long,
        x: Float = 30f,
    ) {
        MotionEvent.obtain(0L, eventTime, action, x, y, 0).also { event ->
            if (view is NativeAppListView) {
                if (view.onInterceptTouchEvent(event)) view.onTouchEvent(event)
            } else {
                view.dispatchTouchEvent(event)
            }
            event.recycle()
        }
    }

    private fun clickListenerOf(view: View): View.OnClickListener? {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
            isAccessible = true
        }
        val listenerInfo = listenerInfoField.get(view) ?: return null
        val clickListenerField = listenerInfo.javaClass
            .getDeclaredField("mOnClickListener")
            .apply { isAccessible = true }
        return clickListenerField.get(listenerInfo) as? View.OnClickListener
    }

    private companion object {
        const val REAL_ICON_COLOR: Int = Color.MAGENTA
        const val FALLBACK_COLOR: Int = Color.GREEN
    }
}
