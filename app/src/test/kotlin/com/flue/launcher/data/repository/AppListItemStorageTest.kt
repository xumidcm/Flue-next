package com.flue.launcher.data.repository

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AppListItemStorageTest {
    private lateinit var context: Context
    private lateinit var storage: AppListItemStorage

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_list_items", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        storage = AppListItemStorage(context)
    }

    @Test
    fun `created folder is durably restored before success is returned`() {
        val created = storage.createFolder(
            sourceKey = "pkg.source/SourceActivity",
            targetKey = "pkg.target/TargetActivity",
            targetName = "目标",
        )

        assertNotNull(created)
        assertEquals(created, AppListItemStorage(context).loadFolders().single())
    }

    @Test
    fun `application dropped on application creates one durable folder`() {
        val created = storage.createFolder("app.a/A", "app.b/B", "B")!!

        assertEquals(listOf("app.b/B", "app.a/A"), created.itemKeys)
        assertEquals(created, AppListItemStorage(context).loadFolders().single())
    }

    @Test
    fun `application dropped on folder joins the durable target folder`() {
        val target = storage.createFolder("app.b/B", "app.c/C", "目标")!!

        val merged = storage.createFolder("app.a/A", target.componentKey, "忽略")!!

        assertEquals(target.componentKey, merged.componentKey)
        assertEquals(listOf("app.c/C", "app.b/B", "app.a/A"), merged.itemKeys)
        assertEquals(merged, AppListItemStorage(context).loadFolders().single())
    }

    @Test
    fun `folder dropped on application keeps the source folder identity`() {
        val source = storage.createFolder("app.a/A", "app.b/B", "来源")!!

        val merged = storage.createFolder(source.componentKey, "app.c/C", "C")!!

        assertEquals(source.componentKey, merged.componentKey)
        assertEquals(listOf("app.b/B", "app.a/A", "app.c/C"), merged.itemKeys)
        assertEquals(merged, AppListItemStorage(context).loadFolders().single())
    }

    @Test
    fun `folder dropped on folder merges children into the durable target identity`() {
        val source = storage.createFolder("app.a/A", "app.b/B", "来源")!!
        val target = storage.createFolder("app.c/C", "app.d/D", "目标")!!

        val merged = storage.createFolder(source.componentKey, target.componentKey, "忽略")!!

        assertEquals(target.componentKey, merged.componentKey)
        assertEquals(
            listOf("app.d/D", "app.c/C", "app.b/B", "app.a/A"),
            merged.itemKeys,
        )
        assertEquals(merged, AppListItemStorage(context).loadFolders().single())
    }

    @Test
    fun `adding item to an existing folder transfers it from its previous folder atomically`() {
        val sourceFolder = storage.createFolder("app.a/A", "app.b/B", "来源")!!
        val targetFolder = storage.createFolder("app.c/C", "app.d/D", "目标")!!

        val result = storage.addItemsToFolder(targetFolder.componentKey, listOf("app.a/A"))

        assertTrue(result.succeeded)
        assertEquals(listOf("app.d/D", "app.c/C", "app.a/A"), result.itemKeys)
        val restored = AppListItemStorage(context).loadFolders()
        assertFalse(restored.any { it.componentKey == sourceFolder.componentKey })
        assertEquals(
            listOf("app.d/D", "app.c/C", "app.a/A"),
            restored.single { it.componentKey == targetFolder.componentKey }.itemKeys,
        )
    }

    @Test
    fun `adding unknown item to missing folder reports failure without mutating storage`() {
        storage.createFolder("app.a/A", "app.b/B", "现有")
        val before = storage.loadFolders()

        val result = storage.addItemsToFolder("folder:missing", listOf("app.c/C"))

        assertFalse(result.succeeded)
        assertEquals(before, storage.loadFolders())
    }

    @Test
    fun `moving an absent child reports failure without dissolving its folder`() {
        val folder = storage.createFolder("app.a/A", "app.b/B", "现有")!!
        val before = storage.loadFolders()

        val result = storage.moveItemOutOfFolder(folder.componentKey, "app.missing/Missing")

        assertFalse(result.succeeded)
        assertEquals(before, storage.loadFolders())
    }

    @Test
    fun `concurrent storage instances do not lose separate folder mutations`() {
        val first = AppListItemStorage(context)
        val second = AppListItemStorage(context)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failures = mutableListOf<Throwable>()

        thread(name = "folder-writer-one") {
            try {
                start.await()
                first.createFolder("app.a/A", "app.b/B", "一")
            } catch (error: Throwable) {
                synchronized(failures) { failures += error }
            } finally {
                finished.countDown()
            }
        }
        thread(name = "folder-writer-two") {
            try {
                start.await()
                second.createFolder("app.c/C", "app.d/D", "二")
            } catch (error: Throwable) {
                synchronized(failures) { failures += error }
            } finally {
                finished.countDown()
            }
        }

        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
        assertEquals(2, AppListItemStorage(context).loadFolders().size)
    }

    @Test
    fun `creating a folder with an existing folder as both endpoints is rejected`() {
        val folder = storage.createFolder("app.a/A", "app.b/B", "现有")!!

        assertNull(storage.createFolder(folder.componentKey, folder.componentKey, "现有"))
        assertEquals(1, storage.loadFolders().size)
    }

}
