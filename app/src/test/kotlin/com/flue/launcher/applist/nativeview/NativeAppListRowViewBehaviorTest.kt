package com.flue.launcher.applist.nativeview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
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
    fun nativeHostDisablesFrameworkOverscrollWhenOwningTheEdgeTranslation() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication())

        assertEquals(View.OVER_SCROLL_ALWAYS, view.overScrollMode)
        assertSame(springEdgeEffectsOf(view), view.edgeEffectFactory)

        view.release()
    }

    @Test
    fun nativeHostDisablesNestedScrollingBeforeOwningRecyclerRemainders() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication())

        assertFalse(view.isNestedScrollingEnabled)

        view.release()
    }

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
        assertEquals(0, view.computeVerticalScrollOffset())
        assertTrue(view.computeVerticalScrollRange() > view.computeVerticalScrollExtent())

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
        assertTrue(view.computeVerticalScrollOffset() > 0)
        assertNull(manager.findViewByPosition(0))
        assertTrue("scrolled childCount=${view.childCount}", view.childCount < 36)
        view.release()
    }

    @Test
    fun fractionalHoneycombScrollRangeReachesBothExactEndpoints() {
        val entries = (0 until 24).map { appEntry("component:fractional-scroll-$it") }
        val config = drawingConfig().copy(
            presentation = NativeAppListPresentation.HONEYCOMB,
            iconSizePx = 64,
            rowHeightPx = 66,
            honeycombCellSizePx = 64f,
            honeycombNarrowColumns = 3,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val geometry = NativeHoneycombGeometry.create(
            itemCount = entries.size,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
            cellSizePx = 64f,
        )
        assertTrue(
            "the fixture must keep a fractional range",
            geometry.minimumScrollOffsetPx % 1f != 0f,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(config)
            setLayoutStrategy(HoneycombNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        val manager = view.layoutManager as NativeHoneycombLayoutManager

        view.scrollBy(0, 10_000)
        assertEquals(
            geometry.minimumScrollOffsetPx,
            spatialScrollOffsetOf(manager),
            0f,
        )
        assertFalse(view.canScrollVertically(1))

        view.scrollBy(0, -10_000)
        assertEquals(
            geometry.maximumScrollOffsetPx,
            spatialScrollOffsetOf(manager),
            0f,
        )
        assertFalse(view.canScrollVertically(-1))
        view.release()
    }

    @Test
    fun denseGridHalfPixelRangeRoundTripConservesChildPositionAndConsumedDelta() {
        val entries = (0 until 2).map { appEntry("component:half-pixel-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 1,
                    denseGridHorizontalPaddingPx = 0f,
                    denseGridItemSpacingPx = 4f,
                    denseGridItemAspectRatio = 1.6f,
                    denseGridMinimumItemWidthPx = 1f,
                    denseGridMinimumItemHeightPx = 1f,
                    denseGridMinimumVerticalPaddingPx = 0f,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 100, height = 100)
        val manager = view.layoutManager as NativeDenseGridLayoutManager
        val initialTop = requireNotNull(manager.findViewByPosition(0)).top
        var consumedSum = 0
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                consumedSum += dy
            }
        })

        repeat(3) {
            view.scrollBy(0, 10_000)
            view.scrollBy(0, -10_000)
        }

        assertEquals(initialTop, requireNotNull(manager.findViewByPosition(0)).top)
        assertEquals(0, consumedSum)
        view.release()
    }

    @Test
    fun denseGridLayoutManagerKeepsLegacyGeometryAndBoundedChildren() {
        val entries = (0 until 100).map { appEntry("component:dense-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 5,
                    denseGridHorizontalPaddingPx = 8f,
                    denseGridItemSpacingPx = 4f,
                    denseGridItemAspectRatio = 0.92f,
                    denseGridMinimumItemWidthPx = 48f,
                    denseGridMinimumItemHeightPx = 48f,
                    denseGridMinimumVerticalPaddingPx = 8f,
                    denseGridIconInnerPaddingPx = 3f,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
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

        val manager = view.layoutManager as NativeDenseGridLayoutManager
        val first = requireNotNull(manager.findViewByPosition(0))
        assertEquals(160f, first.top + first.height / 2f, 1f)
        assertEquals(58, first.width)
        assertEquals(63, first.height)
        assertTrue("initial childCount=${view.childCount}", view.childCount < 36)
        assertEquals(0, view.computeVerticalScrollOffset())

        view.scrollBy(0, 300)
        assertTrue(view.computeVerticalScrollOffset() > 0)
        assertNull(manager.findViewByPosition(0))
        assertTrue("scrolled childCount=${view.childCount}", view.childCount < 36)
        view.release()
    }

    @Test
    fun denseGeometryChangeRelaysOutEveryAttachedChildBeforeTheNextScroll() {
        val entries = (0 until 100).map { appEntry("component:dense-config-$it") }
        val initial = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            denseGridHorizontalPaddingPx = 8f,
            denseGridItemSpacingPx = 4f,
            denseGridItemAspectRatio = 0.92f,
            denseGridMinimumItemWidthPx = 48f,
            denseGridMinimumItemHeightPx = 48f,
            denseGridMinimumVerticalPaddingPx = 8f,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initial)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        assertEquals(58, requireNotNull(view.layoutManager?.findViewByPosition(0)).width)

        view.setConfig(initial.copy(denseGridColumns = 3))
        view.scrollBy(0, 1)

        val widths = (0 until view.childCount).map { view.getChildAt(it).width }.toSet()
        assertEquals(setOf(99), widths)
        view.release()
    }

    @Test
    fun spatialChildrenRemainInAdapterOrderAfterScrollingDownAndBackUp() {
        val entries = (0 until 100).map { appEntry("component:dense-order-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 5,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)

        view.scrollBy(0, 500)
        view.scrollBy(0, -300)

        val positions = (0 until view.childCount).map { index ->
            view.getChildAdapterPosition(view.getChildAt(index))
        }
        assertEquals(positions.sorted(), positions)
        view.release()
    }

    @Test
    fun denseGeometryChangeKeepsTheStableCenterItemAtTheSameScreenY() {
        val entries = (0 until 100).map { appEntry("component:dense-anchor-$it") }
        val initial = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initial)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        view.scrollBy(0, 500)
        val anchor = closestChildToViewportCenter(view)
        val anchorKey = (view.getChildViewHolder(anchor) as
            NativeAppListAdapter.AppItemViewHolder).boundKeyOrNull()
        val anchorCenterY = anchor.top + anchor.height / 2f

        view.setConfig(initial.copy(denseGridColumns = 3))
        measureAndLayout(view, width = 320, height = 320)

        val attached = (0 until view.childCount)
            .map(view::getChildAt)
        val anchoredChild = attached.firstOrNull { child ->
                (view.getChildViewHolder(child) as
                    NativeAppListAdapter.AppItemViewHolder).boundKeyOrNull() == anchorKey
            }
        val attachedKeys = attached.map { child ->
            (view.getChildViewHolder(child) as
                NativeAppListAdapter.AppItemViewHolder).boundKeyOrNull()
        }
        assertTrue("anchor=$anchorKey attached=$attachedKeys", anchoredChild != null)
        assertEquals(
            anchorCenterY,
            requireNotNull(anchoredChild).top + anchoredChild.height / 2f,
            1f,
        )
        view.release()
    }

    @Test
    fun viewportResizeKeepsThePreviouslyCenteredStableKeyAtTheNewViewportCenter() {
        val entries = (0 until 100).map { appEntry("component:resize-anchor-$it") }
        val config = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(config)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        view.scrollToPosition(40)
        measureAndLayout(view, width = 320, height = 320)
        val oldAnchor = requireNotNull(view.layoutManager?.findViewByPosition(40))
        val oldAnchorKey = (view.getChildViewHolder(oldAnchor) as
            NativeAppListAdapter.AppItemViewHolder).boundKeyOrNull()
        assertEquals(160f, oldAnchor.top + oldAnchor.height / 2f, 1f)

        measureAndLayout(view, width = 240, height = 480)

        val anchoredChild = (0 until view.childCount)
            .map(view::getChildAt)
            .firstOrNull { child ->
                (view.getChildViewHolder(child) as
                    NativeAppListAdapter.AppItemViewHolder).boundKeyOrNull() == oldAnchorKey
            }
        assertTrue("anchor=$oldAnchorKey", anchoredChild != null)
        assertEquals(
            240f,
            requireNotNull(anchoredChild).top + anchoredChild.height / 2f,
            1f,
        )
        view.release()
    }

    @Test
    fun geometryChangeAndInitialResetTokenAlwaysStartAtPositionZero() {
        val entries = (0 until 100).map { appEntry("component:dense-reset-$it") }
        val initial = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            centerEdgeItems = false,
            fisheyeEnabled = false,
            initialResetToken = 1L,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initial)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        view.scrollBy(0, 500)
        assertNull(view.layoutManager?.findViewByPosition(0))

        view.setConfig(
            initial.copy(
                denseGridColumns = 3,
                initialResetToken = 2L,
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        measureAndLayout(view, width = 320, height = 320)

        val first = requireNotNull(view.layoutManager?.findViewByPosition(0))
        assertEquals(160f, first.top + first.height / 2f, 1f)
        assertEquals(0, view.computeVerticalScrollOffset())
        view.release()
    }

    @Test
    fun emptyCommitClearsPendingGeometryAnchorBeforeRepopulation() {
        val initialEntries = (0 until 100).map { appEntry("component:old-anchor-$it") }
        val replacementEntries = (0 until 100).map { appEntry("component:new-anchor-$it") }
        val initial = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initial)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, initialEntries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        view.scrollBy(0, 500)
        view.setConfig(initial.copy(denseGridColumns = 3))

        val adapter = view.adapter as NativeAppListAdapter
        adapter.clearSynchronously()
        measureAndLayout(view, width = 320, height = 320)
        assertEquals(0, view.childCount)

        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(2L, replacementEntries),
                iconsByKey = emptyMap(),
            ),
        )
        measureAndLayout(view, width = 320, height = 320)

        val first = requireNotNull(view.layoutManager?.findViewByPosition(0))
        assertEquals(160f, first.top + first.height / 2f, 1f)
        assertEquals(0, view.computeVerticalScrollOffset())
        view.release()
    }

    @Test
    fun emptyCommitClearsPendingGeometryAnchorWithoutAnIntermediateEmptyLayout() {
        val initialEntries = (0 until 100).map { appEntry("component:old-anchor-$it") }
        val replacementEntries = (0 until 100).map { appEntry("component:new-anchor-$it") }
        val initial = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            centerEdgeItems = false,
            fisheyeEnabled = false,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(initial)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, initialEntries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        view.scrollBy(0, 500)
        view.setConfig(initial.copy(denseGridColumns = 3))

        val adapter = view.adapter as NativeAppListAdapter
        adapter.clearSynchronously()
        assertTrue(adapter.currentList.isEmpty())
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(2L, replacementEntries),
                iconsByKey = emptyMap(),
            ),
        )
        measureAndLayout(view, width = 320, height = 320)

        val first = requireNotNull(view.layoutManager?.findViewByPosition(0))
        assertEquals(160f, first.top + first.height / 2f, 1f)
        assertEquals(0, view.computeVerticalScrollOffset())
        view.release()
    }

    @Test
    fun denseGridUsesSpatialNearestTargetAndTracksDragInBothAxes() {
        val entries = (0 until 30).map { appEntry("component:dense-drag-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 5,
                    denseGridHorizontalPaddingPx = 8f,
                    denseGridItemSpacingPx = 4f,
                    denseGridItemAspectRatio = 0.92f,
                    denseGridMinimumItemWidthPx = 48f,
                    denseGridMinimumItemHeightPx = 48f,
                    denseGridMinimumVerticalPaddingPx = 8f,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
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
        val manager = view.layoutManager as NativeDenseGridLayoutManager
        val source = requireNotNull(manager.findViewByPosition(0))
        val target = requireNotNull(manager.findViewByPosition(4))
        val sourceX = source.left + source.width / 2f
        val sourceY = source.top + source.height / 2f
        val targetX = target.left + target.width / 2f
        val targetY = target.top + target.height / 2f

        dispatch(view, MotionEvent.ACTION_DOWN, x = sourceX, y = sourceY, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatch(
            view,
            MotionEvent.ACTION_MOVE,
            x = sourceX + 22f,
            y = sourceY,
            eventTime = 621L,
        )
        val overlay = dragOverlayViewOf(view)
        assertTrue(overlay != null)
        // Starting the drag must not make the overlay jump away from its source.
        assertEquals(0f, requireNotNull(overlay).translationX, 1f)
        dispatch(
            view,
            MotionEvent.ACTION_MOVE,
            x = targetX,
            y = targetY,
            eventTime = 700L,
        )

        assertTrue(requireNotNull(overlay).translationX > 100f)
        assertFalse(commands.any { command -> command is AppListCommand.Move })
        assertEquals(0f, source.alpha, 0f)
        dispatch(view, MotionEvent.ACTION_UP, x = targetX, y = targetY, eventTime = 701L)
        assertEquals(1, commands.count { command -> command is AppListCommand.Move })
        view.release()
        activity.finish()
    }

    @Test
    fun scrollIndicatorFrameDriverSettlesWithoutWaitingForAListDraw() {
        val entries = (0 until 30).map { appEntry("component:indicator-frame-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig())
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

        dispatch(view, MotionEvent.ACTION_DOWN, x = 160f, y = 160f, eventTime = 0L)
        // The frame driver must advance its own clock. This idle window must
        // return even if no RecyclerView draw callback is delivered.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            Duration.ofMillis(NATIVE_SCROLL_INDICATOR_FADE_IN_MS + 32L),
        )

        dispatch(view, MotionEvent.ACTION_UP, x = 160f, y = 160f, eventTime = 200L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            Duration.ofMillis(
                NATIVE_SCROLL_INDICATOR_HOLD_MS +
                    NATIVE_SCROLL_INDICATOR_FADE_OUT_MS +
                    32L,
            ),
        )

        view.release()
        activity.finish()
    }

    @Test
    fun spatialVisualsFollowTheDisplayedPositionDuringAMoveAnimation() {
        val entries = (0 until 20).map { appEntry("component:visual-move-$it") }
        val config = drawingConfig().copy(
            presentation = NativeAppListPresentation.DENSE_GRID,
            denseGridColumns = 5,
            denseGridHorizontalPaddingPx = 8f,
            denseGridItemSpacingPx = 4f,
            denseGridItemAspectRatio = 0.92f,
            centerEdgeItems = false,
            fisheyeEnabled = true,
            fisheyeStrength = 1f,
            honeycombFisheyeRangeRows = 4,
            edgeSpacingCompressionEnabled = true,
        )
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(config)
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        val manager = view.layoutManager as NativeDenseGridLayoutManager
        val child = requireNotNull(manager.findViewByPosition(0)) as NativeAppListRowView
        child.setMoveTranslation(0f, 140f)
        val displayedCenterX = child.left + child.width / 2f
        val displayedCenterY = child.top + child.height / 2f + 140f
        val expected = nativeDenseGridChildVisual(
            centerX = displayedCenterX,
            centerY = displayedCenterY,
            viewportWidth = 320f,
            viewportHeight = 320f,
            config = config.legalized(),
        )

        manager.reapplyChildVisuals()

        assertTrue(expected.scale < 1f)
        assertEquals(expected.scale, child.visualScaleForOverlay(), 0.001f)
        assertEquals(expected.alpha, child.alpha, 0.001f)
        view.release()
    }

    @Test
    fun switchingFromLinearToSpatialGridClearsLinearViewportPadding() {
        val entries = (0 until 20).map { appEntry("component:grid-padding-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(centerEdgeItems = true, rowHeightPx = 80))
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
        assertEquals(120, view.paddingTop)

        view.setConfig(
            drawingConfig().copy(
                presentation = NativeAppListPresentation.DENSE_GRID,
                centerEdgeItems = false,
            ),
        )
        view.setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
        view.layout(0, 0, 320, 320)

        assertEquals(0, view.paddingTop)
        assertEquals(0, view.paddingBottom)
        view.release()
    }

    @Test
    fun spatialFolderHitTracksTheScaledRenderedIconCore() {
        val entries = (0 until 10).map { appEntry("component:grid-folder-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 5,
                    denseGridHorizontalPaddingPx = 8f,
                    denseGridItemSpacingPx = 4f,
                    denseGridItemAspectRatio = 0.92f,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
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
        val target = requireNotNull(view.layoutManager?.findViewByPosition(1))
        val centerX = target.left + target.width / 2f
        val centerY = target.top + target.height / 2f

        assertEquals(entries[1].key, folderKeyAtOf(view, centerX, centerY))
        assertNull(
            folderKeyAtOf(
                view,
                target.left + target.width * 0.10f,
                centerY,
            ),
        )
        val targetRow = target as NativeAppListRowView
        targetRow.setVisualScale(0.5f)
        assertNull(
            folderKeyAtOf(
                view,
                centerX + targetRow.iconWidthForSafeGeometry() * 0.30f,
                centerY,
            ),
        )
        view.release()
    }

    @Test
    fun folderHitMapsTheCurrentPreviewSlotInsteadOfItsOriginalOccupant() {
        val entries = (0 until 5).map { appEntry("component:preview-folder-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            itemAnimator = null
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.DENSE_GRID,
                    denseGridColumns = 5,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                ),
            )
            setLayoutStrategy(DenseGridNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 160)
        val host = dragHostOf(view)
        setDraggedSourceKey(view, entries[0].key)
        assertTrue(host.beginDragPreview(entries[0].key))
        assertTrue(host.previewMove(entries[0].key, entries[2].key))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))
        val displayedTarget = (0 until view.childCount)
            .map { view.getChildAt(it) as NativeAppListRowView }
            .single { it.boundStableKeyOrNull() == entries[2].key }

        assertEquals(
            entries[2].key,
            folderKeyAtOf(
                view,
                displayedTarget.iconCenterXInParentWithoutFlow(),
                displayedTarget.iconCenterYInParentWithoutFlow(),
            ),
        )
        view.release()
    }

    @Test
    fun flowCurveDoesNotReadTheReorderPreviewTranslation() {
        val entries = (0 until 12).map { appEntry("component:flow-preview-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.FLOW,
                    flowColumns = 3,
                    flowCellSizePx = 70f,
                    flowCrescentBulgeFraction = 1.5f,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
                ),
            )
            setLayoutStrategy(FlowNativeAppListLayoutStrategy)
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        measureAndLayout(view, width = 320, height = 320)
        val manager = view.layoutManager as NativeFlowLayoutManager
        val row = requireNotNull(manager.findViewByPosition(0)) as NativeAppListRowView
        val originalFlowX = row.translationX - row.previewTranslationX()

        row.setPreviewTranslation(0f, 90f)
        manager.reapplyChildVisuals()

        assertEquals(originalFlowX, row.translationX - row.previewTranslationX(), 0.001f)
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

        assertFalse(commands.any { it is AppListCommand.Move })
        dispatch(view, MotionEvent.ACTION_UP, x = 160f, y = 160f, eventTime = 701L)
        assertEquals(1, commands.count { it is AppListCommand.Move })
        view.release()
        activity.finish()
    }

    @Test
    fun spatialScrollKeepsAnAttachedDragSourceHiddenBehindItsOverlay() {
        val entries = (0 until 40).map { appEntry("component:hidden-drag-$it") }
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

        view.scrollBy(0, 20)

        assertSame(source, view.layoutManager?.findViewByPosition(0))
        assertEquals(0f, source.alpha, 0f)
        dispatch(view, MotionEvent.ACTION_CANCEL, x = sourceX, y = sourceY, eventTime = 700L)
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
    fun presentationChangeCancelsPendingGestureBeforeTheNextProfileCanApply() {
        val entry = appEntry("component:presentation-change")
        val commands = mutableListOf<AppListCommand>()
        val initialConfig = drawingConfig().copy(
            presentation = NativeAppListPresentation.LINEAR,
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
        view.setConfig(initialConfig.copy(presentation = NativeAppListPresentation.HONEYCOMB))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))

        assertFalse(commands.any { it is AppListCommand.ShowActions })
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
    fun discardedVirtualPreviewRestoresAuthoritativeOrderWithoutSubmittingADiff() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val initialEntries = listOf(appEntry("a"), appEntry("b"), appEntry("c"))
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, initialEntries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(adapter.beginDragPreview(AppItemKey("a")))
        assertTrue(adapter.previewMove(AppItemKey("a"), AppItemKey("b")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("b", "a", "c"), adapter.currentKeysView().map { it.value })

        adapter.discardDragPreviewAwaitingAuthoritative()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("a", "b", "c"), adapter.currentKeysView().map { it.value })
        assertEquals(listOf("a", "b", "c"), adapter.currentList.map { it.stableKey.value })
    }

    @Test
    fun dragHoverStaysVirtualAndAcceptedReleaseSubmitsExactlyOnce() {
        val previewOrders = mutableListOf<List<String>>()
        val adapter = NativeAppListAdapter(
            commandSink = {},
            onDragPreviewOrderChanged = { _, preview ->
                previewOrders += preview.map { it.value }
            },
        )
        val entries = listOf(appEntry("a"), appEntry("b"), appEntry("c"))
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(adapter.beginDragPreview(AppItemKey("a")))
        assertTrue(adapter.previewMove(AppItemKey("a"), AppItemKey("b")))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("b", "a", "c"), adapter.currentKeysView().map { it.value })
        assertEquals(listOf("a", "b", "c"), adapter.currentList.map { it.stableKey.value })
        assertEquals(listOf("b", "a", "c"), previewOrders.last())

        val command = requireNotNull(adapter.finishDragPreview(commit = true))
        assertEquals(AppItemKey("a"), command.key)
        adapter.completeDragPreview(accepted = true)

        // The optimistic snapshot changes immediately; the actual ListAdapter
        // diff is asynchronous by design and is verified by the controller's
        // single-dispatch tests rather than by a paused Robolectric scheduler.
        assertEquals(listOf("b", "a", "c"), adapter.currentKeysView().map { it.value })
    }

    @Test
    fun acceptedFolderPreviewCleanupTransfersAcrossAuthoritativeGenerationsExactlyOnce() {
        val finished = mutableListOf<Boolean>()
        val adapter = NativeAppListAdapter(
            commandSink = {},
            onDragPreviewFinished = { committed -> finished += committed },
        )
        val entries = listOf(appEntry("a"), appEntry("b"), appEntry("c"))
        val authoritativeEntries = listOf(entries[1], entries[2])
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        assertTrue(adapter.beginDragPreview(AppItemKey("a")))
        assertTrue(adapter.previewFolderCommit(AppItemKey("a"), AppItemKey("b")))
        adapter.discardDragPreviewAwaitingAuthoritative()

        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(2L, authoritativeEntries),
                iconsByKey = emptyMap(),
            ),
        )
        var finalAuthorityCommitted = false
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(3L, authoritativeEntries),
                iconsByKey = emptyMap(),
            ),
        ) {
            finalAuthorityCommitted = true
        }

        assertTrue(
            "latest authoritative differ generation did not commit",
            idleMainLooperUntil { finalAuthorityCommitted },
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(true), finished)
    }

    @Test
    fun rejectedPreviewClearsImmediatelyAndAuthorityCannotRelabelItCommitted() {
        val finished = mutableListOf<Boolean>()
        val adapter = NativeAppListAdapter(
            commandSink = {},
            onDragPreviewFinished = { committed -> finished += committed },
        )
        val entries = listOf(appEntry("a"), appEntry("b"), appEntry("c"))
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        assertTrue(adapter.beginDragPreview(AppItemKey("a")))
        assertTrue(adapter.previewMove(AppItemKey("a"), AppItemKey("b")))
        requireNotNull(adapter.finishDragPreview(commit = true))

        adapter.completeDragPreview(accepted = false)

        assertEquals(listOf(false), finished)
        var authorityCommitted = false
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(2L, entries),
                iconsByKey = emptyMap(),
            ),
        ) {
            authorityCommitted = true
        }
        assertTrue(
            "authoritative differ generation did not commit",
            idleMainLooperUntil { authorityCommitted },
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(false), finished)
    }

    @Test
    fun authoritativeSubmitSupersedingAcceptedPreviewClearsHostOrderBeforeChildReattach() {
        val entries = (0 until 12).map { appEntry("component:preview-race-$it") }
        val authoritativeEntries = entries.toMutableList().apply {
            add(0, removeAt(1))
        }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            itemAnimator = null
            setConfig(
                drawingConfig().copy(
                    rowHeightPx = 40,
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 80)
        val adapter = view.adapter as NativeAppListAdapter
        val source = entries[0].key
        val target = entries[1].key

        assertTrue(adapter.beginDragPreview(source))
        assertTrue(adapter.previewMove(source, target))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(180L))
        val virtuallyMovedSource = requireNotNull(
            view.layoutManager?.findViewByPosition(0),
        ) as NativeAppListRowView
        assertTrue(virtuallyMovedSource.previewTranslationY() != 0f)

        requireNotNull(adapter.finishDragPreview(commit = true))
        adapter.completeDragPreview(accepted = true)
        var authoritativeCommitted = false
        // Do not idle the optimistic diff. This authoritative generation must
        // inherit responsibility for clearing the host's virtual preview.
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(2L, authoritativeEntries),
                iconsByKey = emptyMap(),
            ),
        ) {
            authoritativeCommitted = true
        }

        assertTrue(
            "authoritative differ generation did not commit",
            idleMainLooperUntil { authoritativeCommitted },
        )
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(32L))
        assertTrue(dragPreviewOrderOf(view).isEmpty())
        assertTrue(
            (0 until view.childCount).all { index ->
                val row = view.getChildAt(index) as NativeAppListRowView
                row.previewTranslationX() == 0f && row.previewTranslationY() == 0f
            },
        )

        // Robolectric keeps RecyclerView's overscan children attached even at
        // the scroll boundary. Explicitly remount the same adapter so this
        // test exercises the intended child-reattach contract deterministically.
        view.adapter = null
        assertEquals(0, view.childCount)
        view.adapter = adapter
        measureAndLayout(view, width = 100, height = 80)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(32L))

        val reattachedFirst = requireNotNull(
            view.layoutManager?.findViewByPosition(0),
        ) as NativeAppListRowView
        assertEquals(0f, reattachedFirst.previewTranslationX(), 0f)
        assertEquals(0f, reattachedFirst.previewTranslationY(), 0f)
        view.release()
        activity.finish()
    }

    @Test
    fun acceptedFolderCommitRemovesBothAppsUntilNewFolderSnapshotArrives() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val entries = listOf(appEntry("a"), appEntry("b"), appEntry("c"))
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(adapter.beginDragPreview(AppItemKey("a")))
        assertTrue(adapter.previewFolderCommit(AppItemKey("a"), AppItemKey("b")))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("c"), adapter.currentKeysView().map { it.value })
    }

    @Test
    fun acceptedFolderCommitKeepsAnExistingTargetFolderVisible() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val entries = listOf(
            appEntry("app"),
            appEntry("folder").copy(type = AppItemType.FOLDER, launchTarget = null),
            appEntry("tail"),
        )
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(adapter.beginDragPreview(AppItemKey("app")))
        assertTrue(adapter.previewFolderCommit(AppItemKey("app"), AppItemKey("folder")))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("folder", "tail"), adapter.currentKeysView().map { it.value })
    }

    @Test
    fun acceptedFolderCommitKeepsASourceFolderThatAbsorbsAnAppVisible() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val entries = listOf(
            appEntry("folder").copy(type = AppItemType.FOLDER, launchTarget = null),
            appEntry("app"),
            appEntry("tail"),
        )
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(adapter.beginDragPreview(AppItemKey("folder")))
        assertTrue(adapter.previewFolderCommit(AppItemKey("folder"), AppItemKey("app")))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("folder", "tail"), adapter.currentKeysView().map { it.value })
    }

    @Test
    fun acceptedFolderCommitMergesTwoFoldersIntoTheTargetFolder() {
        val adapter = NativeAppListAdapter(commandSink = {})
        val entries = listOf(
            appEntry("source-folder").copy(type = AppItemType.FOLDER, launchTarget = null),
            appEntry("target-folder").copy(type = AppItemType.FOLDER, launchTarget = null),
            appEntry("tail"),
        )
        adapter.submitContent(
            NativeAppListContent(
                snapshot = AppListSnapshot.create(1L, entries),
                iconsByKey = emptyMap(),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(adapter.beginDragPreview(AppItemKey("source-folder")))
        assertTrue(
            adapter.previewFolderCommit(
                AppItemKey("source-folder"),
                AppItemKey("target-folder"),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("target-folder", "tail"), adapter.currentKeysView().map { it.value })
    }

    @Test
    fun folderTargetPolicyAcceptsAppsAndExistingFoldersButRejectsShortcuts() {
        val source = boundEntryRow(appEntry("source"))
        val appTarget = boundEntryRow(appEntry("target"))
        val folderTarget = boundEntryRow(
            appEntry("folder").copy(type = AppItemType.FOLDER, launchTarget = null),
        )
        val shortcutTarget = boundEntryRow(
            appEntry("shortcut").copy(type = AppItemType.SHORTCUT),
        )

        assertTrue(nativeFolderTargetAllowed(source, appTarget))
        assertTrue(nativeFolderTargetAllowed(source, folderTarget))
        assertFalse(nativeFolderTargetAllowed(source, shortcutTarget))
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
        // Linear mode deliberately retains one full row of transformed
        // overscan to prevent edge flashes. Move beyond that buffer so this
        // test exercises a real recycle/reattach instead of an attached row.
        view.scrollBy(0, 100)
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
    fun roundLinearDragMovesTheAdjacentRowWhenThePointerStopsAtTheirMidpoint() {
        val entries = listOf(
            appEntry(key = "component:round-linear-a"),
            appEntry(key = "component:round-linear-b"),
            appEntry(key = "component:round-linear-c"),
        )
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.LINEAR,
                    deviceShape = NativeDeviceShape.ROUND,
                    centerEdgeItems = false,
                    allowFolderCreation = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val source = requireNotNull(view.layoutManager?.findViewByPosition(0))
            as NativeAppListRowView
        val target = requireNotNull(view.layoutManager?.findViewByPosition(1))
            as NativeAppListRowView
        val sourceX = source.iconCenterXInParentForHitTest()
        val sourceY = source.iconCenterYInParentForHitTest()
        val targetY = target.iconCenterYInParentForHitTest()
        val midpointY = (sourceY + targetY) / 2f
        val betweenAdjacentRowsY = midpointY + 4f

        dispatch(view, MotionEvent.ACTION_DOWN, x = sourceX, y = sourceY, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatch(view, MotionEvent.ACTION_MOVE, x = sourceX, y = betweenAdjacentRowsY, eventTime = 621L)
        dispatch(view, MotionEvent.ACTION_MOVE, x = sourceX, y = betweenAdjacentRowsY, eventTime = 640L)

        val centers = (0 until view.childCount).joinToString { index ->
            val row = view.getChildAt(index) as NativeAppListRowView
            "${row.boundStableKeyOrNull()?.value}:layout=${row.top + row.height / 2f}," +
                "hit=${row.iconCenterYInParentForHitTest()},preview=${row.previewTranslationY()}"
        }
        assertEquals(
            "source=$sourceY target=$targetY midpoint=$midpointY sample=$betweenAdjacentRowsY centers=$centers",
            listOf(entries[1].key, entries[0].key, entries[2].key),
            dragPreviewOrderOf(view),
        )
        assertTrue(commands.contains(AppListCommand.DismissActions))

        dispatch(view, MotionEvent.ACTION_CANCEL, x = sourceX, y = betweenAdjacentRowsY, eventTime = 641L)
        view.release()
        activity.finish()
    }

    @Test
    fun roundLinearDragReleasesTheAdjacentRowWhenPointerLeavesTheGap() {
        val entries = listOf(
            appEntry(key = "component:round-linear-gap-a"),
            appEntry(key = "component:round-linear-gap-b"),
            appEntry(key = "component:round-linear-gap-c"),
        )
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    presentation = NativeAppListPresentation.LINEAR,
                    deviceShape = NativeDeviceShape.ROUND,
                    centerEdgeItems = false,
                    allowFolderCreation = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val source = requireNotNull(view.layoutManager?.findViewByPosition(0))
            as NativeAppListRowView
        val target = requireNotNull(view.layoutManager?.findViewByPosition(1))
            as NativeAppListRowView
        val sourceX = source.iconCenterXInParentForHitTest()
        val sourceY = source.iconCenterYInParentForHitTest()
        val targetY = target.iconCenterYInParentForHitTest()
        val midpointY = (sourceY + targetY) / 2f
        val gapY = midpointY + 8f

        dispatch(view, MotionEvent.ACTION_DOWN, x = sourceX, y = sourceY, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatch(view, MotionEvent.ACTION_MOVE, x = sourceX, y = gapY, eventTime = 621L)
        dispatch(view, MotionEvent.ACTION_MOVE, x = sourceX, y = gapY, eventTime = 640L)
        assertEquals(
            listOf(entries[1].key, entries[0].key, entries[2].key),
            dragPreviewOrderOf(view),
        )

        // Move back toward the gap's other side without first crossing the
        // source's current virtual slot. The neighbour must yield immediately.
        dispatch(view, MotionEvent.ACTION_MOVE, x = sourceX, y = midpointY + 3f, eventTime = 660L)

        assertEquals(
            listOf(entries[0].key, entries[1].key, entries[2].key),
            dragPreviewOrderOf(view),
        )

        dispatch(view, MotionEvent.ACTION_CANCEL, x = sourceX, y = midpointY + 3f, eventTime = 661L)
        view.release()
        activity.finish()
    }

    @Test
    fun recyclerHandoffCancelsPendingNativeLongPress() {
        val entries = (0 until 20).map { appEntry("component:handoff-$it") }
        val commands = mutableListOf<AppListCommand>()
        val context = RuntimeEnvironment.getApplication()
        val view = NativeAppListView(context).apply {
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
        measureAndLayout(view, width = 100, height = 120)
        val sourceRow = requireNotNull(view.layoutManager?.findViewByPosition(0))
        val handoffDistance = ViewConfiguration.get(context).scaledTouchSlop + 1
        val dragStartThreshold = 20f * context.resources.displayMetrics.density
        assertTrue(handoffDistance < dragStartThreshold)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        dispatchThroughHierarchy(
            view,
            MotionEvent.ACTION_MOVE,
            y = 20f - handoffDistance,
            eventTime = 16L,
        )
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))

        assertFalse(commands.any { it is AppListCommand.ShowActions })
        assertTrue(sourceRow.isLongClickable)
        dispatchThroughHierarchy(
            view,
            MotionEvent.ACTION_CANCEL,
            y = 20f - handoffDistance,
            eventTime = 637L,
        )
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
    fun applePressOverlayUsesTheCircularIconPathInsteadOfTheWholeRow() {
        val view = NativeAppListRowView(RuntimeEnvironment.getApplication())
        val output = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        view.bind(
            row = boundRow(solidBitmap(Color.TRANSPARENT)),
            config = NativeAppListResolvedConfig.from(
                drawingConfig().copy(
                    iconShape = NativeAppListIconShape.CIRCLE,
                    iconShadowEnabled = false,
                    palette = drawingConfig().palette.copy(
                        labelColorArgb = 0L,
                        pressedOverlayColorArgb = 0xffff0000L,
                    ),
                ),
            ),
        )
        measureAndLayout(view)
        view.isPressed = true

        view.draw(Canvas(output))

        assertTrue(Color.alpha(output.getPixel(14, 20)) > 0)
        assertEquals(Color.TRANSPARENT, output.getPixel(80, 5))
    }

    @Test
    fun iconShadowKeepsElevationStableAndFadesThroughOutlineAlpha() {
        // 阴影改走真平台高程（与 Compose 版 shadowElevation 同源机制），
        // 不再在自绘画布里画 BlurMaskFilter 近似（黑环来源）。
        val view = NativeAppListRowView(RuntimeEnvironment.getApplication())
        val density = view.resources.displayMetrics.density
        view.bind(
            row = boundRow(solidBitmap(REAL_ICON_COLOR)),
            config = NativeAppListResolvedConfig.from(
                drawingConfig().copy(
                    iconShape = NativeAppListIconShape.CIRCLE,
                    iconShadowEnabled = true,
                ),
            ),
        )
        measureAndLayout(view)

        assertEquals(8f * density, view.elevation, 0.01f)

        // Alpha no longer switches elevation recipes at the transition edge.
        // The outline alpha fades the platform shadow continuously instead.
        view.alpha = 0f
        assertEquals(8f * density, view.elevation, 0.01f)
        val hiddenOutline = Outline()
        requireNotNull(view.outlineProvider).getOutline(view, hiddenOutline)
        assertEquals(0f, hiddenOutline.alpha, 0.01f)
        view.alpha = 1f
        assertEquals(8f * density, view.elevation, 0.01f)
        val visibleOutline = Outline()
        requireNotNull(view.outlineProvider).getOutline(view, visibleOutline)
        assertEquals(1f, visibleOutline.alpha, 0.01f)

        // 关闭开关时高程归零。
        view.applyConfig(
            NativeAppListResolvedConfig.from(
                drawingConfig().copy(
                    iconShape = NativeAppListIconShape.CIRCLE,
                    iconShadowEnabled = false,
                ),
            ),
        )
        assertEquals(0f, view.elevation, 0.01f)
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
    fun edgeFadeMasksStayStableWhileTheParentSceneTransitionsInactive() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    active = false,
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
    fun edgeFadeMaskCanBeOwnedByTheOuterTransitionLayer() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    drawEdgeFadeMask = false,
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

        assertEquals(Color.TRANSPARENT, output.getPixel(50, 0))
        assertEquals(Color.TRANSPARENT, output.getPixel(50, 99))
        view.release()
    }

    @Test
    fun terminalActionUpConfirmsTheTopEdgeThresholdOnce() {
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
        repeat(3) {
            springEdgeEffectsOf(view).pull(FlueEdgePhysics.Edge.TOP, distance = 1f)
        }
        dispatch(view, MotionEvent.ACTION_UP, y = 93f, eventTime = 101L)

        assertEquals(1, requests)
        view.release()
        activity.finish()
    }

    @Test
    fun intermediateEdgeReleasePreservesRealUpAndCancelCannotRequestTheWatchFace() {
        var requests = 0
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onScrollToTop = { requests++ }
            setConfig(drawingConfig().copy(fisheyeEnabled = false, centerEdgeItems = false))
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)

        dispatch(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        repeat(3) {
            springEdgeEffectsOf(view).pull(FlueEdgePhysics.Edge.TOP, distance = 1f)
        }
        springEdgeEffectsOf(view).release(FlueEdgePhysics.Edge.TOP)
        dispatch(view, MotionEvent.ACTION_UP, y = 40f, eventTime = 101L)

        assertEquals(1, requests)

        dispatch(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 200L)
        repeat(3) {
            springEdgeEffectsOf(view).pull(FlueEdgePhysics.Edge.TOP, distance = 1f)
        }
        dispatch(view, MotionEvent.ACTION_CANCEL, y = 80f, eventTime = 301L)

        assertEquals(1, requests)
        view.release()
        activity.finish()
    }

    @Test
    fun frameworkEdgeReleaseStartsTheReboundImmediatelyEvenMidGesture() {
        // Contract inversion over the old finger guard: RecyclerView's
        // onRelease call sites are all legitimate, and rejecting them locked
        // the offset for the rest of the gesture while distance() stayed
        // non-zero — every fling in that window was silently absorbed.
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(fisheyeEnabled = false, centerEdgeItems = false))
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)
        val effects = springEdgeEffectsOf(view)

        effects.beginGesture()
        effects.pull(FlueEdgePhysics.Edge.TOP, distance = 0.5f)
        assertTrue(pullOffsetOf(view) > 0f)
        effects.release(FlueEdgePhysics.Edge.TOP)

        assertTrue("release must not wait for the terminal sample", effects.isAnimating)
        assertEquals(
            "an animating edge must be invisible to RecyclerView's scroll gate",
            0f,
            effects.distance(FlueEdgePhysics.Edge.TOP),
            0f,
        )

        // The window is still open; the terminal sample just closes it.
        assertTrue(effects.ownsPointerGesture)
        effects.endGesture(terminalUp = false)
        assertFalse(effects.ownsPointerGesture)
        view.release()
        activity.finish()
    }

    @Test
    fun longPressWithoutMoveDoesNotSwallowTheNextGesture() {
        val entries = (0 until 8).map { appEntry("component:latch-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)

        // Long-press without moving: the consumed terminal must not latch the
        // owned-touch flag across gestures.
        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        assertTrue(commands.any { it is AppListCommand.ShowActions })
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 640L)
        // The idle above let the framework re-lay the list at the full window
        // size, which makes the content fit and disables scrolling. Restore
        // the small viewport so the second gesture has scroll range again.
        measureAndLayout(view, width = 100, height = 120)

        // The very next gesture must scroll normally.
        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 100f, eventTime = 800L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 816L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 20f, eventTime = 832L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 848L)

        assertTrue(
            "the gesture after a stationary long-press must scroll the list",
            view.computeVerticalScrollOffset() > 0,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun longPressCancelledByParentDoesNotSwallowTheNextGesture() {
        val entries = (0 until 8).map { appEntry("component:latch-cancel-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        assertTrue(commands.any { it is AppListCommand.ShowActions })
        dispatchThroughHierarchy(view, MotionEvent.ACTION_CANCEL, y = 20f, eventTime = 640L)
        // Restore the small viewport after the framework's full-size relayout
        // during the idle (see longPressWithoutMoveDoesNotSwallowTheNextGesture).
        measureAndLayout(view, width = 100, height = 120)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 100f, eventTime = 800L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 816L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 20f, eventTime = 832L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 848L)

        assertTrue(
            "the gesture after a cancelled long-press must scroll the list",
            view.computeVerticalScrollOffset() > 0,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun downDuringInactiveSceneKeepsRecyclerAnchorsFreshForMidGestureActivation() {
        val entries = (0 until 8).map { appEntry("component:inactive-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    active = false,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)

        // DOWN lands while the scene is inactive (mid transition)…
        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 100f, eventTime = 0L)
        // …and the scene reactivates while the finger is still down.
        view.setConfig(
            drawingConfig().copy(
                active = true,
                centerEdgeItems = false,
                fisheyeEnabled = false,
                entryMotionEnabled = false,
            ),
        )
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 32L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 20f, eventTime = 48L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 64L)

        assertTrue(
            "a gesture straddling reactivation must scroll against fresh anchors",
            view.computeVerticalScrollOffset() > 0,
        )
        assertEquals(
            "no ownerless edge distance may survive an inactive terminal",
            0f,
            springEdgeEffectsOf(view).distance(FlueEdgePhysics.Edge.TOP),
            0f,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun inactiveSceneNeverDeliversTouchToChildRows() {
        val entries = (0 until 3).map { appEntry("component:blind-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    active = false,
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)
        val firstRow = requireNotNull(
            view.layoutManager?.findViewByPosition(0),
        ) as NativeAppListRowView

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(150L))

        assertFalse(
            "an invisible transitioning list must not press its rows",
            firstRow.isPressed,
        )
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 200L)
        view.release()
        activity.finish()
    }

    @Test
    fun externalGeometryChangeKeepsRowLongClickSuppressedWhileFingerIsDown() {
        val entries = (0 until 3).map { appEntry("component:suppress-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    centerEdgeItems = false,
                    fisheyeEnabled = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)
        val firstRow = requireNotNull(
            view.layoutManager?.findViewByPosition(0),
        ) as NativeAppListRowView

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        // Geometry changes under the live finger (presentation/scale switch).
        view.setConfig(
            drawingConfig().copy(
                centerEdgeItems = false,
                fisheyeEnabled = false,
                entryMotionEnabled = false,
                rowHeightPx = 48,
            ),
        )
        // Neither the controller's timer nor the row's own long-press may fire
        // at stale coordinates while the finger is still down.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700L))
        assertFalse(commands.any { it is AppListCommand.ShowActions })
        assertFalse(
            "row long-click must stay suppressed until the real terminal",
            firstRow.isLongClickable,
        )

        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 20f, eventTime = 720L)
        assertTrue(
            "the real terminal must hand long-click ownership back to the row",
            firstRow.isLongClickable,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun scrollingGlidePressFollowsThePointerAndClearsOnCancelAndRealUp() {
        val entries = (0 until 8).map { appEntry("component:glide-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
                    entryMotionEnabled = false,
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
        measureAndLayout(view, width = 100, height = 120)
        val secondRow = requireNotNull(
            view.layoutManager?.findViewByPosition(1),
        ) as NativeAppListRowView

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 16L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(180L))

        assertEquals(0.992f, interactionScaleOf(secondRow), 0.001f)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_CANCEL, y = 60f, eventTime = 17L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(180L))

        assertEquals(1f, interactionScaleOf(secondRow), 0.001f)
        springEdgeEffectsOf(view).finish()

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 200L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 216L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(180L))

        assertEquals(0.992f, interactionScaleOf(secondRow), 0.001f)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 60f, eventTime = 397L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(180L))

        assertEquals(1f, interactionScaleOf(secondRow), 0.001f)
        view.release()
        activity.finish()
    }

    @Test
    fun dragHandoffUsesTheRealTerminalEventWithoutStartingARecyclerFling() {
        var flingCount = 0
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(centerEdgeItems = false))
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(
                        1L,
                        listOf(appEntry("handoff-a"), appEntry("handoff-b"), appEntry("handoff-c")),
                    ),
                    iconsByKey = emptyMap(),
                ),
            )
            onFlingListener = object : RecyclerView.OnFlingListener() {
                override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                    flingCount++
                    return true
                }
            }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 45f, eventTime = 621L)
        assertTrue(dragOverlayViewOf(view) != null)
        assertTrue(recyclerTerminalPendingOf(view))

        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 100f, eventTime = 630L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(140L))

        assertFalse(recyclerTerminalPendingOf(view))
        assertNull(dragOverlayViewOf(view))
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, view.scrollState)
        assertEquals(0, flingCount)
        view.release()
        activity.finish()
    }

    @Test
    fun launchOriginIncludesTheVisibleCanvasPullTranslation() {
        val entries = (0 until 3).map { appEntry("component:pull-origin-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)
        val firstRow = requireNotNull(view.layoutManager?.findViewByPosition(0))

        firstRow.performClick()
        val baselineOrigin = (commands.single() as AppListCommand.Open).origin
        commands.clear()

        springEdgeEffectsOf(view).pull(
            FlueEdgePhysics.Edge.TOP,
            20f / view.height,
        )
        val visiblePullOffset = pullOffsetOf(view)
        assertTrue(visiblePullOffset > 0f)
        assertEquals(visiblePullOffset, firstRow.translationY, 0.01f)
        firstRow.performClick()
        val pulledOrigin = (commands.single() as AppListCommand.Open).origin

        assertEquals(
            baselineOrigin.y + visiblePullOffset / view.height,
            pulledOrigin.y,
            0.0001f,
        )
        springEdgeEffectsOf(view).finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun launchOriginUsesTheIconCenterWhenARecycledRowHasAStalePivot() {
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
                    entryMotionEnabled = false,
                ),
            )
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(
                        1L,
                        listOf(appEntry("component:stale-pivot-origin")),
                    ),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)
        val row = requireNotNull(view.layoutManager?.findViewByPosition(0)) as NativeAppListRowView
        row.pivotY = 0f

        row.performClick()

        val origin = (commands.single() as AppListCommand.Open).origin
        assertEquals((row.top + row.height / 2f) / view.height, origin.y, 0.0001f)
        assertEquals(row.height / 2f, row.pivotY, 0f)
        view.release()
        activity.finish()
    }

    @Test
    fun hitTestingTracksTheVisibleCanvasPullTranslation() {
        val firstKey = AppItemKey("component:pull-hit-0")
        val entries = (0 until 3).map { appEntry("component:pull-hit-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val firstRow = requireNotNull(
            view.layoutManager?.findViewByPosition(0),
        ) as NativeAppListRowView
        val originalCenterX = firstRow.iconCenterXInParentWithoutFlow()
        val originalCenterY = firstRow.iconCenterYInParentWithoutFlow()
        springEdgeEffectsOf(view).pull(
            FlueEdgePhysics.Edge.TOP,
            50f / view.height,
        )
        val visiblePullOffset = pullOffsetOf(view)
        assertTrue(visiblePullOffset > 0f)

        assertEquals(
            firstKey,
            folderKeyAtOf(view, x = originalCenterX, y = originalCenterY + visiblePullOffset),
        )
        assertNull(folderKeyAtOf(view, x = originalCenterX, y = originalCenterY))

        springEdgeEffectsOf(view).finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun actionUpAfterPullReturnsToRestPreservesFinalSampleForFlingDirection() {
        val entries = (0 until 40).map { appEntry("component:up-sample-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(centerEdgeItems = false))
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)
        var capturedVelocityY: Float? = null
        view.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                capturedVelocityY = velocityY.toFloat()
                return true
            }
        }

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 80f, eventTime = 0L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 160f, eventTime = 16L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 60f, eventTime = 32L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = 140f, eventTime = 48L)

        assertTrue("final sample must preserve the downward fling", (capturedVelocityY ?: 0f) < 0f)
        view.release()
        activity.finish()
    }

    @Test
    fun pointerUpRebasesPullTrackingToTheSurvivingPointerId() {
        val entries = (0 until 8).map { appEntry("component:pointer-$it") }
        var scrollToTopRequests = 0
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(centerEdgeItems = false))
            onScrollToTop = { scrollToTopRequests++ }
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, entries),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)

        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(pointer(7, 20f)),
            eventTime = 0L,
        )
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(pointer(7, 20f), pointer(9, 100f)),
            eventTime = 8L,
        )
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 0,
            pointers = listOf(pointer(7, 20f), pointer(9, 100f)),
            eventTime = 16L,
        )
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(pointer(9, 101f)),
            eventTime = 24L,
        )
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_UP,
            actionIndex = 0,
            pointers = listOf(pointer(9, 101f)),
            eventTime = 32L,
        )

        assertEquals(0, scrollToTopRequests)
        assertTrue("surviving pointer movement should not create a large pull", pullOffsetOf(view) <= 2f)
        view.release()
        activity.finish()
    }

    @Test
    fun secondPointerCancelsPendingNativeLongPress() {
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(drawingConfig().copy(centerEdgeItems = false))
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(1L, listOf(appEntry("component:multi-pending"))),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)

        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(pointer(3, 20f)),
            eventTime = 0L,
        )
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(pointer(3, 20f), pointer(7, 22f)),
            eventTime = 100L,
        )
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700L))

        assertFalse(commands.any { it is AppListCommand.ShowActions })
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_CANCEL,
            actionIndex = 0,
            pointers = listOf(pointer(3, 20f), pointer(7, 22f)),
            eventTime = 801L,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun secondPointerCancelsActiveNativeDragWithoutJumpingItsOverlay() {
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(drawingConfig().copy(centerEdgeItems = false))
            submit(
                NativeAppListContent(
                    snapshot = AppListSnapshot.create(
                        1L,
                        listOf(appEntry("component:multi-drag-0"), appEntry("component:multi-drag-1")),
                    ),
                    iconsByKey = emptyMap(),
                ),
            )
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 100, height = 120)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 20f, eventTime = 0L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 45f, eventTime = 621L)
        assertTrue(dragOverlayViewOf(view) != null)

        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(pointer(0, 45f), pointer(9, 100f)),
            eventTime = 630L,
        )

        assertNull(dragOverlayViewOf(view))
        dispatchMultiThroughHierarchy(
            view,
            action = MotionEvent.ACTION_CANCEL,
            actionIndex = 0,
            pointers = listOf(pointer(0, 45f), pointer(9, 100f)),
            eventTime = 631L,
        )
        view.release()
        activity.finish()
    }

    @Test
    fun singleMoveCrossingTheBottomTransfersOnlyRecyclerRemainderToPull() {
        val entries = (0 until 10).map { appEntry("component:cross-bottom-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)
        view.scrollBy(0, 10_000)
        val bottomOffset = view.computeVerticalScrollOffset()
        view.scrollBy(0, -10)
        assertEquals(bottomOffset - 10, view.computeVerticalScrollOffset())

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 100f, eventTime = 0L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 80f, eventTime = 16L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 40f, eventTime = 32L)

        assertEquals(bottomOffset, view.computeVerticalScrollOffset())
        assertTrue(pullOffsetOf(view) < 0f)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_CANCEL, y = 40f, eventTime = 33L)
        view.release()
        activity.finish()
    }

    @Test
    fun honeycombCrossEdgeRemainderUsesTheCloxEighteenPercentDamping() {
        val entries = (0 until 40).map { appEntry("component:honey-cross-bottom-$it") }
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
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        measureAndLayout(view, width = 320, height = 320)
        view.scrollBy(0, 10_000)
        val bottomOffset = view.computeVerticalScrollOffset()
        view.scrollBy(0, -10)
        assertEquals(bottomOffset - 10, view.computeVerticalScrollOffset())

        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = 200f, eventTime = 0L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 180f, eventTime = 16L)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = 140f, eventTime = 32L)

        assertEquals(bottomOffset, view.computeVerticalScrollOffset())
        // 阻尼是 0.18 × strengthScale，而 strengthScale 由档位的 edgeStrength
        // 推出（见 FlueSpringEdgeEffect 的 updateMotionProfile）。这里必须跟着
        // 档位算，不能写死一个数字——柔和档 edgeStrength 从 5 调到 10 时
        // strengthScale 翻倍成 2.0，写死的 -5.4 当场失效。
        assertEquals(-5.4f * activeEdgeStrengthScale(), pullOffsetOf(view), 1f)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_CANCEL, y = 140f, eventTime = 33L)
        view.release()
        activity.finish()
    }

    @Test
    fun returningPullToRestScrollsTheListWithTheSameMoveRemainder() {
        val entries = (0 until 10).map { appEntry("component:return-remainder-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 60f / view.height)
        val stretched = pullOffsetOf(view)
        assertTrue(stretched > 0f)

        val consumed = effects.pull(FlueEdgePhysics.Edge.TOP, -80f / view.height) * view.height
        assertEquals(-stretched, consumed, 0.01f)
        assertEquals(0f, pullOffsetOf(view), 0.01f)
        effects.finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun subTouchSlopPullStillForwardsItsCrossingRemainderWithoutADeadZone() {
        val entries = (0 until 10).map { appEntry("component:small-pull-remainder-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 5f / view.height)
        val stretched = pullOffsetOf(view)
        assertTrue(stretched > 0f)

        val consumed = effects.pull(FlueEdgePhysics.Edge.TOP, -5f / view.height) * view.height
        assertEquals(-stretched, consumed, 0.01f)
        assertEquals(0f, pullOffsetOf(view), 0.01f)
        effects.finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun touchingDuringPullSettleContinuesFromTheVisibleOffset() {
        val entries = (0 until 10).map { appEntry("component:interrupt-settle-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 60f / view.height)
        effects.release(FlueEdgePhysics.Edge.TOP)
        val interruptedOffset = pullOffsetOf(view)
        assertTrue(interruptedOffset > 0f)

        effects.beginGesture()
        effects.pull(FlueEdgePhysics.Edge.TOP, -10f / view.height)

        assertEquals(maxOf(0f, interruptedOffset - 10f), pullOffsetOf(view), 0.01f)
        effects.finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun firstMoveAfterInterruptingPullSettleForwardsTheRemainderPastRest() {
        val entries = (0 until 10).map { appEntry("component:interrupt-cross-rest-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 60f / view.height)
        effects.release(FlueEdgePhysics.Edge.TOP)
        val interruptedOffset = pullOffsetOf(view)
        assertTrue(interruptedOffset > 0f)

        effects.beginGesture()
        val consumed = effects.pull(
            FlueEdgePhysics.Edge.TOP,
            -(interruptedOffset + 20f) / view.height,
        ) * view.height

        assertEquals(0f, pullOffsetOf(view), 0.01f)
        assertEquals(-interruptedOffset, consumed, 0.01f)
        effects.finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun dragStartingDuringInterruptedPullSettleClearsTheVisiblePullOffset() {
        val entries = (0 until 10).map { appEntry("component:drag-interrupt-settle-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 60f / view.height)
        val interruptedOffset = pullOffsetOf(view)
        assertTrue(interruptedOffset > 0f)

        val firstRow = requireNotNull(view.layoutManager?.findViewByPosition(0))
        val visibleFirstCenter = firstRow.top + firstRow.height / 2f + interruptedOffset
        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = visibleFirstCenter, eventTime = 18L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        dispatchThroughHierarchy(view, MotionEvent.ACTION_MOVE, y = visibleFirstCenter + 22f, eventTime = 639L)

        assertTrue(commands.any { it is AppListCommand.ShowActions })
        assertTrue(dragOverlayViewOf(view) != null)
        assertEquals(0f, pullOffsetOf(view), 0.01f)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_CANCEL, y = visibleFirstCenter + 22f, eventTime = 640L)
        view.release()
        activity.finish()
    }

    @Test
    fun longPressReleaseDuringInterruptedPullSettleRestoresRowLongClickOwnership() {
        val entries = (0 until 10).map { appEntry("component:release-interrupt-settle-$it") }
        val commands = mutableListOf<AppListCommand>()
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            onCommand = commands::add
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        measureAndLayout(view, width = 100, height = 120)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, 60f / view.height)
        assertTrue(pullOffsetOf(view) > 0f)

        val firstRow = requireNotNull(view.layoutManager?.findViewByPosition(0))
        val visibleFirstCenter = firstRow.top + firstRow.height / 2f + pullOffsetOf(view)
        dispatchThroughHierarchy(view, MotionEvent.ACTION_DOWN, y = visibleFirstCenter, eventTime = 18L)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(620L))
        assertTrue(commands.any { it is AppListCommand.ShowActions })
        assertFalse(firstRow.isLongClickable)

        dispatchThroughHierarchy(view, MotionEvent.ACTION_UP, y = visibleFirstCenter, eventTime = 639L)

        assertTrue(firstRow.isLongClickable)
        effects.finish(FlueEdgePhysics.Edge.TOP)
        view.release()
        activity.finish()
    }

    @Test
    fun viewportShrinkClampsActivePullStateAndVisibleOffsetTogether() {
        val entries = (0 until 10).map { appEntry("component:pull-resize-$it") }
        val view = NativeAppListView(RuntimeEnvironment.getApplication()).apply {
            setConfig(
                drawingConfig().copy(
                    fisheyeEnabled = false,
                    centerEdgeItems = false,
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
        val compactHeight = 100
        // 与生产公式同源：48dp 与视口 14% 各自先乘 strengthScale 再取小者。
        // strengthScale 跟档位的 edgeStrength 走，所以这里不能写死。
        val strengthScale = activeEdgeStrengthScale()
        val compactMaximum = minOf(
            48f * view.resources.displayMetrics.density * strengthScale,
            compactHeight * 0.14f * strengthScale,
        )
        val pullDistance = compactMaximum / (0.18f * strengthScale) + 20f
        val expandedHeight = (pullDistance * 2f + 40f).toInt()
        measureAndLayout(view, width = 100, height = expandedHeight)

        val effects = springEdgeEffectsOf(view)
        effects.pull(FlueEdgePhysics.Edge.TOP, pullDistance / view.height)
        assertTrue(pullOffsetOf(view) > compactMaximum)

        measureAndLayout(view, width = 100, height = compactHeight)

        assertEquals(compactMaximum, pullOffsetOf(view), 0.01f)
        effects.finish(FlueEdgePhysics.Edge.TOP)
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
    fun folderHitRegionTracksTheRenderedIconCenterAndTransforms() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
        row.bind(boundRow(null), resolvedDrawingConfig())
        row.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
        )
        row.layout(30, 50, 130, 90)
        row.setMoveTranslation(10f, 20f)
        row.setVisualScale(0.5f)

        assertTrue(row.containsTransformedIconPoint(72f, 90f, centerFraction = 0.64f))
        assertFalse(row.containsTransformedIconPoint(76f, 90f, centerFraction = 0.64f))
        assertFalse(row.containsTransformedIconPoint(Float.NaN, 90f, centerFraction = 0.64f))
    }

    @Test
    fun reorderHitCenterTracksTheRenderedIconWhenTheLinearRowIsScaled() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
        row.bind(boundRow(null), resolvedDrawingConfig())
        measureAndLayout(row)
        row.setVisualScale(0.5f)

        // The icon is laid out at x=14, but the row's centered transform pivot
        // moves its rendered centre to x=32 when scaleX is 0.5.
        assertEquals(32f, row.iconCenterXInParentForHitTest(), 0.001f)
    }

    @Test
    fun globalSafeInsetIsNotAppliedInsideSpatialGridCells() {
        listOf(
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.DENSE_GRID,
        ).forEach { presentation ->
            val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
            row.bind(
                boundRow(null),
                NativeAppListResolvedConfig.from(
                    drawingConfig().copy(
                        presentation = presentation,
                        leftSafeInsetPx = 80,
                        honeycombCellSizePx = 100f,
                    ),
                ),
            )
            measureAndLayout(row)
            val localCellDown = MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                5f,
                20f,
                0,
            )

            assertTrue("presentation=$presentation", row.onTouchEvent(localCellDown))
            localCellDown.recycle()
        }
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
    fun acceptedFolderRemovalStaysInvisibleUntilTheViewIsRebound() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
        row.bind(boundRow(null, key = "component:source/.Main"), resolvedDrawingConfig())
        row.alpha = 0.72f

        row.setFolderRemovalPending(true)
        assertEquals(0f, row.alpha, 0f)
        row.setFolderRemovalPending(false)
        assertEquals(0.72f, row.alpha, 0f)

        row.setFolderRemovalPending(true)
        row.commitFolderRemoval()
        // RecyclerView may clear the same holder again after moving it into
        // the disappearing/recycle layer; that must not resurrect its RenderNode.
        row.clearBinding()
        assertEquals(View.INVISIBLE, row.visibility)
        assertEquals(0f, row.alpha, 0f)

        row.bind(boundRow(null, key = "folder:new"), resolvedDrawingConfig())
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(1f, row.alpha, 0f)
    }

    @Test
    fun pendingFolderRemovalRestoresImmediatelyWhenRecyclerReusesViewForNewFolder() {
        val row = NativeAppListRowView(RuntimeEnvironment.getApplication())
        row.bind(boundRow(null, key = "component:target/.Main"), resolvedDrawingConfig())
        row.alpha = 0.64f

        row.setFolderRemovalPending(true)
        assertEquals(View.INVISIBLE, row.visibility)
        assertEquals(0f, row.alpha, 0f)

        // A same-frame edge/fisheye refresh must not resurrect the old target
        // while RecyclerView is moving it into the disappearing-child layer.
        row.setHostVisualAlpha(0.91f)
        assertEquals(View.INVISIBLE, row.visibility)
        assertEquals(0f, row.alpha, 0f)

        // The repository callback can synchronously submit a Diff and reuse
        // this exact holder before completeFolderDispatchVisuals() returns.
        row.bind(boundRow(null, key = "folder:authoritative-new"), resolvedDrawingConfig())

        assertEquals(AppItemKey("folder:authoritative-new"), row.boundStableKeyOrNull())
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(1f, row.alpha, 0f)
        // A stale completion for the old key must now be a harmless no-op.
        row.setFolderRemovalPending(false)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(1f, row.alpha, 0f)
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

    private fun boundEntryRow(entry: AppListEntry): NativeAppListBoundRow<Bitmap> =
        NativeAppListBoundRow(
            snapshotGeneration = 1L,
            stableKey = entry.key,
            entry = entry,
            icon = null,
        )

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

    private fun measureAndLayout(
        view: View,
        width: Int = 100,
        height: Int = 40,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun spatialScrollOffsetOf(manager: NativeAppGridLayoutManager): Float {
        val field = NativeAppGridLayoutManager::class.java.getDeclaredField("scrollOffsetPx")
        field.isAccessible = true
        return field.getFloat(manager)
    }

    private fun pullOffsetOf(view: NativeAppListView): Float {
        val field = NativeAppListView::class.java
            .getDeclaredField("pullOffsetPx")
            .apply { isAccessible = true }
        return field.getFloat(view)
    }

    /**
     * 边缘物理的强度系数，与 FlueSpringEdgeEffect.updateMotionProfile 同一条
     * 公式。drawingConfig() 不覆盖 motionProfile，用的就是 NativeAppListConfig
     * 的默认档（柔和），所以这里直接读它的 edgeStrength。
     *
     * 断言必须走这个函数而不是写死数字：档位调参（例如柔和档 edgeStrength
     * 5 → 10）会让 maxOffsetPx 与 pullResistance 整体缩放，写死的期望值当场
     * 失效，而被测的其实是「48dp / 视口 14% / 0.18 阻尼」这套公式本身。
     */
    private fun activeEdgeStrengthScale(): Float {
        val edgeStrength = drawingConfig().motionProfile.edgeStrength.coerceIn(0, 10)
        return (edgeStrength / 5f).coerceIn(0f, 2f)
    }

    private fun interactionScaleOf(view: NativeAppListRowView): Float {
        val field = NativeAppListRowView::class.java
            .getDeclaredField("interactionScale")
            .apply { isAccessible = true }
        return field.getFloat(view)
    }

    private fun dispatchThroughHierarchy(
        view: NativeAppListView,
        action: Int,
        y: Float,
        eventTime: Long,
        x: Float = 30f,
    ) {
        MotionEvent.obtain(0L, eventTime, action, x, y, 0).also { event ->
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private data class TestPointer(val id: Int, val y: Float, val x: Float = 30f)

    private fun pointer(id: Int, y: Float, x: Float = 30f): TestPointer =
        TestPointer(id = id, y = y, x = x)

    private fun dispatchMultiThroughHierarchy(
        view: NativeAppListView,
        action: Int,
        actionIndex: Int,
        pointers: List<TestPointer>,
        eventTime: Long,
    ) {
        val properties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].x
                y = pointers[index].y
                pressure = 1f
                size = 1f
            }
        }
        val encodedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        MotionEvent.obtain(
            0L,
            eventTime,
            encodedAction,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        ).also { event ->
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun dispatch(
        view: View,
        action: Int,
        y: Float,
        eventTime: Long,
        x: Float = 30f,
    ) {
        MotionEvent.obtain(0L, eventTime, action, x, y, 0).also { event ->
            // Always go through the real entry point. The old helper drove
            // onInterceptTouchEvent/onTouchEvent by hand, which skipped the
            // dispatchTouchEvent funnel where the edge pointer window opens and
            // closes — the tests were exercising a code path real hardware
            // never takes.
            view.dispatchTouchEvent(event)
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

    private fun dragOverlayViewOf(view: NativeAppListView): NativeAppListRowView? {
        val field = NativeAppListView::class.java
            .getDeclaredField("dragOverlayView")
            .apply { isAccessible = true }
        return field.get(view) as? NativeAppListRowView
    }

    private fun springEdgeEffectsOf(view: NativeAppListView): FlueSpringEdgeEffectFactory {
        val field = NativeAppListView::class.java
            .getDeclaredField("springEdgeEffects")
            .apply { isAccessible = true }
        return field.get(view) as FlueSpringEdgeEffectFactory
    }

    @Suppress("UNCHECKED_CAST")
    private fun dragPreviewOrderOf(view: NativeAppListView): List<AppItemKey> {
        val field = NativeAppListView::class.java
            .getDeclaredField("dragPreviewOrder")
            .apply { isAccessible = true }
        return field.get(view) as List<AppItemKey>
    }

    private fun idleMainLooperUntil(
        timeoutMillis: Long = 2_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        do {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.yield()
        } while (System.nanoTime() < deadlineNanos)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun recyclerTerminalPendingOf(view: NativeAppListView): Boolean {
        val field = NativeAppListView::class.java
            .getDeclaredField("recyclerGestureNeedsTerminal")
            .apply { isAccessible = true }
        return field.getBoolean(view)
    }

    private fun closestChildToViewportCenter(view: NativeAppListView): View {
        val centerY = view.height / 2f
        return (0 until view.childCount)
            .map(view::getChildAt)
            .minBy { child -> kotlin.math.abs(child.top + child.height / 2f - centerY) }
    }

    private fun folderKeyAtOf(view: NativeAppListView, x: Float, y: Float): AppItemKey? {
        return dragHostOf(view).folderKeyAt(x, y)
    }

    private fun dragHostOf(view: NativeAppListView): NativeAppListDragHost {
        val field = NativeAppListView::class.java
            .getDeclaredField("dragHost")
            .apply { isAccessible = true }
        return field.get(view) as NativeAppListDragHost
    }

    private fun setDraggedSourceKey(view: NativeAppListView, key: AppItemKey?) {
        NativeAppListView::class.java
            .getDeclaredField("draggedSourceKey")
            .apply { isAccessible = true }
            // AppItemKey is a value class, so the nullable field is lowered to its String value.
            .set(view, key?.value)
    }

    private companion object {
        const val REAL_ICON_COLOR: Int = Color.MAGENTA
        const val FALLBACK_COLOR: Int = Color.GREEN
    }
}
