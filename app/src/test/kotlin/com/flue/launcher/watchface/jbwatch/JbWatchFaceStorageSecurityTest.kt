package com.flue.launcher.watchface.jbwatch

import android.content.Context
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 审查报告 P1-23 的回归测试：jbwatch 导入通道曾对归档大小 / 条目数 /
 * 展开总量没有任何限制，一个高压缩比 zip 炸弹（几十 KB 的归档展开成
 * 数百 MB）就能写满 /data。防护必须按**实际写出的字节数**计数——
 * `ZipEntry.size` 是归档自报的值，炸弹会谎报。
 */
@RunWith(RobolectricTestRunner::class)
// 与仓库其它 Robolectric 测试一致固定到 27：本机 JDK 17 跑不了 SDK 36 的沙箱，
// 且这里只用到 Context.cacheDir，与 SDK 版本无关。
@Config(sdk = [27])
class JbWatchFaceStorageSecurityTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `zip bomb entry is rejected instead of filling the disk`() {
        val oneMiB = 1024L * 1024L
        val testLimits = JbWatchArchiveLimits(
            maximumArchiveBytes = 4L * oneMiB,
            maximumEntryCount = 32,
            maximumSingleEntryBytes = oneMiB,
            maximumExpandedBytes = 4L * oneMiB,
        )
        val bomb = File.createTempFile("jbwatch_bomb_", ".watch", context.cacheDir)
        try {
            ZipOutputStream(bomb.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("watch.xml"))
                zip.write("<Watch name=\"bomb\"/>".toByteArray())
                zip.closeEntry()
                // 2MB 的零字节：压缩后很小，但超过测试注入的单条目 1MB 上限。
                zip.putNextEntry(ZipEntry("images/huge.bin"))
                val zeros = ByteArray(1024 * 1024)
                repeat(2) { zip.write(zeros) }
                zip.closeEntry()
            }
            // 炸弹本体必须是小文件，否则测试测的是归档大小上限而不是展开上限。
            assertTrue(
                "测试前提：压缩后的炸弹应远小于归档上限（实际 ${bomb.length()} 字节）",
                bomb.length() < 4L * 1024L * 1024L,
            )

            val error = assertThrows(IllegalArgumentException::class.java) {
                JbWatchFaceStorage.importArchiveFile(context, bomb, testLimits)
            }
            assertTrue(
                "拒绝原因应指向大小限制，实际：${error.message}",
                error.message.orEmpty().contains("上限"),
            )
        } finally {
            bomb.delete()
        }
    }

    @Test
    fun `entry count explosion is rejected`() {
        val flood = File.createTempFile("jbwatch_flood_", ".watch", context.cacheDir)
        try {
            ZipOutputStream(flood.outputStream()).use { zip ->
                repeat(2_100) { index ->
                    zip.putNextEntry(ZipEntry("junk/$index.txt"))
                    zip.write(byteArrayOf(0x30))
                    zip.closeEntry()
                }
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                JbWatchFaceStorage.importArchiveFile(context, flood)
            }
            assertTrue(
                "拒绝原因应指向条目数限制，实际：${error.message}",
                error.message.orEmpty().contains("条目"),
            )
        } finally {
            flood.delete()
        }
    }
}
