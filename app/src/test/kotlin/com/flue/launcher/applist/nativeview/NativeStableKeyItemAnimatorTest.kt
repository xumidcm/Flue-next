package com.flue.launcher.applist.nativeview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 装卸应用时那只「粘在背景层、点不动但有按压动画」的鬼图标的回归测试。
 *
 * ## 症状是怎么串起来的
 *
 * `animatePersistence` 返回 true 就等于对 RecyclerView 承诺「我会调
 * `dispatchAnimationFinished`」。漏掉这次上报，RecyclerView 会一直认为那个 holder 还在
 * 动画：既不回收也不重新布局，它带着动画**起始**位移停在旧槽位、作为残留子 View 画在新
 * 挂上来的图标下面。触摸照样落在它身上（按压覆层会亮），可 `NativeAppListView` 的命中
 * 测试全部按 `bindingAdapterPosition` 过滤，`NO_POSITION` 的它对点击与拖拽完全隐形。
 *
 * ## 触发它需要的交错
 *
 * 装/卸应用会连着提交两次差分（`AppRepository` 的防抖刷新发一次、图标加载完再发一次）。
 * 第二次提交的位移还躺在 `pendingMoves` 里，而 `animatePersistence` 开头对每个 holder 调
 * 的 `endAnimation` 恰好摘走上一批最后一个活跃项 —— 旧实现此时就把批动画器连监听一起
 * 拆了，于是再没人来接 `pendingMoves`。
 *
 * 所以这里用**真实的调用顺序**复现，而不是直接测某个私有状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class NativeStableKeyItemAnimatorTest {

    private class TestHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun holder(): TestHolder = TestHolder(View(RuntimeEnvironment.getApplication()))

    private fun info(top: Int): RecyclerView.ItemAnimator.ItemHolderInfo =
        RecyclerView.ItemAnimator.ItemHolderInfo().apply {
            left = 0
            this.top = top
            right = 100
            bottom = top + 100
        }

    /** 位移动画：起点是「上一帧的位置减这一帧的位置」，所以 View 先被顶回旧位置。 */
    private fun startMove(
        animator: NativeStableKeyItemAnimator,
        holder: TestHolder,
        fromTop: Int,
        toTop: Int,
    ) {
        val animating = animator.animatePersistence(holder, info(fromTop), info(toTop))
        assertEquals("holder 应当进入位移动画", true, animating)
        assertEquals(
            "起始位移应当等于 from - to",
            (fromTop - toTop).toFloat(),
            holder.itemView.translationY,
            0.001f,
        )
    }

    private fun idle(millis: Long) {
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idleFor(Duration.ofMillis(millis))
    }

    @Test
    fun `a move queued while the previous batch is still running still finishes`() {
        val animator = NativeStableKeyItemAnimator()
        val first = holder()
        val second = holder()

        // 第一次提交：first 位移，批动画器起来。
        startMove(animator, first, fromTop = 0, toTop = 100)
        animator.runPendingAnimations()

        // 第二次提交紧接着来：second 的位移排进 pendingMoves……
        startMove(animator, second, fromTop = 100, toTop = 0)
        // ……而 first 在这一轮被摘走（回收、或者 animatePersistence 自己调的 endAnimation）。
        animator.endAnimation(first)

        idle(1_000L)

        // second 必须被真正收尾：位移归零、动画器回到空闲。
        // 旧实现里批动画器在上一行就连监听一起被拆了，second 会永远停在 100f、
        // isRunning 永远是 true —— 那就是老师看到的鬼图标。
        assertEquals(0f, second.itemView.translationY, 0.001f)
        assertEquals(0f, first.itemView.translationY, 0.001f)
        assertFalse("动画器不该永远停在运行中", animator.isRunning)
    }

    @Test
    fun `ending the last active move with nothing pending stops cleanly`() {
        val animator = NativeStableKeyItemAnimator()
        val only = holder()

        startMove(animator, only, fromTop = 0, toTop = 60)
        animator.runPendingAnimations()
        animator.endAnimation(only)

        assertEquals(0f, only.itemView.translationY, 0.001f)
        assertFalse(animator.isRunning)
        // 空闲之后也不该有残留的帧回调把位移改回去。
        idle(1_000L)
        assertEquals(0f, only.itemView.translationY, 0.001f)
    }

    @Test
    fun `endAnimations clears both the running batch and the queued moves`() {
        val animator = NativeStableKeyItemAnimator()
        val running = holder()
        val queued = holder()

        startMove(animator, running, fromTop = 0, toTop = 40)
        animator.runPendingAnimations()
        startMove(animator, queued, fromTop = 40, toTop = 0)

        // 换布局 / 换数据源时 RecyclerView 会同步要求「立刻结束一切」。
        animator.endAnimations()

        assertEquals(0f, running.itemView.translationY, 0.001f)
        assertEquals(0f, queued.itemView.translationY, 0.001f)
        assertFalse(animator.isRunning)
    }

    @Test
    fun `a batch left running to completion resets every holder`() {
        val animator = NativeStableKeyItemAnimator()
        val holders = List(3) { holder() }

        holders.forEachIndexed { index, item ->
            startMove(animator, item, fromTop = index * 50, toTop = (index + 1) * 50)
        }
        animator.runPendingAnimations()
        idle(1_000L)

        holders.forEach { item ->
            assertEquals(0f, item.itemView.translationY, 0.001f)
        }
        assertFalse(animator.isRunning)
    }

    @Test
    fun `the dragged holder is left alone`() {
        val dragged = holder()
        val animator = NativeStableKeyItemAnimator(shouldAnimateHolder = { it !== dragged })

        // 被拖的那一个不参与位移动画：它的位置由拖拽控制器直接给，动画器插手会打架。
        assertEquals(false, animator.animatePersistence(dragged, info(0), info(100)))
        assertEquals(0f, dragged.itemView.translationY, 0.001f)
        assertFalse(animator.isRunning)
    }
}
