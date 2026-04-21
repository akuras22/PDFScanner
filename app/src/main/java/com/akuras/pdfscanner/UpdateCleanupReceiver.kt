package com.akuras.pdfscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment

class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("PDFScanner-update.apk")
            ?.delete()
    }
}
