package com.mekromn.bubble

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local loopback workload. It uses a normal http Gecko tab and the real
 * Bubble windows/controls, but removes Internet/server variability. Browser-side
 * telemetry is batched once per second; it never controls the input arm.
 */
internal object BenchmarkPageServer {
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "BubbleAB-http").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    fun ensureStarted(context: Context): String {
        if (started.compareAndSet(false, true)) {
            val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread({
                while (!s.isClosed) {
                    val socket = runCatching { s.accept() }.getOrNull() ?: break
                    workers.execute { serve(socket) }
                }
            }, "BubbleAB-listen").apply { isDaemon = true; start() }
        }
        val port = server?.localPort ?: throw IllegalStateException("Benchmark loopback server unavailable")
        return "http://127.0.0.1:$port/"
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine().orEmpty()
            if (request.isEmpty()) return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val parts = request.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')
            if (method == "POST" && path == "/telemetry") {
                val chars = CharArray(contentLength.coerceIn(0, 128_000))
                var at = 0
                while (at < chars.size) {
                    val n = reader.read(chars, at, chars.size - at)
                    if (n <= 0) break
                    at += n
                }
                if (at > 0) InputBenchmark.addPageTelemetry(String(chars, 0, at))
                respond(s, "204 No Content", "text/plain", "")
            } else {
                respond(s, "200 OK", "text/html; charset=utf-8", html())
            }
        }
    }

    private fun respond(socket: Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n"
        socket.getOutputStream().use { out -> out.write(head.toByteArray(StandardCharsets.US_ASCII)); out.write(bytes); out.flush() }
    }

    private fun html(): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes">
<title>Bubble Production A/B Workload</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}html,body{margin:0;background:#090a0d;color:#eceff4;font:15px system-ui,sans-serif;overscroll-behavior:auto}
header{position:sticky;top:0;z-index:5;padding:12px 16px;background:rgba(9,10,13,.94);backdrop-filter:blur(14px);border-bottom:1px solid #333}
#hero{height:92px;margin:14px;border-radius:24px;background:linear-gradient(120deg,#20283b,#232323);display:flex;align-items:center;padding:18px;overflow:hidden}
.orb{width:54px;height:54px;border-radius:50%;background:linear-gradient(135deg,#7aa2ff,#b080ff);animation:orbit 1.7s ease-in-out infinite alternate;will-change:transform}
@keyframes orbit{from{transform:translateX(0) rotate(0)}to{transform:translateX(calc(100vw - 150px)) rotate(270deg)}}
#nested{height:240px;overflow:auto;margin:14px;padding:10px;border:1px solid #46516a;border-radius:18px;background:#11151d;touch-action:pan-y}
.card{margin:10px 14px;padding:18px;border-radius:18px;background:#151820;border:1px solid #282d3a;contain:content}
.card b{font-size:17px}.code{white-space:pre-wrap;font-family:monospace;background:#0b0d12;padding:10px;border-radius:12px;margin-top:10px}
#stream{min-height:120px}.pulse{display:inline-block;width:8px;height:8px;border-radius:50%;background:#7aa2ff;animation:pulse .8s infinite alternate}@keyframes pulse{to{opacity:.25}}
#tap{position:sticky;bottom:10px;margin:14px;padding:18px;text-align:center;border-radius:16px;background:#1f355f;border:1px solid #6d9fff;touch-action:manipulation}
.small{opacity:.75;font-size:12px}
</style></head><body>
<header><b>Bubble 139 ↔ 140 production A/B</b><div class="small">Use real drags/flings, nested scrolling, pinch, taps and reversals. The app switches methods between paired blocks automatically.</div></header>
<div id="hero"><div class="orb"></div></div>
<div id="nested"><b>Nested scroller</b><div id="nestedRows"></div></div>
<div id="stream" class="card"><b>Streaming/layout churn <span class="pulse"></span></b><div id="streamText"></div></div>
<div id="cards"></div>
<div id="tap">Tap / drag target — DOM timing telemetry is local only</div>
<script>
'use strict';
const cards=document.getElementById('cards'), nested=document.getElementById('nestedRows'), stream=document.getElementById('streamText');
for(let i=0;i<180;i++){
 const d=document.createElement('section'); d.className='card';
 d.innerHTML='<b>Conversation block '+i+'</b><p>This is deterministic dense content with wrapping text, inline emphasis, links and list-like structure. Drag and fling naturally as you would in a long AI conversation.</p><div class="code">frame_'+i+' = layout + paint + compositor;\nscroll target remains real DOM content.</div>';
 cards.appendChild(d);
 if(i<36){const n=document.createElement('div');n.className='card';n.textContent='Nested message '+i+' — independent overflow target';nested.appendChild(n)}
}
let streamN=0; setInterval(()=>{const s=document.createElement('span');s.textContent=' token_'+(++streamN);stream.appendChild(s);while(stream.childNodes.length>180)stream.removeChild(stream.firstChild)},180);
let delivery=[],raf=[],eventDuration=[],moves=0,coalesced=0,scrolls=0,longTasks=0,lastRaf=0;
function onPointer(e){
 const d=performance.now()-e.timeStamp;if(Number.isFinite(d)&&d>=0&&d<1000)delivery.push(d);
 if(e.type==='pointermove'){moves++;if(e.getCoalescedEvents){try{coalesced+=e.getCoalescedEvents().length}catch(_){}}}
}
for(const t of ['pointerdown','pointermove','pointerup','pointercancel']) addEventListener(t,onPointer,{capture:true,passive:true});
addEventListener('scroll',()=>scrolls++,{capture:true,passive:true});
function frame(t){if(lastRaf){const d=t-lastRaf;if(d>0&&d<250)raf.push(d)}lastRaf=t;requestAnimationFrame(frame)}requestAnimationFrame(frame);
try{
 if(PerformanceObserver.supportedEntryTypes&&PerformanceObserver.supportedEntryTypes.includes('event')) new PerformanceObserver(list=>{for(const e of list.getEntries())if(e.duration>=0&&e.duration<1000)eventDuration.push(e.duration)}).observe({type:'event',buffered:true,durationThreshold:0});
 if(PerformanceObserver.supportedEntryTypes&&PerformanceObserver.supportedEntryTypes.includes('longtask')) new PerformanceObserver(list=>longTasks+=list.getEntries().length).observe({type:'longtask',buffered:true});
}catch(_){ }
setInterval(()=>{
 const body=JSON.stringify({delivery:delivery.splice(0),raf:raf.splice(0),eventDuration:eventDuration.splice(0),moves,coalesced,scrolls,longTasks});
 moves=0;coalesced=0;scrolls=0;longTasks=0;
 fetch('/telemetry',{method:'POST',headers:{'Content-Type':'application/json'},body,cache:'no-store'}).catch(()=>{});
},1000);
</script></body></html>"""
}
