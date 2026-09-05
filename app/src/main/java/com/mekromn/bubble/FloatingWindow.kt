package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

internal enum class FloatingMode { BUBBLE, CHOOSER, CHAT }

/** One interactive black-glass overlay. Existing GeckoSessions are handed between this
 * TextureView and fullscreen SurfaceView; chrome never creates a replacement session. */
internal class FloatingWindow(private val service: BubbleService, private val workspace: Workspace) {
    private val context=themedWindowContext(service)
    private val manager=context.getSystemService(WindowManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val motion=WindowMotion()
    private val dismiss=DismissTarget(context)
    private var destroyed=false
    private var frameQueued=false
    private var hiding=false
    private var imeBottom=0
    private var params=WindowManager.LayoutParams()
    private var rectangle=WindowBox(0,0,68,68)
    private var target=rectangle
    private var panelBox: WindowBox?=null
    private var gestureInitial=rectangle
    private var gestureX=0f
    private var gestureY=0f
    private var dragging=false
    private var held=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val hold=Runnable { if(!dragging && mode==FloatingMode.BUBBLE) { held=true; openChat(workspace.selectedId) } }
    private var list: ConversationList?=null
    private var heading: TextView?=null
    private var subtitle: TextView?=null
    private var error: TextView?=null
    private var count: GlyphView?=null
    private var backControl: GlyphView?=null
    private var bubble: GlassBubble?=null
    private var gecko: LiveGeckoView?=null
    private var backCallback: android.window.OnBackInvokedCallback?=null
    private var backDispatcher: android.window.OnBackInvokedDispatcher?=null
    var mode=FloatingMode.BUBBLE
        private set
    val geckoView: GeckoView? get()=gecko
    val box: WindowBox get()=rectangle
    val isTransitioning: Boolean get()=motion.busy || hiding
    val dismissTargetAttached: Boolean get()=dismiss.attached
    private val root=object: FrameLayout(context) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus); main.post { if(!destroyed)workspace.applyPolicy() }
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if(event.keyCode==KeyEvent.KEYCODE_BACK && mode!=FloatingMode.BUBBLE) {
                if(event.action==KeyEvent.ACTION_UP)back(); return true
            }
            return super.dispatchKeyEvent(event)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action==MotionEvent.ACTION_OUTSIDE && mode==FloatingMode.CHOOSER && !workspace.quickMenuVisible) { collapse(); return true }
            return super.onTouchEvent(event)
        }
    }
    private val listener: () -> Unit={ render() }
    private fun back() {
        if(workspace.quickMenuVisible)QuickPanel.dismissFor(root)
        else if(imeBottom>0)context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
        else collapse()
    }
    fun attach(initial: FloatingMode=FloatingMode.BUBBLE, origin: WindowBox?=null) {
        root.isFocusableInTouchMode=true; root.elevation=d(12).toFloat()
        rectangle=if(origin==null)headBox() else WindowGeometry.fit(WindowBox(origin.x+origin.width/2-d(32),origin.y+origin.height/2-d(32),d(64),d(64)),safeArea()); target=rectangle
        params=WindowManager.LayoutParams(rectangle.width,rectangle.height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags(FloatingMode.BUBBLE),PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; x=rectangle.x; y=rectangle.y
            title="Bubble floating workspace"; softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        build(FloatingMode.BUBBLE); RenderPolicy.vote(context,root,params)
        manager.addView(root,params); workspace.listen(listener)
        root.post {
            if(!destroyed && Build.VERSION.SDK_INT>=33) {
                backDispatcher=root.findOnBackInvokedDispatcher()
                backCallback=android.window.OnBackInvokedCallback { back() }
                backCallback?.let { backDispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it) }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val bottom=if(insets.isVisible(WindowInsetsCompat.Type.ime()))insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if(bottom!=imeBottom) {
                if(imeBottom==0 && mode!=FloatingMode.BUBBLE)panelBox=rectangle
                imeBottom=bottom
                if(mode==FloatingMode.CHAT)main.post { if(!destroyed && mode==FloatingMode.CHAT && !motion.busy)place(expandedBox(),false) }
            }
            insets
        }
        when(initial) {
            FloatingMode.CHOOSER -> showChooser()
            FloatingMode.CHAT -> openChat(workspace.selectedId)
            else -> if(ValueAnimator.areAnimatorsEnabled()) { root.alpha=0f; root.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease).start() }
        }
    }
    fun showChooser()=present(FloatingMode.CHOOSER)
    fun openChat(id: String) {
        if(!workspace.ready || workspace.tabs.none { it.id==id })return
        workspace.select(id); present(FloatingMode.CHAT)
    }
    /** Reverse the reveal, never squash the webpage. Move only the circle afterward. */
    fun collapse() {
        if(destroyed || mode==FloatingMode.BUBBLE || hiding)return
        if(service.prefersEdge()) { exitToRestingEdge(); return }
        QuickPanel.dismissFor(root); main.removeCallbacks(hold); dismiss.hide(true)
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
        root.animate().cancel(); root.animate().withEndAction(null); root.alpha=1f
        val end=headBox(); val radius=d(32)
        val cx=(end.x+radius-rectangle.x).coerceIn(radius,(rectangle.width-radius).coerceAtLeast(radius))
        val cy=(end.y+radius-rectangle.y).coerceIn(radius,(rectangle.height-radius).coerceAtLeast(radius))
        val startHead=WindowBox(rectangle.x+cx-radius,rectangle.y+cy-radius,d(64),d(64))
        motion.reveal(root,cx,cy,radius.toFloat(),false) {
            if(!destroyed) { switchContents(FloatingMode.BUBBLE); place(startHead,true); motion.move(startHead,end,{ place(it,false) }) { render() } }
        }
    }
    /** Long press is the same notification-only state as a successful drop on the x target. */
    internal fun hideToNotification() {
        if(destroyed || hiding)return
        if(!service.canPark()) { service.park(); return }
        QuickPanel.dismissFor(root); main.removeCallbacks(hold); dismiss.hide(true); motion.cancel()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
        hiding=true
        root.animate().cancel(); root.animate().withEndAction(null)
        if(!ValueAnimator.areAnimatorsEnabled()) { if(!service.park())hiding=false; return }
        root.animate().alpha(0f).setDuration(140).setInterpolator(Ui.ease).withEndAction {
            if(!destroyed && !service.park()) { hiding=false; root.alpha=1f }
        }.start()
    }
    private fun exitToRestingEdge() {
        QuickPanel.dismissFor(root); main.removeCallbacks(hold); dismiss.hide(true); motion.cancel()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
        hiding=true; root.animate().cancel(); root.animate().withEndAction(null)
        if(!ValueAnimator.areAnimatorsEnabled()) { service.showMinimized(); return }
        val direction=if(AccessPreferences.get(context).options.left)-1 else 1
        // A short directional exit toward the selected edge, no squashing or page re-layout.
        root.animate().alpha(0f).translationX(direction*d(18).toFloat()).setDuration(170).setInterpolator(Ui.ease)
            .withEndAction { if(!destroyed)service.showMinimized() }.start()
    }
    private fun present(next: FloatingMode) {
        if(destroyed || hiding)return
        if(mode==next) { render(); return }
        QuickPanel.dismissFor(root); main.removeCallbacks(hold); dismiss.hide(true); motion.cancel()
        root.animate().cancel(); root.animate().withEndAction(null); root.alpha=1f
        val previous=mode; val from=rectangle
        if(next==FloatingMode.CHOOSER) { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0); imeBottom=0 }
        switchContents(next)
        var destination=expandedBox()
        if(previous==FloatingMode.BUBBLE) {
            val r=d(32); val cx=from.x+r; val cy=from.y+r
            destination=WindowGeometry.fit(destination.copy(
                x=destination.x.coerceIn(cx-destination.width+r,cx-r),
                y=destination.y.coerceIn(cy-destination.height+r,cy-r)),safeArea())
            panelBox=destination; place(destination,true); render()
            val localX=cx-destination.x; val localY=cy-destination.y
            val mark=GlassBubble(context).apply { isClickable=false; isFocusable=false; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            root.addView(mark,FrameLayout.LayoutParams(d(64),d(64)).apply { leftMargin=localX-r; topMargin=localY-r })
            motion.reveal(root,localX,localY,r.toFloat(),true) { if(mark.parent===root)root.removeView(mark) }
            mark.animate().alpha(0f).setDuration(110).start()
        } else {
            destination=WindowGeometry.fit(panelBox ?: from,safeArea()); place(destination,true); render()
            root.getChildAt(0)?.let { content -> if(ValueAnimator.areAnimatorsEnabled()) {
                content.alpha=.35f; content.animate().alpha(1f).setDuration(140).setInterpolator(Ui.ease).start()
            } }
        }
    }
    private fun switchContents(next: FloatingMode) {
        gecko?.let { workspace.detachSurface(it); (it.parent as? ViewGroup)?.removeView(it) }
        mode=next; workspace.floatingVisible=next==FloatingMode.CHAT; build(next); workspace.applyPolicy()
    }
    private fun build(next: FloatingMode) {
        root.removeAllViews(); list=null; heading=null; subtitle=null; error=null; bubble=null; count=null; backControl=null
        root.clipToOutline=next!=FloatingMode.BUBBLE
        root.background=if(next==FloatingMode.BUBBLE)null else Ui.shape(context,Ui.SURFACE,26f,Ui.LINE)
        if(next==FloatingMode.BUBBLE) {
            val mark=GlassBubble(context); bubble=mark
            mark.setOnClickListener { showChooser() }; mark.setOnLongClickListener { openChat(workspace.selectedId); true }
            mark.setOnTouchListener { _,event -> drag(event,false,true) }
            root.addView(mark,FrameLayout.LayoutParams(-1,-1)); accessibilityMoves(mark); return
        }
        val column=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        root.addView(column,FrameLayout.LayoutParams(-1,-1))
        val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(d(4),0,d(4),0); background=Ui.shape(context,Ui.SURFACE,0f) }
        if(next==FloatingMode.CHAT) {
            backControl=control("back","Back in webpage") { if(workspace.selected?.back==true)workspace.selected?.session?.goBack() }
            top.addView(backControl,LinearLayout.LayoutParams(d(48),d(48)))
        }
        val labels=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(d(8),0,0,0)
            contentDescription="Drag floating window"; isClickable=true; setOnTouchListener { _,event -> drag(event,false,false) }
        }
        heading=Ui.text(context,if(next==FloatingMode.CHOOSER)"Your chats" else "ChatGPT",14f,Ui.TEXT,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        subtitle=Ui.text(context,"",10f,Ui.MUTED).apply { maxLines=1; setPadding(0,d(3),0,0) }
        labels.addView(heading); labels.addView(subtitle); top.addView(labels,LinearLayout.LayoutParams(0,-1,1f))
        if(next==FloatingMode.CHAT) {
            count=control("tabs","Choose another conversation") { showChooser() }
            top.addView(count,LinearLayout.LayoutParams(d(48),d(48)))
            top.addView(control("expand","Open fullscreen") { fullscreen() },LinearLayout.LayoutParams(d(48),d(48)))
        } else top.addView(control("add","New floating ChatGPT chat",true) { if(workspace.ready)openChat(workspace.create().id) },LinearLayout.LayoutParams(d(48),d(48)))
        top.addView(control("collapse","Minimize floating window") { collapse() },LinearLayout.LayoutParams(d(48),d(48)))
        column.addView(top,LinearLayout.LayoutParams(-1,d(52)))
        if(next==FloatingMode.CHOOSER) {
            list=ConversationList(context,{ openChat(it) },{ workspace.close(it) },{ _,id -> QuickMenus.tabOptions(top,workspace,id,::openChat) })
            column.addView(list,LinearLayout.LayoutParams(-1,0,1f))
            val footer=LinearLayout(context)
            footer.addView(Ui.text(context,"Chat tools",12f,Ui.ACCENT,true).apply {
                gravity=Gravity.CENTER; background=Ui.ripple(context); setOnClickListener { QuickMenus.tools(top,workspace,::openChat) }
            },LinearLayout.LayoutParams(0,d(48),1f))
            footer.addView(Ui.text(context,"Edge access",12f,Ui.ACCENT).apply {
                gravity=Gravity.CENTER; background=Ui.ripple(context); setOnClickListener { AccessMenu.show(top,workspace) }
            },LinearLayout.LayoutParams(0,d(48),1f))
            footer.addView(Ui.text(context,"Reply sound",12f,Ui.ACCENT).apply {
                gravity=Gravity.CENTER; background=Ui.ripple(context); contentDescription="ChatGPT notification settings"; setOnClickListener { Replies.settings(context) }
            },LinearLayout.LayoutParams(0,d(48),1f))
            column.addView(footer)
        } else {
            val web=gecko ?: LiveGeckoView(context).also { it.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW); gecko=it }
            val content=FrameLayout(context); content.addView(web,FrameLayout.LayoutParams(-1,-1))
            error=Ui.text(context,"",13f,Ui.TEXT).apply {
                setPadding(d(20),d(20),d(20),d(20)); background=Ui.shape(context,Ui.SURFACE,20f)
                gravity=Gravity.CENTER; visibility=View.GONE; setOnClickListener { workspace.retry() }
            }
            content.addView(error,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply { setMargins(d(12),0,d(12),0) })
            column.addView(content,LinearLayout.LayoutParams(-1,0,1f))
            val resize=control("resize","Resize floating chat") { }
            resize.setOnTouchListener { _,event -> drag(event,true,false) }
            root.addView(resize,FrameLayout.LayoutParams(d(48),d(32),Gravity.BOTTOM or Gravity.RIGHT)); content.setPadding(0,0,0,d(24))
        }
        RenderPolicy.vote(context,root,params)
    }
    private fun control(glyph: String,label: String,accent: Boolean=false,click: () -> Unit)=GlyphView(context,glyph,label,accent).apply {
        setOnClickListener { click() }
        when(glyph) {
            "back" -> { tooltipText="Back · hold for Forward, Stop and Refresh"; setOnLongClickListener { QuickMenus.navigation(this,workspace); true } }
            "collapse" -> { tooltipText="Minimize · hold to hide in notification"; setOnLongClickListener { hideToNotification(); true } }
            "tabs" -> { tooltipText="Tabs · hold for quick tabs"; setOnLongClickListener { QuickMenus.tabs(this,workspace,::openChat); true } }
        }
    }
    private fun render() {
        if(destroyed)return
        bubble?.update(workspace.tabs.size,workspace.tabs.count { it.unread },workspace.tabs.any { it.generating }); list?.refresh(workspace)
        if(mode==FloatingMode.CHOOSER) { val text="${workspace.tabs.size} conversations · drag to move"; if(subtitle?.text!=text)subtitle?.text=text; return }
        if(mode!=FloatingMode.CHAT)return
        val tab=workspace.selected ?: return
        count?.count=workspace.tabs.size
        backControl?.let { val alpha=if(tab.back)1f else .55f; if(it.alpha!=alpha)it.alpha=alpha }
        if(heading?.text!=tab.displayName)heading?.text=tab.displayName
        val state=when { tab.generating -> "Generating · kept live"; tab.loading -> "Loading ${tab.progress}%"; else -> "${Policy.host(tab.url)} · live" }
        if(subtitle?.text!=state)subtitle?.text=state
        gecko?.let { view -> val session=tab.session; if(session!=null && session.isOpen)workspace.attachSurface(view,session) else if(view.session!=null)workspace.detachSurface(view) }
        error?.visibility=if(tab.error==null)View.GONE else View.VISIBLE
        val message=tab.error?.plus("\n\nTap to retry").orEmpty(); if(error?.text?.toString()!=message)error?.text=message
        if(tab.unread && workspace.chatVisible) { tab.unread=false; Replies.clear(context,tab.id); workspace.changed(true) }
    }
    private fun fullscreen() {
        QuickPanel.dismissFor(root)
        try { service.startActivity(Intent(service,BrowserActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra(BrowserActivity.EXTRA_TAB,workspace.selectedId)
        }) } catch(_:RuntimeException) { Toast.makeText(context,"Could not open the browser window",Toast.LENGTH_SHORT).show() }
    }
    fun offerExternal(raw: String) { Toast.makeText(context,"Open fullscreen to confirm this external-app link.",Toast.LENGTH_LONG).show() }
    private fun flags(next: FloatingMode): Int {
        val base=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return if(next==FloatingMode.BUBBLE)base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else base
    }
    private fun safeArea(): WindowBox {
        if(Build.VERSION.SDK_INT>=30) {
            val metrics=manager.maximumWindowMetrics
            val inset=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return WindowBox(inset.left+d(4),inset.top+d(4),(metrics.bounds.width()-inset.left-inset.right-d(8)).coerceAtLeast(1),(metrics.bounds.height()-inset.top-inset.bottom-d(8)).coerceAtLeast(1))
        }
        val p=Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return WindowBox(d(4),d(28),(p.x-d(8)).coerceAtLeast(1),(p.y-d(60)).coerceAtLeast(1))
    }
    private fun headBox()=WindowGeometry.placed(safeArea(),workspace.bubbleX,workspace.bubbleY,d(64),d(64))
    private fun expandedBox(): WindowBox {
        val safe=safeArea()
        val width=(safe.width*WindowGeometry.fraction(workspace.windowWidth,.92f)).toInt().coerceAtLeast(d(280)).coerceAtMost(d(560))
        val height=(safe.height*WindowGeometry.fraction(workspace.windowHeight,.72f)).toInt().coerceAtLeast(d(260))
        val resting=panelBox ?: WindowGeometry.placed(safe,workspace.windowX,workspace.windowY,width,height)
        val area=if(mode==FloatingMode.CHAT && imeBottom>0)safe.copy(height=(safe.height-imeBottom).coerceAtLeast(d(180))) else safe
        return WindowGeometry.fit(resting,area)
    }
    private fun place(box: WindowBox,flagsChanged: Boolean) {
        if(destroyed)return
        val fitted=WindowGeometry.fit(box,safeArea())
        if(!flagsChanged && rectangle==fitted)return
        rectangle=fitted; target=fitted
        params.x=fitted.x; params.y=fitted.y; params.width=fitted.width; params.height=fitted.height; params.flags=flags(mode)
        try { manager.updateViewLayout(root,params) } catch(_:RuntimeException) { service.stopSelf() }
    }
    private fun drag(event: MotionEvent,resize: Boolean,isHead: Boolean): Boolean {
        if(hiding)return true
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                QuickPanel.dismissFor(root); main.removeCallbacks(hold); motion.cancel(); root.animate().cancel(); root.animate().withEndAction(null)
                root.alpha=1f; root.scaleX=1f; root.scaleY=1f
                gestureInitial=rectangle; gestureX=event.rawX; gestureY=event.rawY; dragging=false; held=false
                if(isHead)main.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(held)return true
                val dx=event.rawX-gestureX; val dy=event.rawY-gestureY
                if(!dragging && (abs(dx)>slop || abs(dy)>slop)) { dragging=true; main.removeCallbacks(hold); if(isHead && service.canPark())dismiss.show(safeArea()) }
                if(dragging) {
                    val raw=if(resize)gestureInitial.copy(width=(gestureInitial.width+dx).toInt().coerceAtLeast(d(280)),height=(gestureInitial.height+dy).toInt().coerceAtLeast(d(260)))
                        else gestureInitial.copy(x=(gestureInitial.x+dx).toInt(),y=(gestureInitial.y+dy).toInt())
                    var projected=raw
                    if(isHead && dismiss.attached) {
                        val before=dismiss.armed; val armed=dismiss.track(raw.x+raw.width/2f,raw.y+raw.height/2f)
                        if(armed && !before)root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if(armed)projected=raw.copy(x=(dismiss.centerX-raw.width/2).toInt(),y=(dismiss.centerY-raw.height/2).toInt())
                    }
                    target=WindowGeometry.fit(projected,safeArea())
                    if(!frameQueued) { frameQueued=true; root.postOnAnimation { frameQueued=false; if(!destroyed && dragging)place(target,false) } }
                }
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                val completed=event.actionMasked==MotionEvent.ACTION_UP; val wasDragging=dragging
                if(completed && wasDragging && isHead && dismiss.attached) dismiss.track(gestureInitial.x+(event.rawX-gestureX)+gestureInitial.width/2f,gestureInitial.y+(event.rawY-gestureY)+gestureInitial.height/2f)
                val shouldHide=completed && wasDragging && isHead && dismiss.armed
                if(wasDragging)place(target,false)
                dragging=false
                if(shouldHide) {
                    hiding=true; dismiss.hide(); root.pivotX=root.width/2f; root.pivotY=root.height/2f
                    root.animate().scaleX(.35f).scaleY(.35f).alpha(0f).setDuration(130).setInterpolator(Ui.ease).withEndAction {
                        if(!destroyed && !service.park()) { hiding=false; root.alpha=1f; root.scaleX=1f; root.scaleY=1f; place(headBox(),false) }
                    }.start()
                } else {
                    dismiss.hide()
                    if(!completed)place(gestureInitial,false) else if(wasDragging)savePosition(resize) else if(!held && isHead)bubble?.performClick()
                }
                return true
            }
        }
        return true
    }
    private fun savePosition(resized: Boolean) {
        if(mode!=FloatingMode.BUBBLE && imeBottom>0)return
        val safe=safeArea()
        val nx=if(safe.width>rectangle.width)(rectangle.x-safe.x).toFloat()/(safe.width-rectangle.width) else .5f
        val ny=if(safe.height>rectangle.height)(rectangle.y-safe.y).toFloat()/(safe.height-rectangle.height) else .5f
        if(mode==FloatingMode.BUBBLE) { workspace.bubbleX=nx; workspace.bubbleY=ny }
        else { panelBox=rectangle; workspace.windowX=nx; workspace.windowY=ny
            if(resized) { workspace.windowWidth=rectangle.width.toFloat()/safe.width; workspace.windowHeight=rectangle.height.toFloat()/safe.height } }
        workspace.checkpoint()
    }
    private fun accessibilityMoves(view: View) {
        listOf("Move left" to (-1 to 0),"Move right" to (1 to 0),"Move up" to (0 to -1),"Move down" to (0 to 1)).forEach { (name,direction) ->
            ViewCompat.addAccessibilityAction(view,name) { _,_ -> place(rectangle.copy(x=rectangle.x+direction.first*d(40),y=rectangle.y+direction.second*d(40)),false); savePosition(false); true }
        }
        ViewCompat.addAccessibilityAction(view,"Hide in notification") { _,_ -> service.park() }
    }
    fun configurationChanged() {
        if(!destroyed) { QuickPanel.dismissFor(root); motion.cancel(); dismiss.hide(true); panelBox=null; place(if(mode==FloatingMode.BUBBLE)headBox() else expandedBox(),false) }
    }
    fun destroy() {
        if(destroyed)return
        QuickPanel.dismissFor(root); destroyed=true; motion.cancel(); dismiss.hide(true); workspace.unlisten(listener); main.removeCallbacksAndMessages(null)
        root.animate().cancel(); root.animate().withEndAction(null)
        if(Build.VERSION.SDK_INT>=33)backCallback?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        gecko?.let { workspace.detachSurface(it) }; workspace.floatingVisible=false; workspace.applyPolicy()
        try { manager.removeView(root) } catch(_:RuntimeException) { }
        gecko=null
    }
    private fun d(n: Int)=Ui.dp(context,n.toFloat())
    companion object {
        private fun themedWindowContext(service: Context): Context {
            val base=if(Build.VERSION.SDK_INT>=30) {
                val display=service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            } else service
            return ContextThemeWrapper(base,R.style.Theme_Bubble)
        }
    }
}
