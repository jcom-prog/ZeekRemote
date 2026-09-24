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
