package com.example.tiny_computer.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.tiny_computer.Signal9Activity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Pengganti TermPty (xterm + flutter_pty): satu shell `/system/bin/sh` per kontainer, tempat
 * perintah boot proot ditulis. Logika terminal Tiny Computer tetap sama; hanya mesinnya
 * berganti ke Termux terminal-emulator.
 */
class TermSession(private val context: Context) : TerminalSessionClient {
    @Volatile var view: TerminalView? = null
    @Volatile var exitCode: Int? = null
    @Volatile var onExit: ((Int) -> Unit)? = null

    val session: TerminalSession = run {
        val env = ArrayList<String>()
        System.getenv().forEach { (k, v) -> if (k != "TERM") env.add("$k=$v") }
        env.add("TERM=xterm-256color")
        env.add("COLORTERM=truecolor")
        TerminalSession(
            "/system/bin/sh", TinyEnv.dataPath, arrayOf("/system/bin/sh"),
            env.toTypedArray(), Cfg.termMaxLines.coerceIn(100, 50000), this,
        )
    }

    fun write(text: String) {
        session.write(text)
    }

    /** `Util.termWrite`: tulis satu baris perintah. */
    fun writeLine(text: String) = session.write("$text\n")

    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) {
        val code = finishedSession.exitStatus
        exitCode = code
        view?.onScreenUpdated()
        // sinyal -9 (batas proses anak Android 12+): tampilkan halaman petunjuk, seperti launchSignal9Page
        if (code == -9) {
            context.startActivity(Intent(context, Signal9Activity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        onExit?.invoke(code)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        if (clip != null && clip.itemCount > 0) session.write(clip.getItemAt(0).coerceToText(context).toString())
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(changedSession: TerminalSession) { view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.setTerminalCursorBlinkerRate(if (state) 600 else 0)
    }
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}

/** Ctrl/Alt/Shift lengket seperti `VirtualKeyboard` di Dart. */
object TermModifiers {
    @Volatile var ctrl = false
    @Volatile var alt = false
    @Volatile var shift = false

    fun releaseIfNotSticky() {
        if (!Cfg.isStickyKey) { ctrl = false; alt = false; shift = false }
    }
}

class TermViewClient(
    private val onTap: () -> Unit,
    private val onScaleChange: (Float) -> Unit,
    private val onModifiersChanged: () -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        onScaleChange(scale)
        return 1f
    }
    override fun onSingleTapUp(e: MotionEvent) = onTap()
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = TermModifiers.ctrl.also { if (it) { TermModifiers.releaseIfNotSticky(); onModifiersChanged() } }
    override fun readAltKey(): Boolean = TermModifiers.alt
    override fun readShiftKey(): Boolean = TermModifiers.shift
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}

/**
 * Sesi terminal per kontainer (`G.termPtys`). TerminalSession dari Termux membuat Handler di thread
 * pembuatnya, jadi harus dibuat di thread utama; proses shell juga baru jalan setelah ukuran
 * emulator diketahui, maka diberi ukuran awal 80x24 agar perintah boot yang ditulis sebelum
 * halaman Terminal dibuka tidak hilang (view menyesuaikan ukurannya begitu terpasang).
 */
object TermSessions {
    private val map = HashMap<Int, TermSession>()

    fun forCurrent(context: Context): TermSession {
        val idx = TinyEnv.currentContainer
        synchronized(map) { map[idx] }?.let { return it }
        val app = context.applicationContext
        val create = { TermSession(app).also { it.session.updateSize(80, 24) } }
        val made = if (Looper.myLooper() == Looper.getMainLooper()) create() else {
            val box = arrayOfNulls<TermSession>(1)
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { box[0] = create(); latch.countDown() }
            latch.await()
            box[0]!!
        }
        return synchronized(map) { map.getOrPut(idx) { made } }
    }

    fun peek(): TermSession? = synchronized(map) { map[TinyEnv.currentContainer] }
}
