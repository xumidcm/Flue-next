package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorCanvasGeometryTest {
    private fun layer(
        key: String,
        type: String = "shape",
        id: String? = null,
        parentId: String? = null,
        attributes: Map<String, String> = emptyMap(),
        order: Int = 0,
    ) = WatchEditorLayer(
        key = WatchEditorLayerKey(key),
        id = id,
        type = type,
        parentId = parentId,
        attributes = attributes,
        childElementNames = emptyList(),
        order = order,
    )

    @Test
    fun transformParsingMirrorsRuntimeAttributeFallbacks() {
        val transform = watchEditorTransformOf(
            mapOf(
                "x" to "10",
                "y" to "-5",
                "anim_scale" to "50",
                "rotation" to "30",
            ),
        )
        assertEquals(10f, transform.x, 1e-4f)
        assertEquals(-5f, transform.y, 1e-4f)
        assertEquals(0.5f, transform.scaleX, 1e-4f)
        assertEquals(0.5f, transform.scaleY, 1e-4f)
        assertEquals(30f, transform.rotation, 1e-4f)

        val axisSpecific = watchEditorTransformOf(
            mapOf("anim_scale_x" to "200", "scale_y" to "25"),
        )
        assertEquals(2f, axisSpecific.scaleX, 1e-4f)
        assertEquals(0.25f, axisSpecific.scaleY, 1e-4f)
    }

    @Test
    fun affineInvertRoundTripsPoints() {
        val affine = WatchEditorAffine.fromTransform(
            WatchEditorTransform(x = 40f, y = -12f, scaleX = 2f, scaleY = 0.5f, rotation = 33f),
        )
        val inverse = requireNotNull(affine.inverted())
        val world = affine.apply(17f, -9f)
        val local = inverse.apply(world.x, world.y)
        assertEquals(17f, local.x, 1e-3f)
        assertEquals(-9f, local.y, 1e-3f)
    }

    @Test
    fun hitTestPicksTopmostOverlappingLayer() {
        val layers = listOf(
            layer("bottom", attributes = mapOf("width" to "100", "height" to "100"), order = 0),
            layer("top", attributes = mapOf("width" to "100", "height" to "100"), order = 1),
        )
        val geometries = buildWatchEditorLayerGeometries(layers)
        val matrices = buildWatchEditorWorldMatrices(geometries)

        assertEquals(1, hitTestWatchEditorLayers(geometries, matrices, 0f, 0f))
    }

    @Test
    fun hitTestInverseTransformsRotatedLayers() {
        // 40x10 box (clamped to 40x16 by the minimum extent) at (100, 0),
        // rotated 90 degrees: its long axis now runs vertically.
        val rotated = listOf(
            layer(
                "r",
                attributes = mapOf(
                    "width" to "40",
                    "height" to "10",
                    "x" to "100",
                    "y" to "0",
                    "rotation" to "90",
                ),
            ),
        )
        val geometries = buildWatchEditorLayerGeometries(rotated)
        val matrices = buildWatchEditorWorldMatrices(geometries)
        assertEquals(0, hitTestWatchEditorLayers(geometries, matrices, 100f, 15f))
        assertNull(hitTestWatchEditorLayers(geometries, matrices, 115f, 0f))

        val unrotated = buildWatchEditorLayerGeometries(
            listOf(
                layer(
                    "u",
                    attributes = mapOf(
                        "width" to "40",
                        "height" to "10",
                        "x" to "100",
                        "y" to "0",
                    ),
                ),
            ),
        )
        val unrotatedMatrices = buildWatchEditorWorldMatrices(unrotated)
        assertNull(hitTestWatchEditorLayers(unrotated, unrotatedMatrices, 100f, 15f))
    }

    @Test
    fun hitTestComposesParentGroupTransforms() {
        val layers = listOf(
            layer(
                "group",
                type = "group",
                id = "g",
                attributes = mapOf("x" to "100", "y" to "50", "rotation" to "90"),
                order = 0,
            ),
            layer(
                "child",
                parentId = "g",
                attributes = mapOf("width" to "40", "height" to "10"),
                order = 1,
            ),
        )
        val geometries = buildWatchEditorLayerGeometries(layers)
        assertEquals(0, geometries[1].parentIndex)
        val matrices = buildWatchEditorWorldMatrices(geometries)

        // Local (15, 0) maps through the rotated parent to design (100, 65).
        assertEquals(1, hitTestWatchEditorLayers(geometries, matrices, 100f, 65f))
        // Same point misses when the parent chain is ignored.
        val noParent = buildWatchEditorLayerGeometries(
            listOf(layers[1].copy(parentId = null)),
        )
        val noParentMatrices = buildWatchEditorWorldMatrices(noParent)
        assertNull(hitTestWatchEditorLayers(noParent, noParentMatrices, 100f, 65f))
    }

    @Test
    fun hiddenLayersAreNotHitTestable() {
        val layers = listOf(
            layer(
                "hidden",
                attributes = mapOf("width" to "100", "height" to "100", "display" to "false"),
            ),
        )
        val geometries = buildWatchEditorLayerGeometries(layers)
        val matrices = buildWatchEditorWorldMatrices(geometries)
        assertNull(hitTestWatchEditorLayers(geometries, matrices, 0f, 0f))
    }

    @Test
    fun transformOverridesReplaceAttributeTransforms() {
        val layers = listOf(layer("a", attributes = mapOf("x" to "0", "width" to "40")))
        val override = WatchEditorTransform(x = 200f)
        val geometries = buildWatchEditorLayerGeometries(
            layers,
            transformOverrides = mapOf(WatchEditorLayerKey("a") to override),
        )
        assertEquals(200f, geometries[0].transform.x, 1e-4f)
    }

    @Test
    fun imageBoxUsesNativeSizeLookupAndAnchor() {
        val box = estimateWatchEditorLayerBox(
            type = "image",
            attributes = mapOf("path" to "img.png", "alignment" to "tl"),
            imageSizeLookup = { path -> if (path == "img.png") 100f to 60f else null },
        )
        assertEquals(50f, box.centerX, 1e-4f)
        assertEquals(30f, box.centerY, 1e-4f)
        assertEquals(50f, box.halfWidth, 1e-4f)
        assertEquals(30f, box.halfHeight, 1e-4f)
    }

    @Test
    fun textBoxEstimatesExpressionWidth() {
        val box = estimateWatchEditorLayerBox(
            type = "text",
            attributes = mapOf("text" to "{dh}:{dm}", "text_size" to "40"),
        )
        // "{dh}:{dm}" simplifies to "88:88" (5 characters).
        assertEquals(5 * 40f * 0.58f / 2f, box.halfWidth, 1e-3f)
        assertEquals(40f * 1.2f / 2f, box.halfHeight, 1e-3f)
    }

    @Test
    fun tinyLayersKeepAMinimumTappableExtent() {
        val box = estimateWatchEditorLayerBox(
            type = "shape",
            attributes = mapOf("width" to "2", "height" to "2"),
        )
        assertTrue(box.halfWidth >= 8f)
        assertTrue(box.halfHeight >= 8f)
    }

    private fun axisAlignedBox(half: Float) = WatchEditorSelectionBox(
        center = WatchEditorPoint(0f, 0f),
        topLeft = WatchEditorPoint(-half, -half),
        topRight = WatchEditorPoint(half, -half),
        bottomLeft = WatchEditorPoint(-half, half),
        bottomRight = WatchEditorPoint(half, half),
    )

    @Test
    fun cornerHandleScalesProportionally() {
        val next = applyWatchEditorHandleDrag(
            handle = WatchEditorHandle.BOTTOM_RIGHT,
            box = axisAlignedBox(50f),
            startTransform = WatchEditorTransform(),
            startX = 50f,
            startY = 50f,
            currentX = 100f,
            currentY = 100f,
        )
        assertEquals(2f, next.scaleX, 1e-3f)
        assertEquals(2f, next.scaleY, 1e-3f)
        assertEquals(0f, next.rotation, 1e-3f)
    }

    @Test
    fun edgeHandlesScaleASingleAxis() {
        val right = applyWatchEditorHandleDrag(
            handle = WatchEditorHandle.RIGHT,
            box = axisAlignedBox(50f),
            startTransform = WatchEditorTransform(),
            startX = 50f,
            startY = 0f,
            currentX = 100f,
            currentY = 0f,
        )
        assertEquals(2f, right.scaleX, 1e-3f)
        assertEquals(1f, right.scaleY, 1e-3f)

        val top = applyWatchEditorHandleDrag(
            handle = WatchEditorHandle.TOP,
            box = axisAlignedBox(50f),
            startTransform = WatchEditorTransform(),
            startX = 0f,
            startY = -50f,
            currentX = 0f,
            currentY = -100f,
        )
        assertEquals(1f, top.scaleX, 1e-3f)
        assertEquals(2f, top.scaleY, 1e-3f)
    }

    @Test
    fun rotateHandleSnapsNearFortyFiveDegreeSteps() {
        // Start straight above the center, drag 44 degrees clockwise: the
        // result snaps to 45 because it is within the 3-degree tolerance.
        val next = applyWatchEditorHandleDrag(
            handle = WatchEditorHandle.ROTATE,
            box = axisAlignedBox(50f),
            startTransform = WatchEditorTransform(),
            startX = 0f,
            startY = -80f,
            currentX = 55.5727f,
            currentY = -57.5472f,
        )
        assertEquals(45f, next.rotation, 1e-3f)
    }

    @Test
    fun rotationSnapOnlyTriggersWithinTolerance() {
        assertEquals(45f, snapWatchEditorRotation(43.5f), 1e-4f)
        assertEquals(40f, snapWatchEditorRotation(40f), 1e-4f)
        assertEquals(90f, snapWatchEditorRotation(92f), 1e-4f)
        assertEquals(-45f, snapWatchEditorRotation(-44f), 1e-4f)
        assertEquals(0f, snapWatchEditorRotation(1.2f), 1e-4f)
    }

    @Test
    fun handleHitResolvesNearestHandleWithinRadius() {
        val box = axisAlignedBox(50f)
        assertEquals(
            WatchEditorHandle.TOP_RIGHT,
            resolveWatchEditorHandleHit(box, 52f, -48f, 6f, 30f),
        )
        assertEquals(
            WatchEditorHandle.ROTATE,
            resolveWatchEditorHandleHit(box, 0f, -78f, 6f, 30f),
        )
        assertEquals(
            WatchEditorHandle.LEFT,
            resolveWatchEditorHandleHit(box, -49f, 1f, 6f, 30f),
        )
        assertNull(resolveWatchEditorHandleHit(box, 0f, 0f, 6f, 30f))
    }

    @Test
    fun dragDeltaConvertsIntoRotatedParentFrame() {
        val layers = listOf(
            layer("group", type = "group", id = "g", attributes = mapOf("rotation" to "90")),
            layer("child", parentId = "g"),
        )
        val geometries = buildWatchEditorLayerGeometries(layers)
        val matrices = buildWatchEditorWorldMatrices(geometries)

        val delta = watchEditorDragDeltaInParentFrame(geometries, matrices, 1, 10f, 0f)
        assertEquals(0f, delta.x, 1e-3f)
        assertEquals(-10f, delta.y, 1e-3f)

        // Layers without a parent keep the raw design delta.
        val rootDelta = watchEditorDragDeltaInParentFrame(geometries, matrices, 0, 10f, 0f)
        assertEquals(10f, rootDelta.x, 1e-3f)
        assertEquals(0f, rootDelta.y, 1e-3f)
    }

    @Test
    fun selectionBoxMapsCornersThroughWorldMatrix() {
        val layers = listOf(
            layer(
                "a",
                attributes = mapOf(
                    "width" to "40",
                    "height" to "20",
                    "x" to "100",
                    "y" to "-30",
                ),
            ),
        )
        val geometries = buildWatchEditorLayerGeometries(layers)
        val matrices = buildWatchEditorWorldMatrices(geometries)
        val box = watchEditorSelectionBox(geometries[0], matrices[0])
        assertEquals(100f, box.center.x, 1e-3f)
        assertEquals(-30f, box.center.y, 1e-3f)
        assertEquals(80f, box.topLeft.x, 1e-3f)
        assertEquals(-40f, box.topLeft.y, 1e-3f)
        assertEquals(120f, box.bottomRight.x, 1e-3f)
        assertEquals(-20f, box.bottomRight.y, 1e-3f)
    }

    @Test
    fun designViewConversionsRoundTrip() {
        val design = watchEditorDesignFromViewPx(300f, 400f)
        assertEquals(300f, watchEditorViewPxFromDesign(design, 400f), 1e-3f)
        assertEquals(0f, watchEditorDesignFromViewPx(200f, 400f), 1e-3f)
        assertEquals(256f, watchEditorDesignLengthFromViewPx(200f, 400f), 1e-3f)
    }
}
