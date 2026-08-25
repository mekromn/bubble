package com.mekromn.bubble.heads.model

import kotlin.math.abs

object HeadCollisionResolver {
    fun resolve(
        preferred: PixelPoint,
        occupied: List<PixelPoint>,
        areaWidth: Int,
        areaHeight: Int,
        headWidth: Int,
        headHeight: Int,
        gap: Int,
    ): PixelPoint {
        val clamped = HeadPlacementMath.clamp(
            preferred,
            areaWidth,
            areaHeight,
            headWidth,
            headHeight,
        )
        if (occupied.none { overlaps(clamped, it, headWidth, headHeight, gap) }) return clamped

        val stepX = (headWidth + gap).coerceAtLeast(1)
        val stepY = (headHeight + gap).coerceAtLeast(1)
        val maxX = (areaWidth - headWidth).coerceAtLeast(0)
        val maxY = (areaHeight - headHeight).coerceAtLeast(0)
        val candidates = buildList {
            var y = 0
            while (y <= maxY) {
                var x = 0
                while (x <= maxX) {
                    add(PixelPoint(x, y))
                    x += stepX
                }
                if (maxX % stepX != 0) add(PixelPoint(maxX, y))
                y += stepY
            }
            if (maxY % stepY != 0) {
                var x = 0
                while (x <= maxX) {
                    add(PixelPoint(x, maxY))
                    x += stepX
                }
                add(PixelPoint(maxX, maxY))
            }
        }

        return candidates
            .asSequence()
            .filter { candidate ->
                occupied.none { existing -> overlaps(candidate, existing, headWidth, headHeight, gap) }
            }
            .minByOrNull { candidate ->
                abs(candidate.x - clamped.x) + abs(candidate.y - clamped.y)
            }
            ?: clamped
    }

    fun overlaps(
        first: PixelPoint,
        second: PixelPoint,
        width: Int,
        height: Int,
        gap: Int,
    ): Boolean {
        val paddedWidth = width + gap.coerceAtLeast(0)
        val paddedHeight = height + gap.coerceAtLeast(0)
        return abs(first.x - second.x) < paddedWidth &&
            abs(first.y - second.y) < paddedHeight
    }
}
