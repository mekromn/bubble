package com.mekromn.bubble

/** Monochrome neutral glass tokens. Alpha is intentional: chrome is translucent instead of a
 * painted imitation of glass. Floating windows add platform cross-window blur when available. */
internal object GlassPalette {
    const val BACKGROUND = 0xff050505.toInt()
    const val SURFACE = 0xb2101010.toInt()
    const val RAISED = 0xc0222222.toInt()
    const val ACCENT = 0xffd2d2d2.toInt()
    const val ACTIVE = 0xfff0f0f0.toInt()
    const val TEXT = 0xfff5f5f5.toInt()
    const val MUTED = 0xffb8b8b8.toInt()
    const val EDGE = 0x66c0c0c0
    const val RIPPLE = 0x30ffffff
    const val TOP = 0xa8323232.toInt()
    const val MIDDLE = 0xb5101010.toInt()
    const val BOTTOM = 0xc8050505.toInt()
}
