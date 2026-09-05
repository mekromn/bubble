package com.mekromn.bubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Generic completion notices: conversation text, titles, cookies and prompts are never copied. */
internal object Replies {
    private const val CHANNEL="chatgpt-replies-v2"
    fun open(context:Context,id:String?,tray:Boolean=false):PendingIntent {
        val intent=Intent(context,BrowserActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data=Uri.parse("bubble://workspace/${id ?: "selected"}/${if(tray)"tabs" else "page"}")
            if(id!=null)putExtra(BrowserActivity.EXTRA_TAB,id)
            putExtra(BrowserActivity.EXTRA_TRAY,tray)
        }
        return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun finished(context:Context,id:String) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"ChatGPT replies",NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="One audible notification when a background ChatGPT reply is detected as complete"
        })
        val note=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your ChatGPT reply is ready").setContentText("Tap to return to this conversation")
            .setContentIntent(open(context,id)).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
        try{manager.notify(id,2,note)}catch(_:SecurityException){ }
    }
    fun clear(context:Context,id:String){context.getSystemService(NotificationManager::class.java).cancel(id,2)}
}
