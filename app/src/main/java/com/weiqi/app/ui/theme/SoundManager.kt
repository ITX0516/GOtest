package com.weiqi.app.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.weiqi.app.R

/**
 * 落子与对局音效管理器。
 *
 * 基于 [SoundPool] 加载 raw 资源音效：
 * - `stone_black.wav` / `stone_white.wav`：黑白落子声
 * - `capture.wav`：提子声
 * - `pass.wav`：弃权声
 *
 * 资源加载失败（如占位 XML 无法解码为音频）时静默跳过，不影响应用运行。
 * 不同主题可由调用方决定是否区分音色（此处按黑/白分别播放）。
 *
 * 生命周期：构造时加载资源，[release] 时释放池。
 *
 * @param context 应用上下文。
 */
class SoundManager(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile
    private var enabled: Boolean = true

    // 加载成功时 soundId > 0；失败/资源缺失时为 0
    private val blackStoneSound: Int = loadSafely(R.raw.stone_black)
    private val whiteStoneSound: Int = loadSafely(R.raw.stone_white)
    private val captureSound: Int = loadSafely(R.raw.capture)
    private val passSound: Int = loadSafely(R.raw.pass)

    /**
     * 播放落子音效。
     * @param black true 播放黑子声，false 播放白子声。
     */
    fun playStoneSound(black: Boolean) {
        if (!enabled) return
        val id = if (black) blackStoneSound else whiteStoneSound
        play(id, 1f)
    }

    /** 播放提子音效。 */
    fun playCaptureSound() {
        if (!enabled) return
        play(captureSound, 0.85f)
    }

    /** 播放过/弃权音效。 */
    fun playPassSound() {
        if (!enabled) return
        play(passSound, 0.85f)
    }

    /** 启用或禁用音效输出；禁用时所有 play* 调用静默返回。 */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** 释放底层 SoundPool 资源，释放后不可再播放。 */
    fun release() {
        runCatching { pool.release() }
    }

    /** 安全加载 raw 资源；任何异常或失败均返回 0。 */
    private fun loadSafely(resId: Int): Int = runCatching {
        pool.load(context, resId, PRIORITY)
    }.getOrDefault(0)

    /** 仅当 soundId 有效时播放。 */
    private fun play(soundId: Int, volume: Float) {
        if (soundId == 0) return
        runCatching {
            pool.play(soundId, volume, volume, PRIORITY, NO_LOOP, NORMAL_RATE)
        }
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}
