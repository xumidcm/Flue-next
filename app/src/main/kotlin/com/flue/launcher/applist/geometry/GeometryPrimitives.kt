package com.flue.launcher.applist.geometry

data class ViewportPx(
    val width: Float,
    val height: Float,
) {
    init {
        require(width.isFinite() && width > 0f) {
            "Viewport width must be finite and greater than zero"
        }
        require(height.isFinite() && height > 0f) {
            "Viewport height must be finite and greater than zero"
        }
    }
}

data class PixelSize(
    val width: Float,
    val height: Float,
) {
    init {
        require(width.isFinite() && width > 0f) {
            "Pixel width must be finite and greater than zero"
        }
        require(height.isFinite() && height > 0f) {
            "Pixel height must be finite and greater than zero"
        }
    }

    internal fun scaledBy(scale: Float): PixelSize = PixelSize(
        width = width * scale,
        height = height * scale,
    )
}

data class PixelPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "Pixel point coordinates must be finite"
        }
    }
}

data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Pixel rectangle coordinates must be finite"
        }
        require(left <= right && top <= bottom) {
            "Pixel rectangle edges must be ordered"
        }
    }

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    internal companion object {
        fun centeredAt(
            center: PixelPoint,
            size: PixelSize,
        ): PixelRect {
            val halfWidth = size.width / 2f
            val halfHeight = size.height / 2f
            return PixelRect(
                left = center.x - halfWidth,
                top = center.y - halfHeight,
                right = center.x + halfWidth,
                bottom = center.y + halfHeight,
            )
        }
    }
}
