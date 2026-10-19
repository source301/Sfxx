package com.sfxreader

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class SfxPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val sounds = mapOf(
        "boom" to pool.load(context, R.raw.sfx_boom, 1),
        "hit" to pool.load(context, R.raw.sfx_hit, 1),
        "step" to pool.load(context, R.raw.sfx_step, 1),
        "door" to pool.load(context, R.raw.sfx_door, 1),
        "heartbeat" to pool.load(context, R.raw.sfx_heartbeat, 1),
        "wind" to pool.load(context, R.raw.sfx_wind, 1),
        "splash" to pool.load(context, R.raw.sfx_splash, 1),
        "click" to pool.load(context, R.raw.sfx_click, 1),
        "bell" to pool.load(context, R.raw.sfx_bell, 1),
        "crash" to pool.load(context, R.raw.sfx_crash, 1),
    )

    fun play(category: String) {
        sounds[category]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() = pool.release()
}
