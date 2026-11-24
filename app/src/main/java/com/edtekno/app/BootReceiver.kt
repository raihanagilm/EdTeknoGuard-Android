package com.edtekno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver: Tidak perlu memicu Foreground Service lagi karena sistem
 * 100% menggunakan Google Firebase Cloud Messaging (FCM) murni ala WhatsApp/Telegram.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // FCM otomatis ditangani oleh Google Play Services OS secara pasif.
    }
}
