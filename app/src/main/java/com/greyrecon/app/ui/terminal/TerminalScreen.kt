package com.greyrecon.app.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Toybox (github.com/landley/toybox, 0BSD) bundled as `jniLibs/arm64-v8a/libtoybox.so` so AGP
 * extracts it to a real on-disk file under the `apk_data_file` SELinux label -- unlike anything an
 * app writes to its own data dir at runtime, that label is exec-permitted directly (see
 * greyrecon_exec.c's block comment for why the runtime-write case needs a workaround at all).
 *
 * Toybox's own multicall dispatch keys off argv[0]'s basename, so the single binary only behaves as
 * `ls`/`grep`/etc. when invoked through a symlink actually named after the applet (confirmed live --
 * running the file directly by its real jniLibs path, "libtoybox.so", dispatches to neither the
 * multiplexer nor any applet). This creates those symlinks once under $HOME/bin -- one named
 * `toybox` for the explicit `toybox <applet>` form, plus one per applet toybox itself reports via
 * `toybox list` -- into the given (already-created) bin dir. Returns false on an ABI this build
 * didn't bundle toybox for (only arm64-v8a today), true otherwise.
 */
private fun setupToyboxBinDir(ctx: Context, binDir: File): Boolean {
    val toyboxPath = File(ctx.applicationInfo.nativeLibraryDir, "libtoybox.so")
    if (!toyboxPath.exists()) return false

    fun linkIfMissing(name: String) {
        val link = File(binDir, name).toPath()
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, toyboxPath.toPath())
        }
    }

    linkIfMissing("toybox")

    // toybox's applet set is fixed for a given binary -- only enumerate once. There's no dedicated
    // "list applets" subcommand (confirmed live -- `toybox list` prints an "Unknown command" error,
    // not a list); running the multicall binary with *no* arguments is what actually prints the
    // available COMMAND names (per `toybox --help`'s own description of that behavior).
    val marker = File(binDir, ".applets_linked")
    if (!marker.exists()) {
        try {
            val proc = ProcessBuilder(File(binDir, "toybox").absolutePath).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.trim().split(Regex("\\s+")).forEach { name -> if (name.isNotBlank()) linkIfMissing(name) }
            marker.writeText("done")
        } catch (e: Exception) {
            // `toybox <applet>` still works via the multiplexer symlink above even if this failed.
        }
    }

    return true
}

/**
 * Installs a shebang script bundled in the app's own assets (`assetName`, e.g. "greyrecon-pkg.sh")
 * into `$HOME/bin/<destName>`. These run as normal shebang scripts, so they go through
 * greyrecon_exec.c's existing shebang-rewrite path like any other script -- no new native binary or
 * build-chain work needed. Re-copies whenever the bundled asset is newer than what's already
 * installed, so app updates pick up script changes without a manual reinstall.
 *
 * If `destName` is currently a symlink (e.g. `help`, which this is used to deliberately shadow --
 * see setupToyboxBinDir() -- may already be a toybox-applet symlink from before this existed, or from
 * an install that ran before this one on a given device), it's deleted first rather than written
 * through: `File.outputStream()` on a symlink path follows it, and writing through a stale `help` ->
 * `libtoybox.so` symlink would mean writing into the real bundled toybox binary itself.
 */
private fun installBundledScript(ctx: Context, binDir: File, assetName: String, destName: String) {
    val dest = File(binDir, destName)
    val destPath = dest.toPath()
    if (Files.isSymbolicLink(destPath)) {
        Files.delete(destPath)
    } else {
        val assetTime = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        if (dest.exists() && dest.lastModified() >= assetTime) return
    }

    ctx.assets.open(assetName).use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    dest.setExecutable(true, true)
}

/**
 * A real, interactive terminal -- a genuine PTY-backed shell (`/system/bin/sh`, always present on
 * Android, no bundled binary needed), not just a scrolling command-output viewer. GreyRecon never
 * bundles or redistributes any CLI tool itself (nmap included -- its Nmap Public Source License
 * explicitly forbids that, see GreyRecon.md); this is general-purpose infrastructure for whatever
 * the user already has installed/available on their own device (their own Termux install, a binary
 * they've placed themselves, or Android's own built-in utilities).
 *
 * Built on vendored `terminal-emulator`/`terminal-view` (Apache-2.0, see the `NOTICE.md` in each
 * module) -- the same VT100 engine and native PTY-allocation code Termux itself runs on millions of
 * devices, not a from-scratch reimplementation.
 *
 * The real shell process (`TerminalSession`) and the `PkgFetchServer` live in [TerminalViewModel],
 * not here -- this composable (and the `TerminalView` its `AndroidView` factory creates) gets torn
 * down and rebuilt every time this NavHost destination is left and re-entered, but the ViewModel is
 * scoped to the Activity and survives that. The factory below reattaches the existing session to a
 * fresh `TerminalView` when one's already running, instead of unconditionally starting a new shell --
 * see GreyRecon.md for the "cwd/history/backgrounded jobs got silently wiped on every navigation" bug
 * this replaces.
 *
 * `viewModel()`'s default owner is deliberately overridden to the hosting Activity rather than left
 * at its own default. Navigation Compose gives each `composable(...)` destination its own
 * `NavBackStackEntry`-scoped `ViewModelStoreOwner`, and `LocalViewModelStoreOwner.current` resolves to
 * *that* inside a NavHost -- so a `TerminalViewModel` obtained via bare `viewModel()` here gets
 * cleared on every back-navigation, same as a plain `remember{}` would, defeating the entire point.
 * Confirmed live: without this, the "cwd persists across navigation" test below still failed after
 * this ViewModel was introduced, landing back in the default home dir with `$MARKER` unset.
 *
 * [prefilledCommand], when given, is written into a *newly created* session's input line (not
 * executed -- no trailing newline is sent) so the user can review and edit it before pressing Enter
 * themselves. Deliberately never submitted automatically: this is the same shell a scan action's
 * output could end up feeding a value into (an IP, say), and auto-running unseen text would be a real
 * command-injection-shaped risk for what's meant to be a security tool. Only applied on first creation
 * of a session, never on reattaching an already-running one -- otherwise simply re-entering the
 * terminal from the same launch point would re-paste stale text into a live prompt on every visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    prefilledCommand: String? = null,
    viewModel: TerminalViewModel = viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                val homeDir = File(ctx.filesDir, "terminal_home").apply { mkdirs() }

                val view = TerminalView(ctx, null)
                // TerminalView doesn't set these itself -- Termux's own app sets them via XML layout
                // attributes when inflating it, which doesn't apply when constructing it directly in
                // code. Without this, requestFocus() below silently no-ops and keyboard input goes
                // nowhere (confirmed live: focus stayed on the TopAppBar's back button instead).
                view.isFocusable = true
                view.isFocusableInTouchMode = true

                // Density-independent base font size (before any pinch-to-zoom) -- 1.0x scale.
                val baseTextSizePx = 14 * ctx.resources.displayMetrics.scaledDensity

                val client = GreyReconTerminalClient(
                    onSessionFinished = {},
                    // TerminalSession notifies the client on new output/color changes, but doesn't
                    // touch the View itself -- the client is responsible for telling the View to
                    // actually repaint. Confirmed live: without this, the shell ran and echoed input
                    // correctly but the screen never visibly updated at all.
                    onScreenUpdate = { view.onScreenUpdated() },
                    // TerminalView.onSingleTapUp() calls requestFocus() itself and then delegates to
                    // the client -- but requestFocus() alone doesn't summon the IME for a raw View
                    // (it's not an EditText). Without explicitly showing it here, tapping the terminal
                    // focuses it silently and no keyboard appears. Confirmed live.
                    onRequestKeyboard = {
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    },
                    baseTextSizePx = baseTextSizePx,
                    onSetTextSize = { px -> view.setTextSize(px) },
                    // Persisted so re-entering the terminal after navigating away keeps the same zoom
                    // level instead of resetting to 1.0x every time.
                    onScaleChanged = { scale -> viewModel.fontScale = scale },
                )
                view.setTerminalViewClient(client)
                view.setTextSize((baseTextSizePx * viewModel.fontScale).toInt())
                // Keeps TerminalView's own pinch accumulator in sync with the restored zoom level, so
                // the next pinch gesture continues from here instead of jumping back from 1.0x.
                view.mScaleFactor = viewModel.fontScale

                // Only set for a session actually created below (fresh, not reattached) -- see the
                // class doc comment for why a prefilled command is never re-injected into a reused
                // session.
                var freshSessionToPrefill: TerminalSession? = null

                val runningSession = viewModel.session?.takeIf { it.isRunning }
                if (runningSession != null) {
                    // Reattach rather than start a new shell -- this is the actual persistence: same
                    // PTY, same cwd, same scrollback and shell history, same LD_PRELOAD/PATH setup.
                    //
                    // updateTerminalSessionClient() matters just as much as attachSession() here: the
                    // session was built with a *previous* GreyReconTerminalClient closing over the
                    // *previous* (now-detached) TerminalView. attachSession() only points this View at
                    // the session -- without also repointing the session at this View's own client,
                    // the session keeps calling onTextChanged()/etc. on the dead client, whose
                    // onScreenUpdate captures the dead view. Confirmed live: input was actually
                    // reaching the shell the whole time (cwd/env state was correct) but the visibly
                    // open screen never repainted until a subsequent fresh view happened to redraw the
                    // buffer from scratch -- looked exactly like input was being silently dropped.
                    runningSession.updateTerminalSessionClient(client)
                    view.attachSession(runningSession)
                } else {
                    val shellPath = "/system/bin/sh"
                    // Vendor-independent native lib dir this app was installed with -- our compiled
                    // greyrecon_exec shim (see app/src/main/cpp/greyrecon_exec.c) lands here per-ABI
                    // automatically, same as the rest of GreyRecon's native code.
                    val execShimPath = "${ctx.applicationInfo.nativeLibraryDir}/libgreyrecon_exec.so"

                    // Android's /system/bin/sh is mksh (MirBSD Korn Shell), not toybox as originally
                    // assumed -- confirmed live via `strings /system/bin/sh`. mksh sources a startup rc
                    // file (${ENV:-/system/etc/mkshrc}) *after* env vars are set, and that file
                    // unconditionally overwrites PS1 with a hostname:cwd prompt -- which is why passing
                    // PS1 directly in the session's own env array had no visible effect. Point $ENV at
                    // our own minimal rc file instead of Android's system one so our own PS1 sticks.
                    val rcFile = File(homeDir, ".mkshrc").apply { writeText("PS1='$ '\nset +o nohup\n") }
                    val binDir = File(homeDir, "bin").apply { mkdirs() }
                    // Installed *before* setupToyboxBinDir() below so `help` claims that filename first
                    // -- toybox's own symlink pass only creates a symlink where nothing already exists,
                    // so this deliberately shadows toybox's own `help` applet (see help.sh for why).
                    installBundledScript(ctx, binDir, "help.sh", "help")
                    // greyrecon-pkg (see app/src/main/assets/greyrecon-pkg.sh) lives in the same bin
                    // dir -- an original package installer, not a dpkg/apt port; see GreyRecon.md.
                    installBundledScript(ctx, binDir, "greyrecon-pkg.sh", "greyrecon-pkg")
                    // `ai <question>` -- talks to AgentBridgeServer, running the on-device AI agent
                    // with the provider/key from Settings. Same install-a-shell-script pattern.
                    installBundledScript(ctx, binDir, "ai.sh", "ai")
                    // Toybox last so its applets (ls, grep, etc.) fill in everything else -- matching
                    // the Termux-like experience the terminal is meant to offer.
                    setupToyboxBinDir(ctx, binDir)
                    val pathPrefix = "${binDir.absolutePath}:"
                    // mksh buffers some heredocs (e.g. `cat <<'EOF'` in help.sh) through a real temp
                    // file rather than a pipe, and without $TMPDIR set it picks /data/local -- not
                    // writable by this app. Confirmed live: bare `help` failed with "can't create
                    // temporary file /data/local/....tmp: Permission denied" before this was set.
                    val tmpDir = File(homeDir, "tmp").apply { mkdirs() }
                    val env = arrayOf(
                        "TERM=xterm-256color",
                        "HOME=${homeDir.absolutePath}",
                        "TMPDIR=${tmpDir.absolutePath}",
                        "PATH=$pathPrefix/system/bin:/system/xbin:/vendor/bin",
                        "ENV=${rcFile.absolutePath}",
                        // Active from the shell's own first exec onward, so its own execve/execvp
                        // calls for user-typed commands go through our shim too, not just the shell.
                        "LD_PRELOAD=$execShimPath",
                        // Told to greyrecon-pkg.sh's do_fetch() so it knows where to reach PkgFetchServer.
                        "GREYRECON_PKG_FETCH_PORT=${PkgFetchServer.FIXED_PORT}",
                        // Told to ai.sh so it knows where to reach AgentBridgeServer.
                        "GREYRECON_AGENT_PORT=${AgentBridgeServer.FIXED_PORT}",
                    )
                    val newSession = TerminalSession(shellPath, homeDir.absolutePath, arrayOf(shellPath), env, null, client)
                    viewModel.session = newSession
                    view.attachSession(newSession)
                    if (prefilledCommand != null) freshSessionToPrefill = newSession
                }

                // Calling requestFocus() synchronously here is unreliable -- the view isn't
                // necessarily attached to the window yet when factory returns (confirmed live: focus
                // silently stayed on the TopAppBar's back button). view.post{} defers it until after
                // attachment, which is the standard fix for this exact Android focus-timing gotcha.
                view.post {
                    view.requestFocus()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    // No trailing newline -- lands in the shell's own line-editing buffer, visible on
                    // the prompt, not executed. Same mechanism TerminalView itself uses for paste
                    // (TerminalOutput.write(String)), just without the Enter the user hasn't pressed.
                    freshSessionToPrefill?.write(prefilledCommand)
                }
                view
            },
        )
    }
}
