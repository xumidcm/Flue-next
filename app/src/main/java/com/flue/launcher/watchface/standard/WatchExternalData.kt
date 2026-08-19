package com.flue.launcher.watchface.standard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 一个外部数据槽位。
 *
 * 与上游 clox `docs/EXTERNAL_DATA_API.md` 的三个字段一一对应；从没写过的槽位是
 * `("", "", 0)`，不是 null —— Lua 侧要能无条件 `slots[n].data` 而不用先判空。
 */
data class WatchExternalDataSlot(
    val data: String = "",
    val key: String = "",
    val timestampMs: Long = 0L,
)

/** 一次已经通过校验的写入。 */
data class WatchExternalDataWrite(
    val slot: Int,
    val slotData: WatchExternalDataSlot,
)

/**
 * clox 的 External Data API（B5）。
 *
 * 上游的形状：广播 `com.ailife.clox.WRITE_DATA`，extras `slot`（1–10，必填）/ `data`
 * （String，必填）/ `key`（可选标签），落 SharedPreferences 持久化，并立即让活动表盘
 * 通过 `WF.thirdParty.slots[n]` 读到。
 *
 * ## 安全基线（这一条必须留在代码里）
 *
 * 上游明说「**无 API key、无权限，任何已安装的 App 都能写**」。要跟 clox 的集成方兼容
 * 就不能加权限门（加了写方全都失效），所以槽位数据一律当**不可信输入**处理：
 *
 * - **长度上限**：[MAX_DATA_LENGTH] / [MAX_KEY_LENGTH]。上游只靠 Android 的 IPC 上限
 *   （约 1MB）兜着，那足够让一个恶意 App 把手表的偏好文件撑大。
 * - **只当字符串**：不解析、不反序列化、不猜类型。要结构化数据的表盘自己 JSON 解。
 * - **绝不进求值路径**：槽位值只作为**字符串值**交给 Lua（`slots[n].data`），不会被拼回
 *   表达式再编译，也不会被当成 `{tag}` 二次展开。表盘脚本跑在白名单沙箱里
 *   （见 [WatchFaceLuaRuntime] 的白名单），拿到一段字符串做不了别的事。
 * - **控制字符剔掉**：换行制表之外的 C0 字符对文本排版没有意义，留着只会让图层画出
 *   豆腐块或撑坏行高。
 */
object WatchExternalData {

    /** 上游的动作名。为了兼容照 clox 写的集成方，Flue 也收这一个。 */
    const val ACTION_CLOX_WRITE_DATA = "com.ailife.clox.WRITE_DATA"

    /** Flue 自己命名空间下的同义动作，给新写的集成方用。 */
    const val ACTION_FLUE_WRITE_DATA = "com.flue.launcher.WRITE_DATA"

    const val EXTRA_SLOT = "slot"
    const val EXTRA_DATA = "data"
    const val EXTRA_KEY = "key"

    const val MIN_SLOT = 1
    const val MAX_SLOT = 10

    /** 单槽位数据上限。够放一小段 JSON，又不至于让偏好文件被人撑爆。 */
    const val MAX_DATA_LENGTH = 4_096

    /** 标签只是个名字，64 字符绰绰有余。 */
    const val MAX_KEY_LENGTH = 64

    /** 从没写过的槽位。 */
    val EMPTY_SLOT = WatchExternalDataSlot()

    /**
     * 校验并规范化一次写入。返回 `null` = **静默忽略**（与上游一致：槽位号越界或缺
     * `data` 都不报错、不写入）。
     *
     * 纯函数，可单测；接收器只负责把 Intent 拆成参数。
     */
    fun sanitizeWrite(slot: Int, data: String?, key: String?, nowMs: Long): WatchExternalDataWrite? {
        if (slot < MIN_SLOT || slot > MAX_SLOT) return null
        if (data == null) return null
        return WatchExternalDataWrite(
            slot = slot,
            slotData = WatchExternalDataSlot(
                data = sanitizeText(data, MAX_DATA_LENGTH),
                key = sanitizeText(key.orEmpty(), MAX_KEY_LENGTH),
                // 时间戳由我们盖，不收发送方给的值 —— 那是「本机什么时候收到的」，
                // 不该让外部 App 随便写成未来或 1970。
                timestampMs = nowMs.coerceAtLeast(0L),
            ),
        )
    }

    /**
     * 先剔控制字符再截断。
     *
     * 顺序很重要：先截断的话，末尾可能正好切在一个多字节序列中间，剔完控制字符长度还会变，
     * 上限就不再是硬上限。
     */
    private fun sanitizeText(raw: String, maxLength: Int): String {
        val filtered = buildString(raw.length.coerceAtMost(maxLength)) {
            for (char in raw) {
                if (length >= maxLength) break
                // 换行与制表留着（多行消息是正当用法），其余 C0 与 DEL 剔掉。
                if (char == '\n' || char == '\t' || char.code >= 0x20 && char.code != 0x7F) {
                    append(char)
                }
            }
        }
        return filtered
    }
}

/**
 * Thread-safe in-memory snapshot for the ten public slots.
 *
 * Keeping this separate from [SharedPreferences] makes the update contract explicit and
 * testable: a write replaces exactly one 1-indexed slot, then notifies listeners after the
 * snapshot is visible. The renderer uses the listener to schedule a frame; Lua itself still
 * reads through a supplier so no broadcast thread ever touches a Lua VM.
 */
class WatchExternalDataSlotState(
    initialSlots: List<WatchExternalDataSlot> = emptyList(),
) {

    @Volatile
    private var snapshot: List<WatchExternalDataSlot> = List(WatchExternalData.MAX_SLOT) { index ->
        initialSlots.getOrNull(index) ?: WatchExternalData.EMPTY_SLOT
    }

    @Volatile
    private var revision = 0L

    private val listeners = CopyOnWriteArraySet<(Int) -> Unit>()

    /** Monotonically increasing generation used to invalidate Lua expression caches. */
    val version: Long
        get() = revision

    /** 1-indexed; invalid indexes return the documented empty slot. */
    fun slot(slot: Int): WatchExternalDataSlot {
        val index = slot - WatchExternalData.MIN_SLOT
        return snapshot.getOrNull(index) ?: WatchExternalData.EMPTY_SLOT
    }

    /**
     * Publishes one validated write and returns without exposing a partially updated list.
     * Invalid writes are ignored defensively even though the receiver sanitizes first.
     */
    fun write(write: WatchExternalDataWrite) {
        val index = write.slot - WatchExternalData.MIN_SLOT
        if (index !in 0 until WatchExternalData.MAX_SLOT) return
        val listenersToNotify: List<(Int) -> Unit>
        synchronized(this) {
            snapshot = snapshot.toMutableList().also { it[index] = write.slotData }
            revision++
            listenersToNotify = listeners.toList()
        }
        listenersToNotify.forEach { listener ->
            // A renderer must never be able to abort the broadcast receiver for the other
            // listeners (or prevent persistence) because its View is already disposed.
            runCatching { listener(write.slot) }
        }
    }

    fun addListener(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners -= listener
    }
}

/**
 * 槽位存储。
 *
 * SharedPreferences 一份，外加一份内存快照 —— 表盘每帧都可能读槽位（Lua 的
 * `slots[n]` 走的是**惰性查表**，不做推送），走内存数组比每次问偏好文件省。
 *
 * 按 application context 缓存一个实例：接收器与渲染器在同一个进程里，两边必须看到
 * 同一份内存快照，否则广播写完了表盘还在读旧值。
 */
class WatchExternalDataStore private constructor(private val prefs: SharedPreferences) {

    private val state = WatchExternalDataSlotState(loadAll())

    /** 1-indexed，与上游 Lua 侧一致。越界返回空槽位而不是抛。 */
    fun slot(slot: Int): WatchExternalDataSlot = state.slot(slot)

    /** Changes whenever a slot is published; used to invalidate cached Lua reads. */
    val version: Long
        get() = state.version

    /** Adds a renderer callback invoked after a slot snapshot has been published. */
    fun addListener(listener: (Int) -> Unit) = state.addListener(listener)

    /** Removes a renderer callback; safe to call more than once. */
    fun removeListener(listener: (Int) -> Unit) = state.removeListener(listener)

    // 有意只暴露单槽位读取：表盘侧要的就是 `slots[n]` 这一个动作。批量读、清空这些
    // 等真有入口再加，不留没人调的方法。

    /** 落盘 + 刷新内存快照。校验交给 [WatchExternalData.sanitizeWrite]。 */
    fun write(write: WatchExternalDataWrite) {
        val index = write.slot - WatchExternalData.MIN_SLOT
        if (index !in 0 until WatchExternalData.MAX_SLOT) return
        prefs.edit()
            .putString(dataKey(write.slot), write.slotData.data)
            .putString(labelKey(write.slot), write.slotData.key)
            .putLong(timestampKey(write.slot), write.slotData.timestampMs)
            .apply()
        // 内存快照**同步**换掉：广播回调之后表盘马上会被 listener 唤醒，不能等
        // apply() 落盘才更新。整块替换而不是原地改，读侧只需一次 volatile 读。
        state.write(write)
    }

    private fun loadAll(): List<WatchExternalDataSlot> =
        (WatchExternalData.MIN_SLOT..WatchExternalData.MAX_SLOT).map { slot ->
            WatchExternalDataSlot(
                data = prefs.getString(dataKey(slot), "").orEmpty(),
                key = prefs.getString(labelKey(slot), "").orEmpty(),
                timestampMs = prefs.getLong(timestampKey(slot), 0L),
            )
        }

    companion object {
        private const val PREFS_NAME = "flue_watch_external_data"

        @Volatile
        private var instance: WatchExternalDataStore? = null

        fun of(context: Context): WatchExternalDataStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: WatchExternalDataStore(
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
        }

        private fun dataKey(slot: Int) = "slot_${slot}_data"
        private fun labelKey(slot: Int) = "slot_${slot}_key"
        private fun timestampKey(slot: Int) = "slot_${slot}_ts"
    }
}

/**
 * 外部数据写入的接收器。
 *
 * 只做三件事：拆 Intent → 过 [WatchExternalData.sanitizeWrite] → 落库。**不做任何解析、
 * 不启动组件、不回传** —— 这是一个任何 App 都能触达的导出组件（上游的 API 就是这么定的），
 * 表面越小越好。上游对非法写入是「静默忽略」，这里照办。
 */
class WatchExternalDataReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action != WatchExternalData.ACTION_CLOX_WRITE_DATA &&
            action != WatchExternalData.ACTION_FLUE_WRITE_DATA
        ) {
            return
        }
        // getIntExtra 的兜底给一个越界值，缺 slot 时自然被 sanitizeWrite 拒掉。
        val slot = intent.getIntExtra(WatchExternalData.EXTRA_SLOT, -1)
        val write = WatchExternalData.sanitizeWrite(
            slot = slot,
            data = runCatching { intent.getStringExtra(WatchExternalData.EXTRA_DATA) }.getOrNull(),
            key = runCatching { intent.getStringExtra(WatchExternalData.EXTRA_KEY) }.getOrNull(),
            nowMs = System.currentTimeMillis(),
        ) ?: return
        WatchExternalDataStore.of(context).write(write)
    }
}
