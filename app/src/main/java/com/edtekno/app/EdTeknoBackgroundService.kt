package com.edtekno.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class EdTeknoBackgroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val ALARM_NOTIFICATION_ID = 2002

        const val CHANNEL_PERSISTENT_ID = "tekno_hidden_bg_v6_channel"
        const val CHANNEL_EMERGENCY_ID = "tekno_emergency_v3_channel"
        const val CHANNEL_EMERGENCY_NO_VIB_ID = "tekno_emergency_v3_no_vib_channel"
        const val CHANNEL_CUSTOMER_ID = "tekno_customer_channel"
        const val CHANNEL_CUSTOMER_NO_VIB_ID = "tekno_customer_no_vib_channel"

        const val PREFS_NAME = "tekno_service_prefs"
        const val KEY_LAST_TICKET_ALERT_TIME = "last_ticket_alert_time"
        const val KEY_LAST_MONITORING_ALERT_TIME = "last_monitoring_alert_time"
        const val KEY_ALERTED_IDS = "alerted_notification_ids"
        const val KEY_LAST_ALERTED_TICKET_STATE = "last_alerted_ticket_state"
        const val KEY_LAST_ALERTED_SIGNAL_STATE = "last_alerted_signal_state"
        const val KEY_LAST_BILLING_ALERT_DATE = "last_billing_alert_date"
        const val KEY_LAST_BILLING_DUE_ALERT_MONTH = "last_billing_due_alert_month"
        const val KEY_DISMISSED_AT_PREFIX = "dismissed_at_"
        const val ACTION_DISMISS_NOTIFICATION = "com.edtekno.app.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_DISMISS_NOTIF_ID = "extra_dismiss_notif_id"
    }

    override fun onCreate() {
        super.onCreate()
        stopForeground(true)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TeknoGuard::BackgroundServiceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Hapus channel lama agar pengaturan publik/lockscreen lama terhapus dari sistem Android HP
            try {
                manager.deleteNotificationChannel("tekno_silent_bg_v4_channel")
                manager.deleteNotificationChannel("tekno_persistent_bg_v5_channel")
            } catch (_: Exception) {}

            // 1. Channel Foreground Service Hidden (Senyap total, disembunyikan dari lockscreen & status bar)
            val persistentChannel = NotificationChannel(
                CHANNEL_PERSISTENT_ID,
                "Layanan Latar Belakang",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Menjaga pemantauan jaringan & tiket tetap aktif"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(persistentChannel)

            // 2. Channel Notifikasi NOC (Menggunakan stream NOTIFICATION/RINGTONE, bukan jam weker/ALARM)
            val customAlarmSound = Uri.parse("android.resource://" + packageName + "/" + R.raw.tekno_alarm)
            val notifAudioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY_ID,
                "Peringatan Redaman & Tiket TeknoGuard",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Peringatan Redaman Kabel Optik & Tiket Baru Masuk (Getar)"
                setSound(customAlarmSound, notifAudioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600, 400, 1000)
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(emergencyChannel)

            val emergencyNoVibChannel = NotificationChannel(
                CHANNEL_EMERGENCY_NO_VIB_ID,
                "Peringatan Redaman & Tiket (Tanpa Getar)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Peringatan Redaman Kabel Optik & Tiket Baru Masuk (Tanpa Getar)"
                setSound(customAlarmSound, notifAudioAttr)
                enableVibration(false)
                vibrationPattern = null
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(emergencyNoVibChannel)

            // 3. Channel Ramah Pelanggan (TeknoCust)
            val defaultNotifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val customerAudioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val customerChannel = NotificationChannel(
                CHANNEL_CUSTOMER_ID,
                "Status Tiket Kendala",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pembaruan Status Laporan Kendala WiFi Pelanggan (Getar)"
                setSound(defaultNotifSound, customerAudioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                enableLights(true)
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(customerChannel)

            val customerNoVibChannel = NotificationChannel(
                CHANNEL_CUSTOMER_NO_VIB_ID,
                "Status Tiket Kendala (Tanpa Getar)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pembaruan Status Laporan Kendala WiFi Pelanggan (Tanpa Getar)"
                setSound(defaultNotifSound, customerAudioAttr)
                enableVibration(false)
                vibrationPattern = null
                enableLights(true)
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(customerNoVibChannel)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appTitle = if (BuildConfig.ROLE_TYPE == "KARYAWAN") "TeknoGuard" else "MyTekno"

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT_ID)
            .setContentTitle(appTitle)
            .setContentText("Pemantauan aktif di latar belakang")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun startPollingEngine() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (BuildConfig.ROLE_TYPE == "KARYAWAN") {
                        pollNotificationConfigAndTickets()
                        pollGuardMonitoringStatus()
                    } else {
                        pollCustTicketStatus()
                        pollCustBillingStatus()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(30000) // Polling berkala setiap 30 detik
            }
        }
    }

    private fun getAlertedIds(): HashSet<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val stringSet = prefs.getStringSet(KEY_ALERTED_IDS, emptySet()) ?: emptySet()
        return HashSet(stringSet)
    }

    private fun saveAlertedId(id: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentSet = getAlertedIds()
        currentSet.add(id)
        prefs.edit().putStringSet(KEY_ALERTED_IDS, currentSet).apply()
    }

    private fun pollNotificationConfigAndTickets() {
        val url = "https://portal.teknoindo.net/api/notifications/poll"
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://portal.teknoindo.net")

        val requestBuilder = Request.Builder().url(url)
        if (!cookieString.isNullOrEmpty()) {
            requestBuilder.addHeader("Cookie", cookieString)
        }
        val request = requestBuilder.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return
                    val json = JSONObject(bodyString)

                    // 1. Process Array Notifications Real-Time dari Web
                    val config = json.optJSONObject("notification_config")
                    val vibrationEnabled = config?.optBoolean("vibration_enabled", true) ?: true
                    val nightModeEnabled = config?.optBoolean("night_mode_enabled", false) ?: false
                    val nightModeStart = config?.optString("night_mode_start", "22:00") ?: "22:00"
                    val nightModeEnd = config?.optString("night_mode_end", "06:00") ?: "06:00"
                    // Ambil interval pemantauan & pengingat langsung dari database (default 5 menit)
                    val pollingIntervalMinutes = config?.optInt("polling_interval_minutes", 5) ?: 5
                    val ticketWaitingIntervalMinutes = config?.optInt("alert_waiting_interval_minutes", 5) ?: 5

                    val currentHourMinute = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val isQuietHours = nightModeEnabled && isInQuietHours(currentHourMinute, nightModeStart, nightModeEnd)

                    val notifArray = json.optJSONArray("notifications")
                    if (notifArray != null && notifArray.length() > 0) {
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        val lastMonitoringAlertTime = prefs.getLong(KEY_LAST_MONITORING_ALERT_TIME, 0L)
                        val now = System.currentTimeMillis()
                        val intervalMillis = pollingIntervalMinutes * 60 * 1000L

                        val alertedIds = getAlertedIds()
                        val customSound = Uri.parse("android.resource://" + packageName + "/" + R.raw.tekno_alarm)
                        val defaultNotifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

                        for (i in 0 until notifArray.length()) {
                            val item = notifArray.optJSONObject(i) ?: continue
                            val id = item.optString("id", "")
                            val title = item.optString("title", "Pemberitahuan")
                            val message = item.optString("message", "")
                            val level = item.optString("level", "info") // "critical", "warning", "info"
                            val type = item.optString("type", "")
                            val targetTab = item.optString("target_tab", "")
                            val targetFilter = item.optString("target_filter", "")

                            val isMonitoringAlert = type.contains("monitoring") || id.startsWith("batch_") || id.startsWith("log_")
                            val isCriticalOrLos = level == "critical" || level == "los" || type.contains("los")
                            val isTicketAlert = type.contains("tiket")

                            // SOP Filter Notifikasi: HANYA Kritis, LOS, dan Tiket yang dipush ke teknisi/admin. Warning & Optimal tidak dipush.
                            if (isMonitoringAlert && !isCriticalOrLos) {
                                continue
                            }

                            // Jika sedang jam tenang malam, bungkam notifikasi non-kritis (tiket / info)
                            if (isQuietHours && !isCriticalOrLos) {
                                continue
                            }

                            // Cek jika pengguna baru saja men-swipe / dismiss notifikasi ini, jangan munculkan lagi sebelum interval lewat
                            val lastDismissedAt = prefs.getLong("${KEY_DISMISSED_AT_PREFIX}$id", 0L)
                            if (lastDismissedAt > 0 && (now - lastDismissedAt < intervalMillis)) {
                                continue
                            }

                            // Cek debounce interval untuk notifikasi monitoring / redaman agar tidak spam tiap 1 menit
                            if (isMonitoringAlert && (now - lastMonitoringAlertTime < intervalMillis) && alertedIds.contains(id)) {
                                continue
                            }

                            if (id.isNotEmpty() && (!alertedIds.contains(id) || (now - lastMonitoringAlertTime >= intervalMillis))) {
                                val isCritical = isCriticalOrLos
                                val channelId = if (isCritical) {
                                    if (vibrationEnabled) CHANNEL_EMERGENCY_ID else CHANNEL_EMERGENCY_NO_VIB_ID
                                } else {
                                    if (vibrationEnabled) CHANNEL_CUSTOMER_ID else CHANNEL_CUSTOMER_NO_VIB_ID
                                }

                                val targetOffice = item.optString("kantor", "")
                                val targetStatus = item.optString("target_status", "")

                                val clickIntent = Intent(this, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra("target_tab", targetTab)
                                    putExtra("target_filter", targetFilter)
                                    putExtra("target_kantor", targetOffice)
                                    putExtra("target_status", targetStatus)
                                }
                                // Gunakan stable notification ID berbasis jenis dan kantor agar notifikasi yang diperbarui menggantikan yang lama (tidak duplikat)
                                val stableNotifId = if (id.startsWith("batch_")) {
                                    // Stabil per tipe batch & kantor, misal batch_los_karanggede -> stabilNotifId tetap sama walau jumlah pelanggan berubah
                                    val kantorField = item.optString("kantor", "")
                                    val batchType = if (id.contains("los")) "batch_los" else if (id.contains("critical")) "batch_critical" else if (id.contains("invalid")) "batch_invalid" else "batch_warning"
                                    "${batchType}_${kantorField.lowercase()}".hashCode()
                                } else if (id.startsWith("log_")) {
                                    // Ambil id pelanggan jika ada, atau gunakan id
                                    val itemPelanggan = item.optString("id_pelanggan", id)
                                    "cust_signal_$itemPelanggan".hashCode()
                                } else {
                                    id.hashCode()
                                }

                                val pendingIntent = PendingIntent.getActivity(
                                    this,
                                    stableNotifId,
                                    clickIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )

                                val deleteIntent = Intent(this, EdTeknoBackgroundService::class.java).apply {
                                    action = ACTION_DISMISS_NOTIFICATION
                                    putExtra(EXTRA_DISMISS_NOTIF_ID, id)
                                }
                                val deletePendingIntent = PendingIntent.getService(
                                    this,
                                    stableNotifId,
                                    deleteIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )

                                val builder = NotificationCompat.Builder(this, channelId)
                                    .setContentTitle(title)
                                    .setContentText(message)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentIntent(pendingIntent)
                                    .setDeleteIntent(deletePendingIntent)
                                    .setAutoCancel(true)
                                    .setPriority(NotificationCompat.PRIORITY_MAX)
                                    .setFullScreenIntent(pendingIntent, isCritical)
                                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                                // Sertakan logo besar resmi router TeknoIndo di samping notifikasi
                                try {
                                    val logoBitmap = android.graphics.BitmapFactory.decodeResource(
                                        resources,
                                        R.drawable.app_splash_logo
                                    )
                                    if (logoBitmap != null) {
                                        builder.setLargeIcon(logoBitmap)
                                    }
                                } catch (_: Exception) {}

                                // Terapkan getaran & bunyi mengikuti aliran RINGTONE/NOTIFICATION (Bukan Jam Weker/ALARM)
                                if (isCritical) {
                                    builder.setSound(customSound)
                                        .setLights(Color.RED, 500, 500)
                                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                                    if (vibrationEnabled) {
                                        builder.setVibrate(longArrayOf(0, 600, 200, 600, 200, 600, 400, 1000))
                                    } else {
                                        builder.setVibrate(null)
                                    }
                                } else {
                                    builder.setSound(defaultNotifSound)
                                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                    if (vibrationEnabled) {
                                        builder.setVibrate(longArrayOf(0, 300, 200, 300))
                                    } else {
                                        builder.setVibrate(null)
                                    }
                                }

                                manager.notify(stableNotifId, builder.build())
                                saveAlertedId(id)
                                alertedIds.add(id)

                                if (isMonitoringAlert) {
                                    prefs.edit().putLong(KEY_LAST_MONITORING_ALERT_TIME, now).apply()
                                }
                            }
                        }
                    }

                    // 2. Logic untuk tiket belum selesai (mengikuti interval alert_waiting_interval_minutes dari DB)
                    if (json.optString("status") == "success") {
                        val unreadCount = json.optInt("unread_tickets_count", 0)
                        val latestTicket = json.optJSONObject("latest_ticket")

                        if (unreadCount > 0 && latestTicket != null) {
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            val lastAlertTime = prefs.getLong(KEY_LAST_TICKET_ALERT_TIME, 0L)
                            val now = System.currentTimeMillis()

                            if (now - lastAlertTime >= (ticketWaitingIntervalMinutes * 60 * 1000L)) {
                                val ticketId = latestTicket.optString("id_tiket", "TKT-NEW")
                                val custName = latestTicket.optString("nama_pelanggan", "Warga")
                                val category = latestTicket.optString("kategori", "GANGGUAN")
                                val desc = latestTicket.optString("deskripsi", "Membutuhkan penanganan teknisi")

                                val ticketMsg = "[$ticketId] $custName ($category): $desc ($unreadCount tiket MENUNGGU)"

                                showEmergencyGuardNotification(
                                    message = ticketMsg,
                                    silentMode = isQuietHours,
                                    vibrateMode = vibrationEnabled
                                )

                                prefs.edit().putLong(KEY_LAST_TICKET_ALERT_TIME, now).apply()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isInQuietHours(nowStr: String, startStr: String, endStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val now = sdf.parse(nowStr) ?: return false
            val start = sdf.parse(startStr) ?: return false
            val end = sdf.parse(endStr) ?: return false

            if (start.after(end)) {
                now.after(start) || now.before(end) || now == start || now == end
            } else {
                (now.after(start) || now == start) && (now.before(end) || now == end)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun pollGuardMonitoringStatus() {
        // Data monitoring dan peringatan redaman telah diproses secara terintegrasi
        // dan didebounce di pollNotificationConfigAndTickets() via endpoint /api/notifications/poll.
    }

    private fun pollCustTicketStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 1. Polling Status Sinyal Pelanggan (Hanya muncul 1 kali saat status berubah ke LOS atau pulih)
        try {
            val dashUrl = "https://portal.teknoindo.net/portal/api/dashboard"
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookieManager.getCookie("https://portal.teknoindo.net")

            val dashReqBuilder = Request.Builder().url(dashUrl)
            if (!cookieString.isNullOrEmpty()) {
                dashReqBuilder.addHeader("Cookie", cookieString)
            }
            httpClient.newCall(dashReqBuilder.build()).execute().use { dashResp ->
                if (dashResp.isSuccessful) {
                    val dashBody = dashResp.body?.string() ?: ""
                    val dashJson = JSONObject(dashBody)
                    val lastLog = dashJson.optJSONObject("last_log")
                    val statusKoneksi = lastLog?.optString("status_koneksi", "")?.uppercase() ?: ""

                    val lastAlertedSignal = prefs.getString(KEY_LAST_ALERTED_SIGNAL_STATE, "") ?: ""

                    // Notifikasi Gangguan (LOS) & Notifikasi Pemulihan (Koneksi Kembali Pulih)
                    if (statusKoneksi == "LOS" && lastAlertedSignal != "LOS") {
                        prefs.edit().putString(KEY_LAST_ALERTED_SIGNAL_STATE, "LOS").apply()
                        showCustomerNotification(
                            "Pemberitahuan Gangguan Jaringan",
                            "Mohon maaf atas ketidaknyamanannya, koneksi internet Anda saat ini terdeteksi mengalami gangguan (LOS). Tim teknisi kami sedang segera menangani kendala ini."
                        )
                    } else if (statusKoneksi.isNotEmpty() && statusKoneksi != "LOS") {
                        // Jika sebelumnya LOS dan sekarang pulih ke status normal
                        if (lastAlertedSignal == "LOS") {
                            prefs.edit().putString(KEY_LAST_ALERTED_SIGNAL_STATE, statusKoneksi).apply()
                            showCustomerNotification(
                                "Koneksi Internet Telah Pulih",
                                "Koneksi internet Anda telah kembali normal dan aktif. Terima kasih atas kesabaran Anda telah menggunakan layanan kami!"
                            )
                        } else if (lastAlertedSignal.isEmpty()) {
                            // Inisialisasi awal tanpa spam notif
                            prefs.edit().putString(KEY_LAST_ALERTED_SIGNAL_STATE, statusKoneksi).apply()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore dashboard poll error
        }

        // 2. Polling Status Tiket Pelanggan (Hanya muncul 1 kali per update status tiket, tersimpan di SharedPreferences)
        val url = "https://portal.teknoindo.net/portal/api/kendala"
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://portal.teknoindo.net")

        val requestBuilder = Request.Builder().url(url)
        if (!cookieString.isNullOrEmpty()) {
            requestBuilder.addHeader("Cookie", cookieString)
        }
        val request = requestBuilder.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return
                    val json = JSONObject(bodyString)

                    val ticketsArray = json.optJSONArray("tickets")
                    if (ticketsArray != null && ticketsArray.length() > 0) {
                        val firstTicket = ticketsArray.optJSONObject(0)
                        val status = firstTicket?.optString("status", "") ?: ""
                        val idTiket = firstTicket?.optString("id_tiket", "") ?: ""
                        val deskripsi = firstTicket?.optString("deskripsi", "Laporan Kendala") ?: "Laporan Kendala"

                        val ticketStateKey = "$idTiket:$status"
                        val lastAlertedTicket = prefs.getString(KEY_LAST_ALERTED_TICKET_STATE, "") ?: ""

                        if (status.isNotEmpty() && ticketStateKey != lastAlertedTicket) {
                            prefs.edit().putString(KEY_LAST_ALERTED_TICKET_STATE, ticketStateKey).apply()
                            val (labelStatus, ticketStatusForMascot) = when (status) {
                                "MENUNGGU" -> Pair("Laporan Anda berhasil dikirim & menunggu penanganan tim NOC", "MENUNGGU")
                                "DICEK_ADMIN" -> Pair("Laporan Anda sedang diverifikasi admin NOC", "DICEK_ADMIN")
                                "DIPROSES" -> Pair("Teknisi sedang memproses perbaikan tiket Anda", "DIPROSES")
                                "SELESAI" -> Pair("Kendala WiFi Anda telah berhasil diselesaikan teknisi", "SELESAI")
                                else -> Pair("Status kendala diperbarui: $status", status)
                            }
                            showCustomerNotification("Tiket #$idTiket: $labelStatus", deskripsi, ticketStatusForMascot)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pollCustBillingStatus() {
        // Polling Notifikasi Pembayaran Tagihan:
        // 1. Notifikasi H-1 sebelum tanggal jatuh tempo (cukup 1 kali)
        // 2. Pada hari jatuh tempo dan setelahnya jika belum lunas: kirim notifikasi harian tepat pukul 10:00 WIB (1 kali sehari)
        try {
            val dashUrl = "https://portal.teknoindo.net/portal/api/dashboard"
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookieManager.getCookie("https://portal.teknoindo.net")

            val reqBuilder = Request.Builder().url(dashUrl)
            if (!cookieString.isNullOrEmpty()) {
                reqBuilder.addHeader("Cookie", cookieString)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return
                    val json = JSONObject(bodyString)
                    val customer = json.optJSONObject("customer") ?: return
                    val statusBayar = customer.optString("status_pembayaran", "LUNAS").uppercase()

                    // Jika sudah lunas, tidak perlu kirim notifikasi tagihan
                    if (statusBayar == "LUNAS") return

                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val nowCalendar = java.util.Calendar.getInstance()
                    val currentDay = nowCalendar.get(java.util.Calendar.DAY_OF_MONTH)
                    val currentHour = nowCalendar.get(java.util.Calendar.HOUR_OF_DAY)
                    val currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                    val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    // SOP Tagihan ISP: Standar jatuh tempo tanggal 20 tiap bulan
                    val dueDay = 20

                    val lastBillingAlertDate = prefs.getString(KEY_LAST_BILLING_ALERT_DATE, "") ?: ""
                    val lastDueAlertMonth = prefs.getString(KEY_LAST_BILLING_DUE_ALERT_MONTH, "") ?: ""

                    val periodeTagihan = customer.optString("periode_tagihan", "Bulan Ini")
                    val hargaPaket = customer.optDouble("harga_paket", 0.0)
                    val nominalStr = if (hargaPaket > 0) "Rp " + String.format(Locale.GERMANY, "%,d", hargaPaket.toLong()) else ""

                    // 1. Pengingat H-1 (Tanggal 19 atau dueDay - 1): Dikirim 1 kali per bulan
                    if (currentDay == (dueDay - 1)) {
                        if (lastDueAlertMonth != currentMonthStr) {
                            prefs.edit()
                                .putString(KEY_LAST_BILLING_DUE_ALERT_MONTH, currentMonthStr)
                                .putString(KEY_LAST_BILLING_ALERT_DATE, todayDateStr)
                                .apply()

                            val msg = "Tagihan internet $periodeTagihan ${if (nominalStr.isNotEmpty()) "sebesar $nominalStr " else ""}akan jatuh tempo besok (tanggal $dueDay). Mohon selesaikan pembayaran untuk kenyamanan akses internet Anda."
                            showCustomerNotification("Pengingat Tagihan Internet (H-1)", msg, "TAGIHAN")
                        }
                    }
                    // 2. Hari Jatuh Tempo (Tanggal 20) & Setelahnya jika Belum Bayar:
                    // Push notifikasi harian tepat pada pukul 10:00 WIB (dicek currentHour == 10 dan belum dikirim hari ini)
                    else if (currentDay >= dueDay) {
                        if (currentHour == 10 && lastBillingAlertDate != todayDateStr) {
                            prefs.edit().putString(KEY_LAST_BILLING_ALERT_DATE, todayDateStr).apply()

                            val title = if (currentDay == dueDay) "Hari Ini Jatuh Tempo Tagihan Internet" else "Tagihan Internet Belum Lunas"
                            val msg = if (currentDay == dueDay) {
                                "Hari ini adalah batas waktu pembayaran tagihan internet $periodeTagihan ${if (nominalStr.isNotEmpty()) "sebesar $nominalStr" else ""}. Segera lakukan pembayaran agar koneksi tetap lancar."
                            } else {
                                "Tagihan internet $periodeTagihan ${if (nominalStr.isNotEmpty()) "sebesar $nominalStr " else ""}telah melewati jatuh tempo. Mohon segera melunasi pembayaran untuk menghindari isolir jaringan."
                            }

                            showCustomerNotification(title, msg, "TAGIHAN")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore billing poll error
        }
    }

    private fun showEmergencyGuardNotification(
        message: String,
        silentMode: Boolean,
        vibrateMode: Boolean
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetChannelId = if (vibrateMode) CHANNEL_EMERGENCY_ID else CHANNEL_EMERGENCY_NO_VIB_ID
        val builder = NotificationCompat.Builder(this, targetChannelId)
            .setContentTitle("Pemberitahuan NOC")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setLights(Color.RED, 500, 500)

        // Sertakan logo router TeknoIndo di samping notifikasi
        try {
            val logoBitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.app_splash_logo
            )
            if (logoBitmap != null) {
                builder.setLargeIcon(logoBitmap)
            }
        } catch (_: Exception) {}

        if (!silentMode) {
            val customSound = Uri.parse("android.resource://" + packageName + "/" + R.raw.tekno_alarm)
            builder.setSound(customSound)
        } else {
            builder.setSound(null)
        }

        if (vibrateMode) {
            builder.setVibrate(longArrayOf(0, 600, 200, 600, 200, 600, 400, 1000))
        } else {
            builder.setVibrate(longArrayOf(0))
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIFICATION_ID, builder.build())
    }

    private fun showCustomerNotification(title: String, message: String, explicitStatus: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TARGET_PATH", "/portal")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Muat gambar Maskot MyTekno dinamis sesuai status tiket aduan / konteks pesan notifikasi
        val statusUpper = explicitStatus?.uppercase() ?: ""
        val textCombined = "$title $message".lowercase()

        val mascotResId = when {
            // 1. Status Tiket Aduan Eksplisit & Tagihan
            statusUpper == "MENUNGGU" -> R.drawable.mascot_customer
            statusUpper == "DICEK_ADMIN" || statusUpper == "ADMIN" -> R.drawable.mascot_admin
            statusUpper == "DIPROSES" || statusUpper == "TEKNISI" -> R.drawable.mascot_teknisi
            statusUpper == "SELESAI" -> R.drawable.mascot_berhasil
            statusUpper == "TAGIHAN" || statusUpper == "BAYAR" -> R.drawable.mascot_customer

            // 2. Berdasarkan Kata Kunci Konteks Notifikasi
            textCombined.contains("selesai") || textCombined.contains("normal") || textCombined.contains("pulih") || textCombined.contains("kembali lancar") || textCombined.contains("aktif kembali") || textCombined.contains("berhasil") -> {
                R.drawable.mascot_berhasil
            }
            textCombined.contains("teknisi") || textCombined.contains("lapangan") || textCombined.contains("diproses") -> {
                R.drawable.mascot_teknisi
            }
            textCombined.contains("admin") || textCombined.contains("verifikasi") || textCombined.contains("dicek") || textCombined.contains("cs") || textCombined.contains("layanan") -> {
                R.drawable.mascot_admin
            }
            textCombined.contains("kabel") || textCombined.contains("dropcore") || textCombined.contains("patchcord") || textCombined.contains("sambungan") -> {
                R.drawable.mascot_atur_kabel
            }
            textCombined.contains("los") || textCombined.contains("gangguan") || textCombined.contains("terputus") || textCombined.contains("kritis") -> {
                R.drawable.mascot_terkejut
            }
            textCombined.contains("menunggu") || textCombined.contains("laporan terkirim") -> {
                R.drawable.mascot_customer
            }
            else -> {
                R.drawable.mascot_customer
            }
        }

        val mascotBitmap: android.graphics.Bitmap? = try {
            android.graphics.BitmapFactory.decodeResource(resources, mascotResId)
        } catch (e: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_CUSTOMER_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(notifSound)
            .setVibrate(longArrayOf(0, 300, 200, 300))

        if (mascotBitmap != null) {
            builder.setLargeIcon(mascotBitmap)
        }

        val notification = builder.build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIFICATION_ID + 1, notification)
    }

    private fun showCustomerNotification(message: String) {
        showCustomerNotification("Info Layanan MyTekno", message)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopForeground(true)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }
}
