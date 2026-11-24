package com.edtekno.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TeknoFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TeknoFCM"
        const val CHANNEL_EMERGENCY_ID = "tekno_notif_v4_channel"
        const val CHANNEL_EMERGENCY_NO_VIB_ID = "tekno_notif_v4_no_vib_channel"
        const val CHANNEL_SILENT_NIGHT_ID = "tekno_silent_night_channel"
        const val CHANNEL_CUSTOMER_ID = "tekno_customer_channel"

        /**
         * Inisialisasi Channel Notifikasi Android 8.0+ (Oreo s/d Android 15)
         * Dipanggil saat startup aplikasi di MainActivity.onCreate() maupun saat pesan FCM diterima.
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val customAlarmSound = Uri.parse("android.resource://" + context.packageName + "/" + R.raw.tekno_alarm)
                val notifAudioAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                // 1. Channel Notifikasi TeknoGuard (Suara Kustom + Getar)
                val emergencyChannel = NotificationChannel(
                    CHANNEL_EMERGENCY_ID,
                    "Peringatan Redaman & Tiket TeknoGuard",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Peringatan Redaman Kabel Optik & Tiket Baru Masuk (Getar & Suara Notifikasi)"
                    setSound(customAlarmSound, notifAudioAttr)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    enableLights(true)
                    lightColor = Color.RED
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(emergencyChannel)

                // 2. Channel Notifikasi TeknoGuard (Suara Kustom Saja, TANPA Getar)
                val emergencyNoVibChannel = NotificationChannel(
                    CHANNEL_EMERGENCY_NO_VIB_ID,
                    "Peringatan TeknoGuard (Tanpa Getar)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Peringatan Redaman & Tiket (Hanya Suara Notifikasi, Tanpa Getar)"
                    setSound(customAlarmSound, notifAudioAttr)
                    enableVibration(false)
                    vibrationPattern = longArrayOf(0)
                    enableLights(true)
                    lightColor = Color.RED
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(emergencyNoVibChannel)

                // 3. Channel Jam Tenang (Senyap: Tanpa Suara & Tanpa Getar)
                val silentNightChannel = NotificationChannel(
                    CHANNEL_SILENT_NIGHT_ID,
                    "Pemberitahuan Jam Tenang",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Pemberitahuan Senyap Saat Jam Tenang Malam (Tanpa Suara & Tanpa Getar)"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(silentNightChannel)

                // 4. Channel Pelanggan / MyTekno
                val defaultNotifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val customerAudioAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val customerChannel = NotificationChannel(
                    CHANNEL_CUSTOMER_ID,
                    "Pemberitahuan Layanan",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Pembaruan Status Laporan Kendala & Informasi Pelanggan"
                    setSound(defaultNotifSound, customerAudioAttr)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    enableLights(true)
                    lightColor = Color.BLUE
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(customerChannel)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM Token: $token")
        // Simpan token di preferensi lokal dan kirim ke backend jika sesi login tersedia
        val prefs = getSharedPreferences("tekno_service_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_device_token", token).apply()
        sendRegistrationToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 1. Ekstrak data payload dari FCM
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "Pemberitahuan TeknoIndo"
        val message = data["message"] ?: remoteMessage.notification?.body ?: ""
        val targetTab = data["target_tab"] ?: ""
        val targetFilter = data["target_filter"] ?: ""
        val targetKantor = data["target_kantor"] ?: ""
        val targetStatus = data["target_status"] ?: ""
        val level = data["level"] ?: "info"
        val type = data["type"] ?: ""

        val isGuard = BuildConfig.ROLE_TYPE == "KARYAWAN"
        val soundPayload = data["sound"] ?: ""
        val vibrationPayload = data["vibration"] ?: ""
        val channelIdPayload = data["channel_id"] ?: ""

        // Untuk TeknoGuard NOC: CRITICAL, LOS, TIKET BARU, dan JADWAL TERJEDA seluruhnya memakai alarm tekno darurat
        val isEmergencyAlert = if (isGuard) {
            level == "critical" || level == "los" || type.contains("los") || type.contains("ticket") || type.contains("tiket") || type.contains("paused")
        } else {
            level == "critical" || level == "los"
        }

        val alertCategory = when {
            type.contains("los", ignoreCase = true) || title.contains("TERPUTUS", ignoreCase = true) || level == "los" -> "los"
            type.contains("crit", ignoreCase = true) || title.contains("KRITIS", ignoreCase = true) || level == "critical" -> "crit"
            type.contains("ticket", ignoreCase = true) || type.contains("tiket", ignoreCase = true) || title.contains("TIKET", ignoreCase = true) -> "ticket"
            type.contains("paused", ignoreCase = true) || title.contains("SCHEDULER", ignoreCase = true) -> "sched"
            else -> if (isEmergencyAlert) "emergency" else "info"
        }

        showFCMNotification(
            title = title,
            message = message,
            targetTab = targetTab,
            targetFilter = targetFilter,
            targetKantor = targetKantor,
            targetStatus = targetStatus,
            isCritical = isEmergencyAlert,
            isGuard = isGuard,
            alertCategory = alertCategory,
            soundPayload = soundPayload,
            vibrationPayload = vibrationPayload,
            channelIdPayload = channelIdPayload
        )
    }

    private fun showFCMNotification(
        title: String,
        message: String,
        targetTab: String,
        targetFilter: String,
        targetKantor: String,
        targetStatus: String,
        isCritical: Boolean,
        isGuard: Boolean,
        alertCategory: String = "info",
        soundPayload: String = "",
        vibrationPayload: String = "",
        channelIdPayload: String = ""
    ) {
        createNotificationChannels(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_tab", targetTab)
            putExtra("target_filter", targetFilter)
            putExtra("target_kantor", targetKantor)
            putExtra("target_status", targetStatus)
            if (!isGuard) {
                putExtra("TARGET_PATH", "/portal")
            }
        }

        val stableNotificationId = if (isGuard && targetKantor.isNotEmpty()) {
            "fcm_${alertCategory}_${targetKantor.lowercase()}".hashCode()
        } else if (isGuard) {
            "fcm_${alertCategory}_${title}".hashCode()
        } else {
            (title + message).hashCode()
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            stableNotificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isSilent = (channelIdPayload == CHANNEL_SILENT_NIGHT_ID || soundPayload == "none" || soundPayload == "silent")
        val hasVibration = (vibrationPayload != "false" && !isSilent)

        val channelId = if (channelIdPayload.isNotEmpty()) {
            channelIdPayload
        } else if (isSilent) {
            CHANNEL_SILENT_NIGHT_ID
        } else if (isCritical) {
            if (!hasVibration) CHANNEL_EMERGENCY_NO_VIB_ID else CHANNEL_EMERGENCY_ID
        } else {
            CHANNEL_CUSTOMER_ID
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_tekno_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Ikon Besar Logo Resmi Router TeknoIndo (TeknoGuard) atau Maskot (MyTekno)
        try {
            if (isGuard) {
                val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.app_splash_logo)
                if (logoBitmap != null) {
                    builder.setLargeIcon(logoBitmap)
                }
            } else {
                val mascotBitmap = BitmapFactory.decodeResource(resources, R.drawable.mascot_customer)
                if (mascotBitmap != null) {
                    builder.setLargeIcon(mascotBitmap)
                }
            }
        } catch (_: Exception) {}

        if (isSilent) {
            builder.setSound(null)
                .setVibrate(longArrayOf(0))
        } else if (isCritical) {
            val customAlarmSound = Uri.parse("android.resource://" + packageName + "/" + R.raw.tekno_alarm)
            builder.setSound(customAlarmSound)
                .setLights(Color.RED, 500, 500)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            if (hasVibration) {
                builder.setVibrate(longArrayOf(0, 400, 200, 400))
            } else {
                builder.setVibrate(longArrayOf(0))
            }
        } else {
            val defaultNotifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultNotifSound)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            if (hasVibration) {
                builder.setVibrate(longArrayOf(0, 300, 200, 300))
            } else {
                builder.setVibrate(longArrayOf(0))
            }
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(stableNotificationId, builder.build())
    }

    private fun sendRegistrationToServer(token: String) {
        Thread {
            try {
                val isGuard = BuildConfig.ROLE_TYPE == "KARYAWAN"
                val registerUrl = if (isGuard) {
                    "https://portal.teknoindo.net/api/notifications/register-fcm"
                } else {
                    "https://portal.teknoindo.net/portal/api/auth/register-fcm"
                }

                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookieString = cookieManager.getCookie("https://portal.teknoindo.net")

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val jsonPayload = JSONObject().apply {
                    put("fcm_token", token)
                }

                val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                val requestBuilder = Request.Builder()
                    .url(registerUrl)
                    .post(body)

                if (!cookieString.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", cookieString)
                }

                client.newCall(requestBuilder.build()).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal sinkron token FCM ke server (mungkin belum login): ${e.message}")
            }
        }.start()
    }
}

