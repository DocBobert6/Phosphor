package com.phosphor.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startup + crash diagnostics, all visible in the Downloads folder:
 *
 * - Every launch writes `phosphor-started-<stamp>.txt`
 *   (proves the app process actually started).
 * - Any uncaught exception writes `phosphor-crash-<stamp>.txt`
 *   (full stack trace + device info + logcat tail).
 *
 * Primary target is Downloads via MediaStore; if that fails, falls back
 * to the app's external files dir. Installed in
 * [PhosphorApp.attachBaseContext], so even startup crashes are caught.
 */
object CrashReporter {

    fun install(context: Context) {
        // NOTE: do NOT use context.applicationContext here — inside
        // attachBaseContext() it can still return null (the system hasn't
        // linked the Application yet). The Application IS a Context and is
        // safe to hold. This null was silently swallowing every crash report.
        val appContext: Context = context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // never let the reporter itself crash the crash handler
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    /**
     * Checks how the PREVIOUS run ended and collects its diagnostics.
     * Returns the names of report files written to Downloads. Call once at
     * startup; show the user a notice if any were found.
     */
    fun checkPreviousRun(context: Context): List<String> {
        val reports = mutableListOf<String>()
        reportLastExit(context)?.let { reports.add(it) }
        reports.addAll(collectNativeCrashes(context))
        return reports
    }

    /**
     * Asks the system how the PREVIOUS process died (API 30+). This is the
     * definitive answer when there's no catchable exception: it distinguishes
     * a native crash from an ANR kill from low-memory kill. Writes
     * `phosphor-exit-<stamp>.txt` to Downloads when the last exit was abnormal.
     * Returns the filename written, or null.
     */
    private fun reportLastExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return null
            val reason = exit.reason
            if (reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                reason == ApplicationExitInfo.REASON_EXIT_SELF
            ) {
                return null // normal exit, nothing to report
            }
            val name = when (reason) {
                ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                else -> "CODE_$reason"
            }
            val text = buildString {
                appendLine("PHOSPHOR LAST-EXIT REPORT")
                appendLine("previous process exit reason: $name")
                appendLine("description: ${exit.description}")
                appendLine("died at: " + Date(exit.timestamp))
            }
            val filename = "phosphor-exit-${stamp()}.txt"
            writeToDownloads(context, filename, text)
            return filename
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Picks up native crash files written by the C++ signal handler
     * (see phosphor.cpp's crashHandler) and copies them to Downloads, where
     * the user can actually send them. Runs at startup — the crash it reports
     * is always from a previous process. Returns the copied filenames.
     */
    fun collectNativeCrashes(context: Context): List<String> {
        val copied = mutableListOf<String>()
        try {
            val dir = context.getExternalFilesDir(null) ?: return copied
            val files = dir.listFiles { f ->
                f.name.startsWith("native-crash-")
            } ?: return copied
            for (f in files) {
                try {
                    val name = "phosphor-${f.name}"
                    writeToDownloads(context, name, f.readText())
                    copied.add(name)
                } catch (_: Throwable) {
                } finally {
                    try {
                        f.delete()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return copied
    }

    private fun writeReport(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("PHOSPHOR CRASH REPORT")
        pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        pw.println("app: com.phosphor.player 1.0.0 (debug)")
        pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        pw.println("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println("thread: ${thread.name}")
        pw.println()
        pw.println("--- stack trace ---")
        var c: Throwable? = t
        var first = true
        while (c != null) {
            if (!first) pw.println("Caused by:")
            first = false
            pw.println(c.toString())
            for (el in c.stackTrace) pw.println("    at $el")
            c = c.cause
        }
        pw.println()
        pw.println("--- logcat (Phosphor tags) ---")
        pw.println(readLogcat())
        pw.flush()
        writeToDownloads(context, "phosphor-crash-${stamp()}.txt", sw.toString())
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * Downloads via MediaStore first; falls back to the app's external
     * files dir (always writable, no permission needed).
     */
    private fun writeToDownloads(context: Context, name: String, text: String) {
        val bytes = text.toByteArray()
        // 1) public Downloads via MediaStore
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("null output stream")
                return
            }
        } catch (_: Throwable) {
        }
        // 2) fallback: app-private external storage (no permission needed)
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(null)
                ?: return
            File(dir, name).writeBytes(bytes)
        } catch (_: Throwable) {
        }
    }

    /** Best-effort: an app can read its own logcat entries without permission. */
    private fun readLogcat(): String {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "400", "-v", "brief")
            )
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text.lines()
                .filter { it.contains("Phosphor") || it.contains("AndroidRuntime") }
                .takeLast(120)
                .joinToString("\n")
                .ifBlank { "(no matching logcat lines)" }
        } catch (_: Throwable) {
            "(logcat unavailable)"
        }
    }
}
