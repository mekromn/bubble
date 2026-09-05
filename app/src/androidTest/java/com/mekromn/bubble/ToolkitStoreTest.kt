package com.mekromn.bubble

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolkitStoreTest {
    @Test fun legacyWorkspaceAndNewLocalToolsRoundTripWithoutResettingTabs() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(original.cacheDir, "toolkit-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(original) { override fun getFilesDir(): File = folder }
        val file = File(folder, "workspace-v2.json")
        val id = UUID.randomUUID().toString()
        file.writeText(JSONObject().put("version", 1).put("selected", id).put("tabs", JSONArray().put(
            JSONObject().put("id", id).put("url", "https://chatgpt.com/c/synthetic").put("title", "Original"))).toString())
        val store = WorkspaceStore(context)
        fun read(): StoredWorkspace {
            val latch = CountDownLatch(1); var saved: StoredWorkspace? = null; var error: String? = null
            store.load { result, problem -> saved = result; error = problem; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS)); assertNull(error); return requireNotNull(saved)
        }
        try {
            val legacy = read()
            assertEquals(id, legacy.selected); assertEquals(12, legacy.prompts.size); assertTrue(legacy.closedTabs.isEmpty())
            val changed = legacy.copy(tabs = listOf(legacy.tabs.single().copy(localName = "Pinned local name", pinned = true, note = "Private test note", muted = true)),
                prompts = listOf(PromptSnippet("custom", "My prompt", "Synthetic local prompt")),
                closedTabs = listOf(StoredTab(UUID.randomUUID().toString(), "https://example.org/", "Closed", note = "Closed note")))
            val latch = CountDownLatch(1); var success = false
            store.save(changed) { success = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS)); assertTrue(success)
            val loaded = read(); assertEquals(changed, loaded)
            assertEquals(1, JSONObject(file.readText()).getInt("version"))
            val empty = CountDownLatch(1)
            store.save(changed.copy(prompts = emptyList())) { empty.countDown() }
            assertTrue(empty.await(10, TimeUnit.SECONDS)); assertTrue(read().prompts.isEmpty())
        } finally { folder.deleteRecursively() }
    }
}
