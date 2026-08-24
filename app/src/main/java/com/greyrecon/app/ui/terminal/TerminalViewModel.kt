package com.greyrecon.app.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.termux.terminal.TerminalSession

/**
 * Owns everything about the terminal that should survive navigating away from and back to the
 * "terminal" NavHost destination -- the real PTY-backed shell (`TerminalSession`) and the
 * `PkgFetchServer` it can talk to. Scoped to the hosting Activity (obtained via `viewModel()` with
 * no explicit owner, so it uses the default `LocalViewModelStoreOwner`), so it outlives any single
 * `TerminalScreen` composition but is still cleaned up if the Activity itself is actually destroyed.
 *
 * Before this existed, `TerminalScreen`'s `DisposableEffect` called `session?.finishIfRunning()` on
 * every dispose -- meaning navigating to any other screen and back killed the shell and started a
 * fresh one, losing cwd, shell history, any backgrounded job, and the toybox/greyrecon-pkg PATH setup
 * (cheap to redo, but the rest wasn't). `TerminalScreen` now checks `viewModel.session` before
 * creating anything and reattaches the existing session to a freshly created `TerminalView` if one is
 * already running, instead of unconditionally building a new one.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    var session: TerminalSession? = null

    /** Accumulated pinch-to-zoom factor, applied to the base font size -- see GreyReconTerminalClient.onScale(). */
    var fontScale: Float = 1f

    val fetchServer = PkgFetchServer(application.filesDir).apply { start() }

    /** Loopback bridge that makes the AI agent reachable from the terminal via the `ai` command. */
    val agentServer = AgentBridgeServer(application).apply { start() }

    override fun onCleared() {
        session?.finishIfRunning()
        fetchServer.stop()
        agentServer.stop()
    }
}
