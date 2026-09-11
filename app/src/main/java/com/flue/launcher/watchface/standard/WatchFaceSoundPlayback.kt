package com.flue.launcher.watchface.standard

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Owns one effect. Every asynchronous callback rechecks both identity and playback eligibility. */
internal class WatchFaceSoundPlayback(private val eligible: () -> Boolean) {
    var currentPlayer: MediaPlayer? = null
        private set

    fun stop() {
        val previous = currentPlayer
        currentPlayer = null
        previous?.let { runCatching { it.release() } }
    }

    fun play(file: File) {
        stop()
        if (!eligible()) return
        val player = MediaPlayer()
        currentPlayer = player
        runCatching {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                when {
                    currentPlayer !== prepared -> runCatching { prepared.release() }
                    !eligible() -> stop()
                    else -> runCatching { prepared.start() }.onFailure {
                        if (currentPlayer === prepared) stop()
                    }
                }
            }
            player.setOnCompletionListener { if (currentPlayer === it) stop() }
            player.setOnErrorListener { failed, _, _ -> if (currentPlayer === failed) stop(); true }
            player.prepareAsync()
        }.onFailure { if (currentPlayer === player) stop() }
    }
}
