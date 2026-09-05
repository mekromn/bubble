package com.mekromn.bubble

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real same-origin storage and popup tests. Synthetic identities only; no account credentials. */
@RunWith(AndroidJUnit4::class)
class ProfileIsolationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun simultaneousAccountsRemainIsolatedAcrossTabsPopupsReopenAndRecreation() {
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    var cookies = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Cookie:", true)) cookies = line.substringAfter(':').trim()
                    }
                    val set = Regex("/set\\?account=([A-Z]+)").find(request)?.groupValues?.get(1).orEmpty()
                    val http = Regex("(?:^|;\\s*)sid=([A-Z]+)(?:;|$)").find(cookies)?.groupValues?.get(1) ?: "none"
                    val body = page(set, http).toByteArray()
                    val cookie = if (set.isEmpty()) "" else "Set-Cookie: sid=$set; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600\r\n"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nCache-Control: no-store\r\n$cookie" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(body)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val origin = "http://127.0.0.1:${server.localPort}"
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                waitFor(scenario) { it.workspace.ready }
                var work = ""; var personal = ""; var legacyId = ""; var workId = ""
                scenario.onActivity {
                    val ws = it.workspace
                    val suffix = UUID.randomUUID().toString().take(8)
                    work = ws.createProfile("Work $suffix").id
                    personal = ws.createProfile("Personal $suffix").id
                    legacyId = ws.create("$origin/set?account=LEGACY", ProfilePolicy.DEFAULT_ID).id
                }
                waitFor(scenario) { it.painted && it.pageTitle.startsWith("PROFILE|LEGACY|") }
                scenario.onActivity { it.workspace.create("$origin/read", work) }
                waitFor(scenario) { it.pageTitle == emptyReport() }
                scenario.onActivity { workId = it.workspace.create("$origin/set?account=WORK", work).id }
                waitFor(scenario) { it.pageTitle.startsWith("PROFILE|WORK|WORK|WORK|WORK|") }
                scenario.onActivity { it.workspace.create("$origin/read", personal) }
                waitFor(scenario) { it.pageTitle == emptyReport() }
                scenario.onActivity { it.workspace.create("$origin/set?account=PERSONAL", personal) }
                waitFor(scenario) { it.pageTitle.startsWith("PROFILE|PERSONAL|PERSONAL|PERSONAL|PERSONAL|") }
                fun read(profile: String, identity: String) {
                    scenario.onActivity { it.workspace.create("$origin/read", profile) }
                    waitFor(scenario) { it.painted && it.pageTitle == report(identity) }
                    scenario.onActivity { assertEquals(profile, it.selectedSession!!.settings.contextId) }
                }
                read(work, "WORK")
                read(personal, "PERSONAL")
                read(ProfilePolicy.DEFAULT_ID, "LEGACY")
                read(work, "WORK")
                var opener = ""; var before = 0
                scenario.onActivity { opener = it.workspace.selectedId; before = it.workspace.tabs.size }
                // A trusted touch opens a target=_blank link; the login popup inherits the opener.
                tapPopup(scenario)
                waitFor(scenario) { it.workspace.tabs.size == before + 1 && it.workspace.selectedId != opener && it.pageTitle == report("WORK") }
                scenario.onActivity {
                    assertEquals(work, it.workspace.selected!!.profileId)
                    assertEquals(work, it.selectedSession!!.settings.contextId)
                }
                var duplicate = ""
                scenario.onActivity { duplicate = it.workspace.duplicate(workId)!!.id }
                waitFor(scenario) { it.painted && it.pageTitle.startsWith("PROFILE|WORK|") }
                scenario.onActivity {
                    assertEquals(work, it.workspace.selected!!.profileId)
                    it.workspace.close(duplicate)
                    it.workspace.reopen(duplicate)
                    assertEquals(work, it.workspace.selected!!.profileId)
                    assertEquals(work, it.selectedSession!!.settings.contextId)
                    it.workspace.navigate("$origin/read")
                }
                waitFor(scenario) { it.pageTitle == report("WORK") }
                scenario.recreate()
                waitFor(scenario) { it.painted && it.pageTitle == report("WORK") }
                scenario.onActivity { assertEquals(work, it.selectedSession!!.settings.contextId) }
                // Opening a URL in another profile creates a new tab, not a cross-profile state restore.
                scenario.onActivity {
                    val old = it.workspace.selectedId
                    val other = it.workspace.openInProfile(old, personal)!!
                    assertNotEquals(old, other.id)
                    assertNull(other.savedState)
                    assertTrue(it.workspace.tabs.any { tab -> tab.id == legacyId })
                }
                waitFor(scenario) { it.pageTitle == report("PERSONAL") }
                scenario.onActivity { ProfileMenus.show(it.geckoView, it.workspace) }
                Thread.sleep(300)
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    File(evidence(), "profiles-menu.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                instrumentation.runOnMainSync { QuickPanel.dismiss() }
                File(evidence(), "profile-isolation.txt").writeText(
                    "PASS: three simultaneous synthetic identities on the exact same origin.\n" +
                    "Default normal context retained LEGACY; named contexts retained WORK and PERSONAL.\n" +
                    "Checked JavaScript cookie, HTTP-only cookie received by server, localStorage, IndexedDB and CacheStorage.\n" +
                    "New profile was empty; same-profile tabs shared data; target=_blank, duplicate, reopen and Activity recreation retained profile.\n" +
                    "Open in other profile created a new tab without copying session state.\n" +
                    "This is not a VPN, process security boundary or authenticated ChatGPT test.\n")
            }
        } finally { server.close(); worker.join(1000) }
    }
    @Test fun legacyStoreAndProfileLabelsRoundTripWithoutChangingContainerIds() {
        val folder = File(context.cacheDir, "profile-store-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = folder }
        val file = File(folder, "workspace-v2.json")
        val tabId = UUID.randomUUID().toString()
        file.writeText(JSONObject().put("version", 1).put("selected", tabId).put("tabs", JSONArray().put(
            JSONObject().put("id", tabId).put("url", Policy.HOME).put("title", "Legacy"))).toString())
        fun load(store: WorkspaceStore): StoredWorkspace {
            val done = CountDownLatch(1); var result: StoredWorkspace? = null; var error: String? = null
            store.load { data, problem -> result = data; error = problem; done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(error); return requireNotNull(result)
        }
        try {
            val store = WorkspaceStore(isolated)
            val legacy = load(store)
            assertEquals("normal", legacy.tabs.single().profileId)
            assertEquals(ProfilePolicy.defaults(), legacy.profiles)
            val work = BrowserProfile(ProfilePolicy.newId(), "Work")
            val updated = legacy.copy(tabs = listOf(legacy.tabs.single(), StoredTab(UUID.randomUUID().toString(), Policy.HOME, "Other", profileId = work.id)),
                profiles = ProfilePolicy.defaults() + work)
            val done = CountDownLatch(1); var success = false
            store.save(updated) { success = it; done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(success)
            assertEquals(updated, load(WorkspaceStore(isolated)))
            // Lost metadata must retain the existing context, not merge it with Default.
            val json = JSONObject(file.readText()).apply { remove("profiles") }
            file.writeText(json.toString())
            val recovered = load(WorkspaceStore(isolated))
            assertTrue(recovered.profiles.any { it.id == work.id })
            assertEquals(work.id, recovered.tabs.last().profileId)
        } finally { folder.deleteRecursively() }
    }
    private fun emptyReport() = "PROFILE|none|none|none|none|none"
    private fun report(id: String) = "PROFILE|$id|$id|$id|$id|$id"
    private fun page(set: String, http: String) = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Profile loading</title>
        <style>body{margin:0;background:#121212;color:white;font:18px sans-serif}h1{font-size:22px;margin:16px}a{display:block;position:absolute;left:16px;top:70px;padding:12px;color:white;background:#444}p{margin:140px 16px}</style>
        <h1>Profile isolation test</h1><a href="/read?popup=1" target="_blank">Open test popup</a><p id="result">Checking storage</p>
        <script>(async()=>{try{
          const set='$set';
          if(set){document.cookie='visible='+set+';Path=/;SameSite=Lax;Max-Age=3600';localStorage.setItem('identity',set);}
          const db=await new Promise((ok,no)=>{const r=indexedDB.open('bubble-profile-fixture',1);r.onupgradeneeded=()=>r.result.createObjectStore('values');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error);});
          if(set)await new Promise((ok,no)=>{const tx=db.transaction('values','readwrite');tx.objectStore('values').put(set,'identity');tx.oncomplete=ok;tx.onerror=()=>no(tx.error);});
          const idb=await new Promise((ok,no)=>{const r=db.transaction('values').objectStore('values').get('identity');r.onsuccess=()=>ok(r.result||'none');r.onerror=()=>no(r.error);});db.close();
          const cache=await caches.open('bubble-profile-fixture');
          if(set)await cache.put('/identity-cache',new Response(set));
          const cached=await cache.match('/identity-cache');const cv=cached?await cached.text():'none';
          const cookie=document.cookie.split(';').map(x=>x.trim()).find(x=>x.startsWith('visible='));
          const values=[cookie?cookie.slice(8):'none',localStorage.getItem('identity')||'none',idb,cv,'$http'];
          document.querySelector('#result').textContent=values.join(' / ');document.title='PROFILE|'+values.join('|');
        }catch(e){document.title='PROFILE-ERROR-'+e.name;}})();</script>""".trimIndent()
    private fun tapPopup(scenario: ActivityScenario<BrowserActivity>) {
        var x = 0f; var y = 0f
        scenario.onActivity {
            val location = IntArray(2); it.geckoView.getLocationOnScreen(location)
            val density = it.resources.displayMetrics.density
            x = location[0] + 95 * density; y = location[1] + 92 * density
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 90, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true)); assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true)) }
        finally { down.recycle(); up.recycle() }
    }
    private fun waitFor(scenario: ActivityScenario<BrowserActivity>, check: (BrowserActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        var ready = false; var last = ""
        while (!ready && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { ready = check(it); last = it.pageTitle }
            if (!ready) Thread.sleep(100)
        }
        assertTrue("Profile condition timed out; title=$last", ready)
    }
    private fun evidence() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
}
