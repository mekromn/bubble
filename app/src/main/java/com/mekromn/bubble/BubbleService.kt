package com.mekromn.bubble

import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import kotlin.math.abs

/** One explicitly requested, non-focusable overlay. Never launched by a database observer or boot. */
class BubbleService:Service(){
    private lateinit var workspace:Workspace
    private lateinit var manager:WindowManager
    private var head:Head?=null
    private var params:WindowManager.LayoutParams?=null
    private val main=Handler(Looper.getMainLooper())
    private var minX=0;private var minY=0;private var maxX=0;private var maxY=0
    private var pendingFrame=false
    private var targetX=0;private var targetY=0
    private val listener:()->Unit={refresh()}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate();manager=getSystemService(WindowManager::class.java)
        workspace=Workspace.get(this)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==HIDE){stopSelf();return START_NOT_STICKY}
        @Suppress("DEPRECATION") val receiver=intent?.getParcelableExtra<ResultReceiver>(READY)
        try {
            if(!Settings.canDrawOverlays(this)){receiver?.send(0,null);stopSelf();return START_NOT_STICKY}
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL,"Floating workspace",NotificationManager.IMPORTANCE_LOW))
            val hide=PendingIntent.getService(this,0,Intent(this,BubbleService::class.java).setAction(HIDE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Your workspace is floating").setContentText("Live ChatGPT tabs · tap to open")
                .setContentIntent(Replies.open(this,workspace.selectedId.takeIf{it.isNotEmpty()}))
                .addAction(Notification.Action.Builder(null,"Hide bubble",hide).build())
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build()
            if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1,notification)
            if(head==null)installHead()
            receiver?.send(1,null)
        }catch(_:RuntimeException){receiver?.send(0,null);stopSelf()}
        return START_NOT_STICKY
    }
    private fun installHead(){
        val view=Head()
        val size=Ui.dp(this,68f)
        val layout=WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;title="Bubble workspace";preferredRefreshRate=120f}
        params=layout;head=view;bounds()
        layout.x=Policy.coordinate(workspace.bubbleX,minX,maxX);layout.y=Policy.coordinate(workspace.bubbleY,minY,maxY)
        targetX=layout.x;targetY=layout.y
        view.setOnClickListener{open(false)};view.setOnLongClickListener{open(true);true}
        manager.addView(view,layout)
        workspace.listen(listener)
        if(Build.VERSION.SDK_INT>=35)view.setRequestedFrameRate(120f)
        if(ValueAnimator.areAnimatorsEnabled()){
            view.alpha=0f;view.scaleX=.82f;view.scaleY=.82f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(Ui.ease).start()
        }
        val actions=listOf("Move left" to Pair(-1,0),"Move right" to Pair(1,0),"Move up" to Pair(0,-1),"Move down" to Pair(0,1))
        actions.forEach{(label,direction)->ViewCompat.addAccessibilityAction(view,label){_,_->
            targetX=(targetX+direction.first*Ui.dp(this,40f)).coerceIn(minX,maxX)
            targetY=(targetY+direction.second*Ui.dp(this,40f)).coerceIn(minY,maxY)
            project();persistPosition();true
        }}
        ViewCompat.addAccessibilityAction(view,"Hide bubble"){_,_->stopSelf();true}
    }
    private fun open(tray:Boolean){
        try{startActivity(Intent(this,BrowserActivity::class.java).apply{
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BrowserActivity.EXTRA_TAB,workspace.selectedId);putExtra(BrowserActivity.EXTRA_TRAY,tray)
        })}catch(_:RuntimeException){return}
        // BrowserActivity stops this service once it has actually entered the foreground.
    }
    private fun refresh(){
        val view=head?:return
        view.update(workspace.tabs.size,workspace.tabs.count{it.unread},workspace.tabs.any{it.generating})
    }
    private fun bounds(){
        val size=params?.width?:Ui.dp(this,68f)
        if(Build.VERSION.SDK_INT>=30){
            val metrics=manager.currentWindowMetrics
            val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            minX=insets.left;minY=insets.top
            maxX=(metrics.bounds.width()-insets.right-size).coerceAtLeast(minX)
            maxY=(metrics.bounds.height()-insets.bottom-size).coerceAtLeast(minY)
        }else{
            val point=Point();@Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(point)
            minX=0;minY=Ui.dp(this,28f);maxX=(point.x-size).coerceAtLeast(0);maxY=(point.y-size-Ui.dp(this,32f)).coerceAtLeast(minY)
        }
    }
    private fun scheduleProject(){
        if(pendingFrame)return
        pendingFrame=true
        head?.postOnAnimation{pendingFrame=false;project()}
    }
    private fun project(){
        val view=head?:return;val layout=params?:return
        layout.x=targetX.coerceIn(minX,maxX);layout.y=targetY.coerceIn(minY,maxY)
        try{manager.updateViewLayout(view,layout)}catch(_:RuntimeException){stopSelf()}
    }
    private fun persistPosition(){
        workspace.bubbleX=if(maxX>minX)(targetX-minX).toFloat()/(maxX-minX)else 0f
        workspace.bubbleY=if(maxY>minY)(targetY-minY).toFloat()/(maxY-minY)else 0f
        workspace.checkpoint()
    }
    override fun onConfigurationChanged(config:Configuration){
        super.onConfigurationChanged(config);bounds()
        targetX=Policy.coordinate(workspace.bubbleX,minX,maxX);targetY=Policy.coordinate(workspace.bubbleY,minY,maxY);project()
    }
    override fun onDestroy(){
        workspace.unlisten(listener)
        head?.let{view->view.animate().cancel();try{manager.removeView(view)}catch(_:RuntimeException){ }}
        head=null;main.removeCallbacksAndMessages(null);super.onDestroy()
    }
    private inner class Head:View(this@BubbleService){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private val path=Path()
        private var total="1";private var unread=0;private var busy=false
        private var downX=0f;private var downY=0f;private var originX=0;private var originY=0
        private var dragging=false;private var longClicked=false
        private val slop=ViewConfiguration.get(context).scaledTouchSlop
        private val hold=Runnable{if(!dragging){longClicked=true;performLongClick()}}
        init{isClickable=true;isFocusable=true;contentDescription="Open Bubble workspace"}
        fun update(count:Int,newUnread:Int,generating:Boolean){
            val next=if(count>99)"99+" else count.toString()
            if(total==next&&unread==newUnread&&busy==generating)return
            total=next;unread=newUnread;busy=generating
            contentDescription="Open workspace, $count tabs, $newUnread unread${if(generating)", generating" else ""}. Long press for tabs."
            invalidate()
        }
        override fun onDraw(c:Canvas){
            val scale=width/68f;c.save();c.scale(scale,scale)
            p.style=Paint.Style.FILL;p.color=0xdd161e2c.toInt();c.drawCircle(34f,34f,30f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=if(busy)Ui.MINT else Ui.BLUE;c.drawCircle(34f,34f,29f,p)
            p.strokeWidth=2.2f;p.strokeJoin=Paint.Join.ROUND;p.strokeCap=Paint.Cap.ROUND
            c.drawRoundRect(19f,19f,49f,42f,8f,8f,p)
            path.reset();path.moveTo(26f,42f);path.lineTo(26f,49f);path.lineTo(34f,42f);c.drawPath(path,p)
            c.drawLine(27f,29f,41f,29f,p)
            p.style=Paint.Style.FILL;p.color=if(unread>0)Ui.MINT else Ui.BLUE;c.drawCircle(54f,14f,11f,p)
            p.color=Ui.BG;p.textSize=10f;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER;c.drawText(total,54f,17.5f,p)
            c.restore()
        }
        override fun onTouchEvent(e:MotionEvent):Boolean{
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    downX=e.rawX;downY=e.rawY;originX=targetX;originY=targetY;dragging=false;longClicked=false
                    main.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong());isPressed=true;return true
                }
                MotionEvent.ACTION_MOVE->{
                    if(longClicked)return true
                    if(!dragging&&(abs(e.rawX-downX)>slop||abs(e.rawY-downY)>slop)){dragging=true;main.removeCallbacks(hold);isPressed=false}
                    if(dragging){targetX=(originX+e.rawX-downX).toInt().coerceIn(minX,maxX);targetY=(originY+e.rawY-downY).toInt().coerceIn(minY,maxY);scheduleProject()}
                }
                MotionEvent.ACTION_UP->{main.removeCallbacks(hold);isPressed=false
                    if(dragging){project();persistPosition()}else if(!longClicked)performClick()
                }
                MotionEvent.ACTION_CANCEL->{main.removeCallbacks(hold);isPressed=false;if(dragging){project();persistPosition()}}
            }
            return true
        }
        override fun performClick():Boolean=super.performClick()
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button"}
    }
    companion object{
        const val READY="bubble.overlay.ready"
        private const val CHANNEL="floating-workspace-v2"
        private const val HIDE="bubble.hide.overlay"
    }
}
