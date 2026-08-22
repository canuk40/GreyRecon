package com.greyrecon.app.ui.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * Minimal [TerminalSessionClient]/[TerminalViewClient] implementation -- GreyRecon doesn't need
 * Termux's full feature set here (session multiplexing, notification-bar integration, bell
 * vibration settings, etc.), just enough for a single working interactive terminal: keep the view
 * redrawing on output, forward hardware/soft-keyboard input, and log rather than crash on the
 * logging callbacks every real terminal implementation is expected to provide.
 */
class GreyReconTerminalClient(
    private val onSessionFinished: () -> Unit,
    private val onScreenUpdate: () -> Unit,
    private val onRequestKeyboard: () -> Unit,
    private val baseTextSizePx: Float,
    private val onSetTextSize: (Int) -> Unit,
    private val onScaleChanged: (Float) -> Unit,
) : TerminalSessionClient, TerminalViewClient {

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) = onScreenUpdate()

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinished()

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit

    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = onScreenUpdate()

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    // --- TerminalViewClient ---

    // `scale` arrives as TerminalView's own accumulated mScaleFactor (1f at rest, multiplied by each
    // incremental pinch gesture) -- clamp it to a readable range, apply the resulting font size, and
    // return the clamped value so TerminalView's own accumulator stays in sync (otherwise the next
    // pinch would resume from an unclamped value and could jump when it re-enters the visible range).
    override fun onScale(scale: Float): Float {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        onSetTextSize((baseTextSizePx * clamped).toInt())
        onScaleChanged(clamped)
        return clamped
    }

    override fun onSingleTapUp(e: MotionEvent) = onRequestKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    // --- Shared logging (both interfaces declare the same methods) ---

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: LOG_TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: LOG_TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: LOG_TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: LOG_TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: LOG_TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: LOG_TAG, message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: LOG_TAG, "", e) }

    companion object {
        private const val LOG_TAG = "GreyReconTerminal"
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 3.0f
    }
}
