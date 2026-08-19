package com.flue.launcher.watchface.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.flue.launcher.service.WLauncherNotificationListener

/**
 * 表盘上的媒体控制动作（`media_play_pause` / `media_next` / …）落地的地方。
 *
 * ## 为什么不只发媒体键
 *
 * 第一版只用 `AudioManager.dispatchMediaKeyEvent`，老师实测「音乐播放没用」。媒体键要
 * 系统把它路由给「当前的媒体按键接收者」，而手表上这条路经常断：本地播放器可能没注册
 * 媒体按键接收者，蓝牙中继过来的会话更是只认 `MediaController` 的传输控制。
 *
 * 所以顺序反过来：**先拿活跃会话直接下命令**（这是播放器一定实现的那一路，控制中心的
 * 媒体卡片走的也是它），拿不到会话才退回媒体键。
 *
 * ## 这一层为什么单独一个文件
 *
 * 渲染层的规矩是「不反向依赖启动器的 `NotificationListenerService`」（见
 * `AndroidWatchDataProvider` 里 `{cnc}` 那条注释）。`MediaSessionManager.getActiveSessions`
 * 偏偏要求传入一个**已授权的通知监听组件**，绕不开。折中办法就是把这条依赖收进这一个
 * 只做桥接的小文件里：渲染器只认识 [send]，剩下的谁都不用知道。
 */
internal object WatchMediaTransport {

    /** 送出一条媒体命令。返回 false 表示既没有活跃会话、媒体键也没送出去。 */
    fun send(context: Context, key: WatchMediaKey): Boolean {
        controlActiveSession(context, key)?.let { return it }
        return dispatchMediaKey(context, key)
    }

    /** 当前持有媒体会话的应用包名，没有会话时为 null。`w_music` 用它兜底。 */
    fun activeSessionPackage(context: Context): String? = activeController(context)?.packageName

    /**
     * 通过活跃会话下命令。null = 没有会话（或没有通知监听授权），由调用方退回媒体键。
     */
    private fun controlActiveSession(context: Context, key: WatchMediaKey): Boolean? {
        val controller = activeController(context) ?: return null
        val transport = controller.transportControls
        return runCatching {
            when (key) {
                WatchMediaKey.PLAY -> transport.play()
                WatchMediaKey.PAUSE -> transport.pause()
                WatchMediaKey.NEXT -> transport.skipToNext()
                WatchMediaKey.PREVIOUS -> transport.skipToPrevious()
                WatchMediaKey.PLAY_PAUSE ->
                    // 会话自己就带播放状态，不用猜：正在放就暂停，否则播放。
                    // （有些播放器根本不实现 `KEYCODE_MEDIA_PLAY_PAUSE` 的切换语义。）
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        transport.pause()
                    } else {
                        transport.play()
                    }
            }
            true
        }.getOrDefault(false)
    }

    private fun activeController(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, WLauncherNotificationListener::class.java)
        // 没给通知监听权限时这里直接抛 SecurityException —— 那是「未授权」而不是「出错」，
        // 所以吞掉并退回媒体键，别把表盘点击变成一次崩溃。
        return runCatching { manager.getActiveSessions(component) }
            .getOrNull()
            ?.firstOrNull { it.playbackState != null }
            ?: runCatching { manager.getActiveSessions(component).firstOrNull() }.getOrNull()
    }

    private fun dispatchMediaKey(context: Context, key: WatchMediaKey): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val keyCode = when (key) {
            WatchMediaKey.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            WatchMediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            WatchMediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            WatchMediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            WatchMediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        // 必须**成对**发 DOWN + UP：只发 DOWN 很多播放器会当成长按、或者干脆不动。
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
    }

    /**
     * 「打开音乐应用」的兜底链。
     *
     * `CATEGORY_APP_MUSIC` 在手表上**经常没人声明** —— 那时 `startActivity` 直接抛
     * `ActivityNotFoundException`，被吞掉就成了「点了没反应」（老师报的「大量动作不可用」
     * 有一半是这个）。所以按顺序试：类目 → 当前持有媒体会话的那个应用 → 常见音乐包名。
     */
    fun musicIntent(context: Context): Intent? {
        val manager = context.packageManager
        val category = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        if (manager.resolveActivity(category, 0) != null) return category
        activeSessionPackage(context)
            ?.let(manager::getLaunchIntentForPackage)
            ?.let { return it }
        return KNOWN_MUSIC_PACKAGES.firstNotNullOfOrNull(manager::getLaunchIntentForPackage)
    }

    private val KNOWN_MUSIC_PACKAGES = listOf(
        "com.google.android.apps.youtube.music",
        "com.google.android.music",
        "com.spotify.music",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.apple.android.music",
    )
}
