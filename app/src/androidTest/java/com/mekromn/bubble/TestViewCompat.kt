package com.mekromn.bubble

import android.graphics.Rect
import android.view.View

/** Keeps test type inference from erasing View when JUnit's Java `fail()` is used in an Elvis expression. */
internal fun Any.getGlobalVisibleRect(rect: Rect): Boolean = (this as? View)?.getGlobalVisibleRect(rect) ?: false
