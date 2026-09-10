package com.mekromn.bubble

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent physical-device crash diagnostics.
 *
 * IMPORTANT: diagnostics are intentionally parked for performance testing right now. Flip ENABLED
 * back to true for a forensic build. When disabled, install/event/error/memory/session-state helpers
 * return before allocating a writer thread, opening MediaStore, collecting PSS, scanning Workspace
 * tabs, or formatting log lines. The complete Downloads + private-shadow + uncaught-exception +
 * ApplicationExitInfo implementation remains here for the next debugging cycle.
 *
 * Never log page text, cookies, request headers, auth/session tokens, form contents or URL queries.
 */
internal object DiagnosticLog {
    const val ENABLED = false

    private const val TAG = "BubbleDiag"
    private const val PREFS = "bubble_diagnostics"
    private const val LAST_EXIT_TS = "last_exit_timestamp"
    private const val MAX_TRACE_BYTES = 512 * 1024

    private val sequence by lazy { AtomicLong(0) }
    private val queue by lazy { ConcurrentLinkedQueue<String>() }
    private val drainScheduled by lazy { AtomicBoolean(false) }
    private val handlingCrash by lazy { AtomicBoolean(false) }
    private val writer by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BubbleDiagnostics").apply { isDaemon = true }
        }
    }
    private val writeLock = Any()

    @Volatile private var app: Context? = null
    @Volatile private var publicPfd: ParcelFileDescriptor? = null
    @Volatile private var publicOut: FileOutputStream? = null
    @Volatile private var privateOut: FileOutputStream? = null
    @Volatile private var installed = false
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val processStartElapsed by lazy { SystemClock.elapsedRealtime() }
    private val processStamp by lazy { Instant.now().toString().replace(':', '-').replace('.', '-') }

    val fileHint: String
        get() = if (!ENABLED) "diagnostics disabled" else
            "Downloads/Bubble Logs/Bubble-diagnostics-$processStamp-p${Process.myPid()}.log"

    fun install(context: Context) {
        if (!ENABLED || installed) return
        synchronized(writeLock) {
            if (installed) return
            app = context.applicationContext
            openPrivateShadowLocked()
            openPublicDownloadsLocked()
            installed = true
        }

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordUncaught(thread, error)
            val prior = previousHandler
            if (prior != null) prior.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        event("APP", "diagnostics installed file=$fileHint sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()} uid=${Process.myUid()} abi=${Build.SUPPORTED_ABIS.joinToString()}")
        event("APP", "device=${Build.MANUFACTURER}/${Build.MODEL} product=${Build.PRODUCT} build=${Build.FINGERPRINT}")
        snapshotMemory("startup")
        writer.execute { recordHistoricalExits() }
    }

    fun event(area: String, message: String) {
        if (!ENABLED || !installed) return
        Log.d(TAG, "$area $message")
        queue.add(line(area, message))
        scheduleDrain()
    }

    fun error(area: String, message: String, error: Throwable) {
        if (!ENABLED || !installed) return
        Log.e(TAG, "$area $message", error)
        queue.add(line(area, "$message :: ${throwableSummary(error)}"))
        queue.add(stackLine(area, error))
        scheduleDrain()
    }

    fun snapshotMemory(reason: String) {
        if (!ENABLED || !installed) return
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val am = app?.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        event(
            "MEM",
            "reason=$reason javaUsed=${used / 1024}KiB javaTotal=${runtime.totalMemory() / 1024}KiB " +
                "javaMax=${runtime.maxMemory() / 1024}KiB pss=${Debug.getPss()}KiB " +
                "avail=${mi.availMem / 1024}KiB lowMemory=${mi.lowMemory} threshold=${mi.threshold / 1024}KiB"
        )
    }

    fun sessionLabel(session: org.mozilla.geckoview.GeckoSession?): String {
        if (!ENABLED || !installed) return "diag=off"
        if (session == null) return "session=null"
        val identity = Integer.toHexString(System.identityHashCode(session))
        val workspace = runCatching { Workspace.peek() }.getOrNull()
        val tab = runCatching { workspace?.tabs?.firstOrNull { it.session === session } }.getOrNull()
        return if (tab == null) {
            "session=$identity isOpen=${runCatching { session.isOpen }.getOrDefault(false)} tab=?"
        } else {
            "session=$identity isOpen=${runCatching { session.isOpen }.getOrDefault(false)} tab=${tab.id.take(8)} " +
                "host=${sanitizedHost(tab.url)} selected=${tab.id == workspace?.selectedId} loading=${tab.loading} " +
                "progress=${tab.progress} painted=${tab.painted} suspended=${tab.suspended}"
        }
    }

    fun selectedState(): String {
        if (!ENABLED || !installed) return "diag=off"
        val workspace = Workspace.peek() ?: return "workspace=null"
        val tab = workspace.selected ?: return "selected=null tabs=${workspace.tabs.size}"
        return "selected=${tab.id.take(8)} host=${sanitizedHost(tab.url)} tabs=${workspace.tabs.size} " +
            "loading=${tab.loading} progress=${tab.progress} painted=${tab.painted} " +
            "session=${Integer.toHexString(System.identityHashCode(tab.session))} floating=${workspace.floatingVisible} " +
            "visible=${workspace.visible} covered=${workspace.covered}"
    }

    private fun sanitizedHost(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        if (host.isNotBlank()) "$scheme://$host" else scheme.ifBlank { "unknown" }
    }.getOrDefault("unknown")

    private fun line(area: String, message: String): String {
        val seq = sequence.incrementAndGet()
        val elapsed = SystemClock.elapsedRealtime() - processStartElapsed
        val thread = Thread.currentThread()
        val main = Looper.myLooper() === Looper.getMainLooper()
        return "%08d %s +%dms pid=%d tid=%d thread=%s main=%s [%s] %s\n".format(
            Locale.US,
            seq,
            Instant.now().toString(),
            elapsed,
            Process.myPid(),
            Process.myTid(),
            thread.name.replace('\n', '_'),
            main,
            area,
            message.replace('\n', ' ')
        )
    }

    private fun stackLine(area: String, error: Throwable): String {
        val text = buildString {
            append(error.javaClass.name).append(": ").append(error.message.orEmpty()).append("\\n")
            error.stackTrace.forEach { append("  at ").append(it).append("\\n") }
            var cause = error.cause
            var depth = 0
            while (cause != null && cause !== error && depth++ < 8) {
                append("Caused by: ").append(cause.javaClass.name).append(": ").append(cause.message.orEmpty()).append("\\n")
                cause.stackTrace.forEach { append("  at ").append(it).append("\\n") }
                cause = cause.cause
            }
        }
        return line(area, "STACK ${text.take(64 * 1024)}")
    }

    private fun throwableSummary(error: Throwable): String =
        "${error.javaClass.name}: ${error.message.orEmpty()}"

    private fun scheduleDrain() {
        if (!ENABLED || !installed) return
        if (!drainScheduled.compareAndSet(false, true)) return
        writer.execute {
            try {
                drainQueueSync(force = false)
            } finally {
                drainScheduled.set(false)
                if (queue.isNotEmpty()) scheduleDrain()
            }
        }
    }

    private fun drainQueueSync(force: Boolean) {
        if (!ENABLED || !installed) return
        synchronized(writeLock) {
            var wrote = false
            while (true) {
                val next = queue.poll() ?: break
                val bytes = next.toByteArray(StandardCharsets.UTF_8)
                runCatching { privateOut?.write(bytes) }
                runCatching { publicOut?.write(bytes) }
                wrote = true
            }
            if (wrote || force) {
                runCatching { privateOut?.flush() }
                runCatching { publicOut?.flush() }
                if (force) {
                    runCatching { privateOut?.fd?.sync() }
                    runCatching { publicOut?.fd?.sync() }
                }
            }
        }
    }

    private fun openPrivateShadowLocked() {
        val context = app ?: return
        runCatching {
            val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
            privateOut = FileOutputStream(File(dir, "Bubble-diagnostics-$processStamp-p${Process.myPid()}.log"), true)
        }.onFailure { Log.e(TAG, "Could not open private diagnostic shadow", it) }
    }

    private fun openPublicDownloadsLocked() {
        val context = app ?: return
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Bubble-diagnostics-$processStamp-p${Process.myPid()}.log")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Bubble Logs")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            val pfd = context.contentResolver.openFileDescriptor(uri, "wa")
                ?: error("MediaStore openFileDescriptor returned null")
            publicPfd = pfd
            publicOut = FileOutputStream(pfd.fileDescriptor)
        }.onFailure { Log.e(TAG, "Could not open Downloads diagnostic log", it) }
    }

    private fun recordUncaught(thread: Thread, error: Throwable) {
        if (!ENABLED || !installed || !handlingCrash.compareAndSet(false, true)) return
        try {
            queue.add(line("FATAL", "UNCAUGHT thread=${thread.name} state=${thread.state} ${throwableSummary(error)} ${selectedState()}"))
            queue.add(stackLine("FATAL", error))
            queue.add(line("FATAL", threadDump()))
            drainQueueSync(force = true)
        } catch (secondary: Throwable) {
            Log.e(TAG, "Crash logger itself failed", secondary)
        }
    }

    private fun threadDump(): String = runCatching {
        val stacks = Thread.getAllStackTraces()
        buildString {
            append("THREAD_DUMP count=").append(stacks.size).append("\\n")
            stacks.entries.sortedBy { it.key.name }.take(64).forEach { (thread, stack) ->
                append("--- ").append(thread.name).append(" state=").append(thread.state)
                    .append(" daemon=").append(thread.isDaemon).append("\\n")
                stack.take(80).forEach { append("  at ").append(it).append("\\n") }
            }
        }.take(256 * 1024)
    }.getOrElse { "THREAD_DUMP_FAILED ${throwableSummary(it)}" }

    @TargetApi(30)
    private fun recordHistoricalExits() {
        if (!ENABLED || !installed || Build.VERSION.SDK_INT < 30) return
        val context = app ?: return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(LAST_EXIT_TS, 0L)
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            var newest = lastSeen
            exits.forEach { info ->
                newest = maxOf(newest, info.timestamp)
                event(
                    "EXIT",
                    "timestamp=${Instant.ofEpochMilli(info.timestamp)} process=${info.processName} " +
                        "reason=${exitReason(info.reason)}(${info.reason}) status=${info.status} importance=${info.importance} " +
                        "pss=${info.pss} rss=${info.rss} description=${info.description.orEmpty().replace('\n', ' ').take(512)}"
                )
                val trace = readExitTrace(info)
                if (trace.isNotBlank()) event("EXIT_TRACE", trace)
            }
            if (newest > lastSeen) prefs.edit().putLong(LAST_EXIT_TS, newest).apply()
            if (exits.isEmpty()) event("EXIT", "no unseen historical process exits")
        }.onFailure { error("EXIT", "failed to read historical exits", it) }
    }

    @TargetApi(30)
    private fun readExitTrace(info: ApplicationExitInfo): String = runCatching {
        info.traceInputStream?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = MAX_TRACE_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            String(out.toByteArray(), StandardCharsets.UTF_8).replace('\u0000', ' ').take(MAX_TRACE_BYTES)
        }.orEmpty()
    }.getOrElse { "trace-read-failed ${throwableSummary(it)}" }

    @TargetApi(30)
    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }
}
