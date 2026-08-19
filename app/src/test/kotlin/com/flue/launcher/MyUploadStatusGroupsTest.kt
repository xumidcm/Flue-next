package com.flue.launcher

import com.flue.launcher.watchface.store.MyWatchFaceEntry
import com.flue.launcher.watchface.store.WatchStoreCategory
import com.flue.launcher.watchface.store.WatchStoreReviewStatus
import com.flue.launcher.watchface.store.WatchStoreScreenType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 我的上传按审核状态分组（B3）。
 *
 * 这条分组是老师那条反馈的正面回答：上传永远返回 201，被拦只体现在 `status` 与
 * `rejection_reason` 上。平铺的话一块被驳回的表盘会混在几十块已上架的中间，用户永远
 * 发现不了。
 */
class MyUploadStatusGroupsTest {

    private fun entry(id: String, status: WatchStoreReviewStatus): MyWatchFaceEntry =
        MyWatchFaceEntry(
            id = id,
            name = "face-$id",
            description = "",
            screenType = WatchStoreScreenType.ROUND,
            category = WatchStoreCategory(id = "c", name = "分类", slug = "c"),
            previewUrl = "/p",
            downloadCount = 0,
            createdAt = "2026-08-17",
            status = status,
        )

    @Test
    fun `anything not published goes to the attention group`() {
        val entries = listOf(
            entry("1", WatchStoreReviewStatus.PUBLISHED),
            entry("2", WatchStoreReviewStatus.REJECTED),
            entry("3", WatchStoreReviewStatus.PUBLISHED),
            entry("4", WatchStoreReviewStatus.PENDING),
            entry("5", WatchStoreReviewStatus.DELISTED),
            // 上游加了新状态、我们还不认识 —— 也要让用户看见，不能悄悄混进「已上架」。
            entry("6", WatchStoreReviewStatus.UNKNOWN),
        )

        val (attention, published) = myUploadStatusGroups(entries)

        assertEquals(listOf("2", "4", "5", "6"), attention.map { it.id })
        assertEquals(listOf("1", "3"), published.map { it.id })
    }

    @Test
    fun `order inside each group follows the server`() {
        // 上游按创建时间倒序给，组内不再自作主张排序。
        val entries = listOf(
            entry("newest", WatchStoreReviewStatus.PENDING),
            entry("middle", WatchStoreReviewStatus.PENDING),
            entry("oldest", WatchStoreReviewStatus.PENDING),
        )
        assertEquals(
            listOf("newest", "middle", "oldest"),
            myUploadStatusGroups(entries).first.map { it.id },
        )
    }

    @Test
    fun `all published or all blocked degrades to a single group`() {
        val allGood = List(3) { entry("g$it", WatchStoreReviewStatus.PUBLISHED) }
        assertEquals(0, myUploadStatusGroups(allGood).first.size)
        assertEquals(3, myUploadStatusGroups(allGood).second.size)

        val allBad = List(2) { entry("b$it", WatchStoreReviewStatus.REJECTED) }
        assertEquals(2, myUploadStatusGroups(allBad).first.size)
        assertEquals(0, myUploadStatusGroups(allBad).second.size)

        assertEquals(0 to 0, myUploadStatusGroups(emptyList()).let { it.first.size to it.second.size })
    }
}
