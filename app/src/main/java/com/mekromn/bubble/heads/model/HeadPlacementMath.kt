package com.mekromn.bubble.heads.model

data class PixelPoint(val x: Int, val y: Int)
data class NormalizedPoint(val x: Float, val y: Float)

object HeadPlacementMath {
    fun clamp(
        point: PixelPoint,
        containerWidth: Int,
        containerHeight: Int,
        itemWidth: Int,
        itemHeight: Int,
    ): PixelPoint {
        val maxX = (containerWidth - itemWidth).coerceAtLeast(0)
        val maxY = (containerHeight - itemHeight).coerceAtLeast(0)
        return PixelPoint(
            x = point.x.coerceIn(0, maxX),
            y = point.y.coerceIn(0, maxY),
        )
    }

    fun normalize(
        point: PixelPoint,
        containerWidth: Int,
        containerHeight: Int,
        itemWidth: Int,
        itemHeight: Int,
    ): NormalizedPoint {
        val clamped = clamp(point, containerWidth, containerHeight, itemWidth, itemHeight)
        val maxX = (containerWidth - itemWidth).coerceAtLeast(1)
        val maxY = (containerHeight - itemHeight).coerceAtLeast(1)
        return NormalizedPoint(
            x = (clamped.x.toFloat() / maxX.toFloat()).coerceIn(0f, 1f),
            y = (clamped.y.toFloat() / maxY.toFloat()).coerceIn(0f, 1f),
        )
    }

    fun denormalize(
        point: NormalizedPoint,
        containerWidth: Int,
        containerHeight: Int,
        itemWidth: Int,
        itemHeight: Int,
    ): PixelPoint {
        val maxX = (containerWidth - itemWidth).coerceAtLeast(0)
        val maxY = (containerHeight - itemHeight).coerceAtLeast(0)
        return PixelPoint(
            x = (point.x.coerceIn(0f, 1f) * maxX).toInt(),
            y = (point.y.coerceIn(0f, 1f) * maxY).toInt(),
        )
    }
}
