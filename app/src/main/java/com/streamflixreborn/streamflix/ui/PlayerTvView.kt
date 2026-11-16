package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.R
import java.util.Locale

class PlayerTvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PlayerView(context, attrs, defStyle) {

    val controller: PlayerControlView
        get() = PlayerView::class.java.getDeclaredField("controller").let {
            it.isAccessible = true
            it.get(this) as PlayerControlView
        }

    var isManualZoomEnabled: Boolean = false
        private set

    var onMediaPreviousClicked: (() -> Boolean)? = null
    var onMediaNextClicked: (() -> Boolean)? = null

    private var zoomToast: Toast? = null

    fun enterManualZoomMode() {
        player?.pause()
        isManualZoomEnabled = true
        showZoomToast(context.getString(R.string.player_manual_zoom_hint), Toast.LENGTH_LONG)
    }

    fun exitManualZoomMode() {
        isManualZoomEnabled = false
        videoSurfaceView?.apply {
            scaleX = 1f
            scaleY = 1f
        }
        zoomToast?.cancel()
        player?.play()
    }

    private fun showZoomToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        zoomToast?.cancel()
        zoomToast = Toast.makeText(context, message, duration)
        zoomToast?.show()
    }

    private var canceledSeekBackDownTime: Long? = null

    fun cancelTimelineSeek(): Boolean {
        val timeline = controller.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        if (!controller.isVisible || timeline == null || !timeline.isPressed) return false

        // DefaultTimeBar handles ACTION_CANCEL for both remote and touch scrubs.
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        return try {
            timeline.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                canceledSeekBackDownTime = if (cancelTimelineSeek()) event.downTime else null
            }
            if (canceledSeekBackDownTime == event.downTime) {
                if (event.action == KeyEvent.ACTION_UP) canceledSeekBackDownTime = null
                return true
            }
        }

        if (isManualZoomEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // RIPRISTINATA LOGICA ORIGINALE: Scaliamo solo il videoSurfaceView.
                // Grazie all'uso di texture_view nel layout, ora le modifiche sono visibili in tempo reale anche in pausa.
                val videoView = videoSurfaceView ?: return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        videoView.scaleY += 0.01f
                        showZoomToast("Zoom Y: ${String.format(Locale.US, "%.2f", videoView.scaleY)}")
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        videoView.scaleY -= 0.01f
                        showZoomToast("Zoom Y: ${String.format(Locale.US, "%.2f", videoView.scaleY)}")
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        videoView.scaleX -= 0.01f
                        showZoomToast("Zoom X: ${String.format(Locale.US, "%.2f", videoView.scaleX)}")
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        videoView.scaleX += 0.01f
                        showZoomToast("Zoom X: ${String.format(Locale.US, "%.2f", videoView.scaleX)}")
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        exitManualZoomMode()
                    }
                }
            }
            return true
        }

        val player = player ?: return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    onMediaPreviousClicked?.invoke() ?: false
                } else {
                    true
                }
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    onMediaNextClicked?.invoke() ?: false
                } else {
                    true
                }
            }
        }

        if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && player.isPlayingAd) {
            return super.dispatchKeyEvent(event)
        }

        if (controller.isVisible) {
            val focused = findFocus()
            val isConfirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isConfirm && focused?.id == androidx.media3.ui.R.id.exo_progress) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        // Let the time bar commit any pending keyboard seek.
                        focused.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, event)
                        showController()
                    }
                    KeyEvent.ACTION_UP -> if (!event.isCanceled) {
                        val toggle = controller.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        if (toggle?.isEnabled == true) toggle.performClick()
                    }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    player.seekTo(player.currentPosition - 10_000)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    player.seekTo(player.currentPosition + 10_000)
                }
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
