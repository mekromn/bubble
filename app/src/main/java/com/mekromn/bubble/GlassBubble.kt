package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View

/** Neutral frosted-glass bubble. Shaders live in fixed design coordinates, are allocated once,
 * and use the hardware canvas. No background screenshots, bitmap cache or idle animator. */
internal class GlassBubble(context: Context) : View(context) {
    private val transparent = VisualEffects.transparencyEnabled()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val body = LinearGradient(10f,5f,48f,61f,
        if (transparent) intArrayOf(0xa83b3b3b.toInt(),0xb4151515.toInt(),0xcc050505.toInt())
        else intArrayOf(0xff3b3b3b.toInt(),0xff151515.toInt(),0xff050505.toInt()),
        floatArrayOf(0f,.42f,1f),Shader.TileMode.CLAMP)
    private val edge = LinearGradient(9f,5f,54f,61f,
        if (transparent) intArrayOf(0xc8ffffff.toInt(),0x68777777,0x38505050,0x807e7e7e.toInt())
        else intArrayOf(0xffffffff.toInt(),0xff777777.toInt(),0xff505050.toInt(),0xff7e7e7e.toInt()),
        floatArrayOf(0f,.35f,.72f,1f),Shader.TileMode.CLAMP)
    private val sheen = LinearGradient(17f,8f,30f,33f,
        if (transparent) 0x40ffffff else 0xff4a4a4a.toInt(),
        if (transparent) 0x00ffffff else 0xff171717.toInt(),Shader.TileMode.CLAMP)
    private val badge = LinearGradient(45f,4f,57f,24f,0xf2e4e4e4.toInt(),0xe8999999.toInt(),Shader.TileMode.CLAMP)
    private var label="1"
    private var unread=0
    private var busy=false
    init {
        isClickable=true; isFocusable=true; contentDescription="Choose a conversation, 1 tab"
        // Global opaque mode overrides the resting-bubble opacity slider: a partially transparent
        // whole View would reintroduce cross-window blending even though its material is opaque.
        alpha=if (transparent) AccessPreferences.get(context).options.bubbleOpacity else 1f
    }
    fun update(total: Int,count: Int,generating:Boolean) {
        val next=if(total>99)"99+" else total.toString()
        if(label==next && unread==count && busy==generating)return
        val fresh=count>unread; label=next; unread=count; busy=generating
        contentDescription="Choose a conversation, $total tabs, $count unread${if(busy)", generating" else ""}"
        invalidate()
        if(fresh && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            animate().cancel(); scaleX=.94f; scaleY=.94f
            animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(Ui.ease).start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.save(); canvas.scale(width/64f,height/64f)
        paint.style=Paint.Style.FILL; paint.shader=body; canvas.drawCircle(32f,32f,29f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.15f; paint.shader=edge
        canvas.drawCircle(32f,32f,28.4f,paint)
        paint.style=Paint.Style.FILL; paint.shader=sheen
        canvas.drawOval(12f,7f,50f,32f,paint)
        paint.shader=null; paint.style=Paint.Style.STROKE
        paint.strokeWidth=2f; paint.strokeJoin=Paint.Join.ROUND; paint.strokeCap=Paint.Cap.ROUND
        paint.color=if(busy)Ui.ACTIVE else Ui.ACCENT
        canvas.drawRoundRect(18f,19f,45f,40f,7f,7f,paint)
        path.reset(); path.moveTo(24f,40f); path.lineTo(24f,46f); path.lineTo(31f,40f); canvas.drawPath(path,paint)
        canvas.drawLine(25f,28f,38f,28f,paint)
        paint.style=Paint.Style.FILL; paint.shader=badge; canvas.drawCircle(51f,13f,10f,paint)
        paint.shader=null; paint.style=Paint.Style.STROKE; paint.color=0xa0ffffff.toInt(); paint.strokeWidth=.75f
        canvas.drawCircle(51f,13f,9.6f,paint)
        paint.style=Paint.Style.FILL; paint.color=0xff080808.toInt(); paint.textSize=10f; paint.typeface=Typeface.DEFAULT_BOLD; paint.textAlign=Paint.Align.CENTER
        canvas.drawText(label,51f,16.5f,paint)
        if(unread>0) { paint.color=Ui.TEXT; canvas.drawCircle(14f,51f,3f,paint) }
        canvas.restore()
    }
    override fun performClick(): Boolean=super.performClick()
}
