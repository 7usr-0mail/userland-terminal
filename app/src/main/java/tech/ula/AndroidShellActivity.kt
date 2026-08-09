package tech.ula

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import tech.ula.utils.AndroidShellLauncher
import tech.ula.utils.UlaFiles

/**
 * An in-app terminal attached to a shell running directly on the Android device.
 *
 * Unlike the distribution sessions, nothing here goes through proot and no root
 * filesystem is downloaded. The shell is /system/bin/sh executing with the app's
 * own sandbox privileges, or as root when the device provides a working `su`.
 * The bundled busybox is placed at the front of PATH so GNU-style utilities are
 * available alongside Android's toybox.
 */
class AndroidShellActivity : AppCompatActivity(), TerminalSession.SessionChangedCallback {

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null
    private val localFilesystemId by lazy { intent.getLongExtra("localFilesystemId", -1) }
    private val localUsername by lazy { intent.getStringExtra("localUsername") ?: "user" }
    private var localStartedAt = 0L
    private val localTempTrace by lazy { java.io.File(filesDir, "direct-session-$localFilesystemId.temp") }

    private val ulaFiles by lazy { UlaFiles(this, this.applicationInfo.nativeLibraryDir) }
    private val launcher by lazy { AndroidShellLauncher(this, ulaFiles) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_android_shell)

        title = getString(R.string.android_shell_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        terminalView = findViewById(R.id.android_terminal_view)
        terminalView.setTextSize(resources.getDimensionPixelSize(R.dimen.terminal_text_size))
        terminalView.requestFocus()
        terminalView.setOnKeyListener(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale

            override fun onSingleTapUp(e: MotionEvent?) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false

            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, currentSession: TerminalSession?): Boolean = false

            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

            override fun readControlKey(): Boolean = false

            override fun readAltKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, currentSession: TerminalSession?): Boolean = false

            override fun onLongPress(event: MotionEvent?): Boolean = false
        })

        findViewById<android.view.View>(R.id.key_esc).setOnClickListener { session?.write("\u001b") }
        findViewById<android.view.View>(R.id.key_tab).setOnClickListener { session?.write("\t") }
        findViewById<android.view.View>(R.id.key_left).setOnClickListener { session?.write("\u001b[D") }
        findViewById<android.view.View>(R.id.key_up).setOnClickListener { session?.write("\u001b[A") }
        findViewById<android.view.View>(R.id.key_down).setOnClickListener { session?.write("\u001b[B") }
        findViewById<android.view.View>(R.id.key_right).setOnClickListener { session?.write("\u001b[C") }
        startShell()
    }

    private fun startShell() {
        if (localFilesystemId >= 0) LocalSessionTrace.append(this, "LOCAL launch fs=$localFilesystemId user=$localUsername")
        val result = if (localFilesystemId >= 0) launcher.createProotSession(this, localFilesystemId, localUsername)
                else launcher.createSession(this)
        if (result == null) {
            if (localFilesystemId >= 0) LocalSessionTrace.append(this, "LOCAL launch returned null")
            AlertDialog.Builder(this)
                    .setTitle(R.string.android_shell_failed_title)
                    .setMessage(R.string.android_shell_failed_message)
                    .setPositiveButton(R.string.button_ok) { _, _ -> finish() }
                    .show()
            return
        }
        session = result
        if (localFilesystemId >= 0) {
            localStartedAt = System.currentTimeMillis()
            localTempTrace.writeText("[direct session capture started]\n")
        }
        terminalView.attachSession(result)
        if (launcher.usingRoot) {
            Toast.makeText(this, R.string.android_shell_root_active, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.android_shell_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.menu_shell_reset -> {
                session?.reset()
                return true
            }
            R.id.menu_shell_restart -> {
                session?.finishIfRunning()
                startShell()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // TerminalSession.SessionChangedCallback
    override fun onTextChanged(changedSession: TerminalSession) {
        if (localFilesystemId >= 0 && System.currentTimeMillis() - localStartedAt <= 10_000L) {
            try { localTempTrace.writeText(changedSession.emulator.screen.transcriptText) } catch (_: Exception) {}
        }
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        // The shell may set a title via escape sequence; keep our own.
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (localFilesystemId >= 0) {
            val elapsed = System.currentTimeMillis() - localStartedAt
            try { localTempTrace.writeText(finishedSession.emulator.screen.transcriptText) } catch (_: Exception) {}
            LocalSessionTrace.append(this, "LOCAL session ended exit=${finishedSession.exitStatus} elapsed=${elapsed}ms")
            if (elapsed <= 10_000L || finishedSession.exitStatus != 0) {
                val captured = try { localTempTrace.readText() } catch (_: Exception) { "[no terminal capture]" }
                LocalSessionTrace.append(this, "LOCAL EARLY OUTPUT:\n$captured")
            }
            localTempTrace.delete()
        }
        runOnUiThread {
            Toast.makeText(this, R.string.android_shell_exited, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onClipboardText(session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", text))
    }

    override fun onBell(session: TerminalSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onColorsChanged(session: TerminalSession) {
        // Colors are fixed by the matrix theme.
    }

    override fun onDestroy() {
        if (localFilesystemId >= 0 && session?.isRunning == true) {
            val captured = try { localTempTrace.readText() } catch (_: Exception) { "[no terminal capture]" }
            LocalSessionTrace.append(this, "LOCAL destroyed while running; output:\n$captured")
            localTempTrace.delete()
        }
        session?.finishIfRunning()
        super.onDestroy()
    }
}
