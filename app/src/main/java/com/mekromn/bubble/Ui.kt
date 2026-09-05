package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/** Neutral black glass. Cached native gradients/highlights, no screen capture or blur loop. */
internal object Ui {
    const val BG = GlassPalette.BACKGROUND
    const val SURFACE = GlassPalette.SURFACE
    const val SURFACE_HIGH = GlassPalette.RAISED
    const val ACCENT = GlassPalette.ACCENT
    const val ACTIVE = GlassPalette.ACTIVE
    // Source compatibility for existing controls. These values are deliberately GREY, not hues.
    const val BLUE = ACCENT
    const val MINT = ACTIVE
    const val TEXT = GlassPalette.TEXT
    const val MUTED = GlassPalette.MUTED
    const val LINE = GlassPalette.EDGE
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val normalFont = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mediumFont = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun dp(c: Context, n: Float): Int = (n * c.resources.displayMetrics.density + 0.5f).toInt()
    fun shape(c: Context, color: Int = SURFACE, radius: Float = 24f, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            when (color) {
                SURFACE -> colors = intArrayOf(GlassPalette.TOP, GlassPalette.MIDDLE, GlassPalette.BOTTOM)
                SURFACE_HIGH -> colors = intArrayOf(0xf23c3c3c.toInt(), 0xf21c1c1c.toInt(), 0xf80a0a0a.toInt())
                BG -> colors = intArrayOf(0xff151515.toInt(), BG, 0xff020202.toInt())
                else -> setColor(color)
            }
            cornerRadius = dp(c, radius).toFloat()
            val rim = border ?: if (radius > 0 && (color == SURFACE || color == SURFACE_HIGH)) LINE else null
            if (rim != null) setStroke(dp(c, 1f).coerceAtLeast(1), rim)
        }
    fun ripple(c: Context, color: Int = SURFACE, radius: Float = 24f) =
        RippleDrawable(ColorStateList.valueOf(GlassPalette.RIPPLE), shape(c, color, radius), null)
    fun text(c: Context, value: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color); typeface = if (bold) mediumFont else normalFont; includeFontPadding = false
    }
    /** Only native chrome gets a temporary animation layer; Gecko's page is never cached here. */
    fun show(view: View, shown: Boolean) {
        val wasVisible = view.isLaidOut && view.visibility == View.VISIBLE
        view.animate().cancel(); view.animate().withEndAction(null)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.visibility = if (shown) View.VISIBLE else View.GONE; view.alpha = 1f; view.translationY = 0f; return
        }
        if (shown) {
            view.visibility = View.VISIBLE
            if (!wasVisible) { view.alpha = 0f; view.translationY = dp(view.context, 18f).toFloat() }
            view.animate().withLayer().alpha(1f).translationY(0f).setDuration(210).setInterpolator(ease).start()
        } else if (wasVisible) {
            view.animate().withLayer().alpha(0f).translationY(dp(view.context, 12f).toFloat()).setDuration(150)
                .setInterpolator(ease).withEndAction { view.visibility = View.GONE; view.alpha = 1f; view.translationY = 0f }.start()
        } else view.visibility = View.GONE
    }
}

/** Cached vector control, neutral greys with tactile, reversible press feedback. */
internal class GlyphView(c: Context, var glyph: String, label: String, private val accented: Boolean = false) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val iconSize = Ui.dp(c, 24f).toFloat()
    private var countLabel = "0"
    var count: Int = 0
        set(value) { if (field != value) { field = value; countLabel = if (value > 99) "99+" else value.toString(); invalidate() } }
    init {
        contentDescription = label; isFocusable = true; isClickable = true
        background = Ui.ripple(c, if (accented) Ui.SURFACE_HIGH else android.graphics.Color.TRANSPARENT, 16f)
        minimumWidth = Ui.dp(c, 48f); minimumHeight = Ui.dp(c, 48f); tooltipText = label
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            animate().scaleX(if (isPressed) .9f else 1f).scaleY(if (isPressed) .9f else 1f)
                .setDuration(if (isPressed) 85L else 165L).setInterpolator(Ui.ease).start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = iconSize
        canvas.save(); canvas.translate((width-size)/2, (height-size)/2); canvas.scale(size/24, size/24)
        paint.color = if (accented) Ui.ACCENT else Ui.TEXT
        paint.alpha = if (isEnabled) 255 else 75; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; path.reset()
        when (glyph) {
            "back", "forward" -> {
                if (glyph == "forward") { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
                path.moveTo(14f, 5f); path.lineTo(7f, 12f); path.lineTo(14f, 19f); canvas.drawPath(path, paint)
            }
            "add" -> { canvas.drawLine(12f,5f,12f,19f,paint); canvas.drawLine(5f,12f,19f,12f,paint) }
            "close" -> { canvas.drawLine(6f,6f,18f,18f,paint); canvas.drawLine(18f,6f,6f,18f,paint) }
            "menu" -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(5f,12f,1.5f,paint); canvas.drawCircle(12f,12f,1.5f,paint); canvas.drawCircle(19f,12f,1.5f,paint)
            }
            "reload" -> {
                canvas.drawArc(4f,4f,20f,20f,35f,290f,false,paint)
                path.moveTo(19f,3f); path.lineTo(19f,8f); path.lineTo(14f,8f); canvas.drawPath(path,paint)
            }
            "bubble" -> {
                canvas.drawRoundRect(3f,3f,21f,17f,6f,6f,paint)
                path.moveTo(7f,17f); path.lineTo(7f,21f); path.lineTo(12f,17f); canvas.drawPath(path,paint)
                canvas.drawLine(8f,9f,16f,9f,paint)
            }
            "collapse" -> canvas.drawLine(5f,12f,19f,12f,paint)
            "expand" -> {
                path.moveTo(9f,4f); path.lineTo(4f,4f); path.lineTo(4f,9f)
                path.moveTo(15f,4f); path.lineTo(20f,4f); path.lineTo(20f,9f)
                path.moveTo(20f,15f); path.lineTo(20f,20f); path.lineTo(15f,20f)
                path.moveTo(9f,20f); path.lineTo(4f,20f); path.lineTo(4f,15f); canvas.drawPath(path,paint)
            }
            "pip", "float" -> {
                canvas.drawRoundRect(2f,4f,22f,20f,3f,3f,paint); paint.style=Paint.Style.FILL
                canvas.drawRoundRect(12f,11f,19f,17f,1.5f,1.5f,paint)
            }
            "resize" -> { canvas.drawLine(8f,19f,19f,8f,paint); canvas.drawLine(14f,19f,19f,14f,paint) }
            "tabs" -> {
                canvas.drawRoundRect(3f,3f,21f,21f,5f,5f,paint); paint.style=Paint.Style.FILL; paint.textSize=10f; paint.textAlign=Paint.Align.CENTER
                canvas.drawText(countLabel,12f,15.7f,paint)
            }
            else -> { canvas.drawCircle(12f,12f,8f,paint); canvas.drawLine(4f,12f,20f,12f,paint); canvas.drawOval(8f,4f,16f,20f,paint) }
        }
        canvas.restore()
    }
}
