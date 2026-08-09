package tech.ula

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches any otherwise-fatal exception and writes it to a file the user can
 * actually reach, then lets Android crash as normal.
 *
 * Android's logcat is only readable by the app that wrote it (or via `adb` from
 * a computer), which makes remote debugging on a phone almost impossible. This
 * handler mirrors the crash into external storage so the stack trace can simply
 * be opened in a file manager and shared.
 *
 * Registered in AndroidManifest.xml via android:name=".UlaApplication".
 */
class UlaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrashReport(thread, error)
            } catch (ignored: Throwable) {
                // Never let the crash reporter itself mask the real crash.
            }
            // Hand back to Android so behaviour is otherwise unchanged.
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("UserLAnd Terminal crash report")
            appendLine("==============================")
            appendLine("Time:      $timestamp")
            appendLine("App:       ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}")
            appendLine("Device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI:       ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread:    ${thread.name}")
            appendLine()
            appendLine("Exception: ${error.javaClass.name}")
            appendLine("Message:   ${error.message}")
            appendLine()
            appendLine("Stack trace")
            appendLine("-----------")
            append(stackTrace)
        }

        // Write everywhere plausible; the first location that works is enough.
        val targets = listOfNotNull(
                getExternalFilesDir(null)?.let { File(it, "userland-crash.txt") },
                File(filesDir, "userland-crash.txt")
        )

        for (target in targets) {
            try {
                target.parentFile?.mkdirs()
                target.writeText(report)
            } catch (ignored: Throwable) {
                // Try the next location.
            }
        }
    }
}
