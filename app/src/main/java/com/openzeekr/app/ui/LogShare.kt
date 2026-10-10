package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.openzeekr.app.util.LogCrypto
import com.openzeekr.app.util.Logx
import java.io.File

/** Shares only an encrypted log file; plaintext is never exposed on failure. */
fun shareEncryptedLog(ctx: Context): String {
    val blob = LogCrypto.encryptToBase64(Logx.dump()) ?: return "Share failed - nothing shared."
    return runCatching {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "zeekremote-log-$stamp.txt").apply { writeText(blob) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ZeekRemote encrypted log $stamp")
            putExtra(Intent.EXTRA_TEXT, "ZeekRemote encrypted diagnostic log attached.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send encrypted log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Encrypted log ready to send - pick your email app."
    }.getOrElse { "Share failed: ${it.message}" }
}

/**
 * "Deel diagnose": the event journal plus the filtered app log as a readable .txt via the share
 * sheet (no PC needed). The app log comes from this app's own logcat (hours) and falls back to the
 * in-memory buffer (500 lines). Everything passes [com.openzeekr.app.util.DiagnosticExport]: no VIN,
 * tokens, keys, raw BLE data or positions.
 */
suspend fun shareDiagnostics(ctx: Context): String = runCatching {
    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val journalFile = File(ctx.filesDir, "proximity-diagnostics.log")
        val journal = listOf(File(journalFile.path + ".previous"), journalFile)
            .filter { it.exists() }.joinToString("") { it.readText() }
        val fromLogcat = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-s", "openzeekr:V"))
            p.inputStream.bufferedReader().readLines().also { p.waitFor() }
        }.getOrDefault(emptyList()).let { com.openzeekr.app.util.DiagnosticExport.fromLogcat(it) }
        val appLog = fromLogcat.ifEmpty { Logx.lines.value }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val now = java.util.Date()
        val text = com.openzeekr.app.util.DiagnosticExport.build(version,
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(now), journal, appLog)
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(now)
        File(dir, "zeekremote-diagnose-$stamp.txt").apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ZeekRemote diagnose ${file.nameWithoutExtension.removePrefix("zeekremote-diagnose-")}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Deel diagnose").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "Diagnose klaar om te delen (${file.name})."
}.getOrElse { "Delen mislukt: ${it.message ?: it.javaClass.simpleName}" }
