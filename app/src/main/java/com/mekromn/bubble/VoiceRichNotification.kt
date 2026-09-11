package com.mekromn.bubble

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.min
import org.mozilla.geckoview.WebNotification

/** Builds Android-native conversation notifications from Google Voice's original WebNotification. */
internal object VoiceRichNotification {
    private val io = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "voice-avatar").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    data class Spec(
        val channel: String,
        val title: String,
        val text: String,
        val category: String,
        val ongoing: Boolean,
        val click: PendingIntent,
        val dismiss: PendingIntent,
        val copyNumber: PendingIntent?,
        val contact: VoiceContactInfo?,
        val kind: VoiceNoticeKind?,
        val onlyAlertOnce: Boolean = false
    )

    fun build(context: Context, spec: Spec, avatar: Bitmap? = null): Notification {
        val contactName = spec.contact?.displayName?.takeIf { it.isNotBlank() }
            ?: spec.contact?.phone?.takeIf { it.isNotBlank() }
            ?: spec.title
        val personIcon = avatar?.let(Icon::createWithBitmap) ?: initialIcon(contactName)
        val builder = Notification.Builder(context, spec.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title.take(512))
            .setContentIntent(spec.click)
            .setDeleteIntent(spec.dismiss)
            .setAutoCancel(!spec.ongoing)
            .setOnlyAlertOnce(spec.onlyAlertOnce)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(spec.category)

        if (spec.ongoing) builder.setOngoing(true)
        if (spec.text.isNotBlank()) builder.setContentText(spec.text.take(512))

        if (spec.contact != null && spec.kind != null) {
            val phone = spec.contact.phone
            builder.setSubText((phone ?: "Google Voice").take(256))
            builder.setLargeIcon(personIcon)
            spec.copyNumber?.let { builder.addAction(0, "Copy number", it) }

            if (Build.VERSION.SDK_INT >= 28) {
                val sender = Person.Builder()
                    .setName(contactName.take(256))
                    .setIcon(personIcon)
                    .apply { phone?.let { VoiceContactPolicy.telUri(it)?.let(::setUri) } }
                    .build()
                builder.addPerson(sender)
                if (spec.category == Notification.CATEGORY_MESSAGE && spec.text.isNotBlank()) {
                    val me = Person.Builder().setName("You").build()
                    val style = Notification.MessagingStyle(me)
                        .setGroupConversation(false)
                        .addMessage(spec.text.take(4096), System.currentTimeMillis(), sender)
                    builder.setStyle(style)
                } else if (spec.text.isNotBlank()) {
                    builder.setStyle(Notification.BigTextStyle().bigText(spec.text.take(4096)))
                }
            } else if (spec.text.isNotBlank()) {
                builder.setStyle(Notification.BigTextStyle().bigText(spec.text.take(4096)))
            }
        } else if (spec.text.isNotBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(spec.text.take(4096)))
        }
        return builder.build()
    }

    fun loadAvatar(web: WebNotification, callback: (Bitmap?) -> Unit) {
        val raw = web.imageUrl?.trim().orEmpty()
        if (!raw.startsWith("https://", ignoreCase = true)) { callback(null); return }
        io.execute {
            val bitmap = runCatching {
                val connection = (URL(raw).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 4_000
                    instanceFollowRedirects = true
                    useCaches = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val length = connection.contentLengthLong
                    if (length > 3_000_000L) return@runCatching null
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { connection.disconnect() }
            }.getOrNull()
            main.post { callback(bitmap) }
        }
    }

    private fun initialIcon(label: String): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(48, 49, 52)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 46f
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(initial.take(min(1, initial.length)), size / 2f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }
}
