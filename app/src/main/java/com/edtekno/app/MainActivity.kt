package com.edtekno.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.edtekno.app.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Handling Callback Geolocation GPS
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null

    // Handling File Upload Kamera & Galeri
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Tracker tombol back double-tap
    private var backPressedTime: Long = 0

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            var results: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                if (result.data?.data != null) {
                    results = arrayOf(result.data!!.data!!)
                } else if (cameraImageUri != null) {
                    results = arrayOf(cameraImageUri!!)
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, locationGranted, false)
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set status bar & splash screen:
        // Khusus TeknoGuard (KARYAWAN): status bar putih bersih dengan ikon gelap agar menyatu dengan header putih
        // Khusus MyTekno (PELANGGAN): status bar biru (#2563EB) dengan ikon terang
        if (BuildConfig.ROLE_TYPE == "KARYAWAN") {
            window.statusBarColor = android.graphics.Color.WHITE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } else {
            window.statusBarColor = android.graphics.Color.parseColor("#2563EB")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        setupCookieManager()
        setupSplashScreen()
        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        setupErrorHandling()

        requestSystemPermissions()
        TeknoFirebaseMessagingService.createNotificationChannels(this)
        initFirebaseMessaging()

        handleIntentUrl(intent)
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val targetTab = intent?.getStringExtra("target_tab")
        val targetFilter = intent?.getStringExtra("target_filter") ?: ""
        val targetKantor = intent?.getStringExtra("target_kantor") ?: ""
        val targetStatus = intent?.getStringExtra("target_status") ?: ""
        if (!targetTab.isNullOrEmpty()) {
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "window.navigateToTab && window.navigateToTab('$targetTab', '$targetFilter', '$targetKantor', '$targetStatus')",
                    null
                )
            }
        }
    }

    private fun handleIntentUrl(intent: Intent?) {
        val targetPath = intent?.getStringExtra("TARGET_PATH")
        val finalUrl = if (!targetPath.isNullOrEmpty()) {
            "https://portal.teknoindo.net$targetPath"
        } else if (BuildConfig.ROLE_TYPE == "KARYAWAN") {
            "https://portal.teknoindo.net/"
        } else {
            BuildConfig.BASE_URL
        }
        binding.webView.loadUrl(finalUrl)
    }

    private fun setupCookieManager() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)
        cookieManager.flush()
    }

    private var isSplashDismissed = false

    private fun setupSplashScreen() {
        val isGuard = BuildConfig.ROLE_TYPE == "KARYAWAN"
        val appTitle = if (isGuard) "TeknoGuard" else "MyTekno"
        val appSubtitle = if (isGuard) {
            "Sistem Monitoring Redaman ONT & NOC"
        } else {
            "Portal Layanan Mandiri Pelanggan"
        }
        binding.tvSplashTitle.text = appTitle
        binding.tvSplashSubtitle.text = appSubtitle

        // Khusus TeknoGuard: Background splash layar putih bersih
        if (isGuard) {
            binding.layoutSplash.setBackgroundColor(android.graphics.Color.WHITE)
            binding.tvSplashTitle.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            binding.tvSplashSubtitle.setTextColor(android.graphics.Color.parseColor("#475569"))
            binding.progressSplash.indeterminateTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
        }

        // Safety fallback: jika webView membutuhkan waktu lama, hilangkan splash otomatis setelah 4 detik
        binding.layoutSplash.postDelayed({
            dismissSplashScreen()
        }, 4000)
    }

    private fun dismissSplashScreen() {
        if (!isSplashDismissed) {
            isSplashDismissed = true
            binding.layoutSplash.animate()
                .alpha(0f)
                .setDuration(350)
                .withEndAction {
                    binding.layoutSplash.visibility = View.GONE
                }
                .start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setGeolocationEnabled(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true

        val roleUA = if (BuildConfig.ROLE_TYPE == "KARYAWAN") "TeknoGuardMobile/1.0" else "MyTeknoMobile/1.0"
        settings.userAgentString = "${settings.userAgentString} $roleUA"

        // Hubungkan Native Android Bridge agar web dapat memicu Notifikasi OS langsung
        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)

                if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeolocationOrigin = origin
                    pendingGeolocationCallback = callback
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                val appName = getString(R.string.app_name)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(appName)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                val appName = getString(R.string.app_name)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(appName)
                    .setMessage(message)
                    .setPositiveButton("Ya") { _, _ -> result?.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                try {
                    val photoFile = File.createTempFile("photo_", ".jpg", externalCacheDir)
                    cameraImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        photoFile
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                } catch (e: Exception) {
                    cameraImageUri = null
                }

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Pilih Gambar atau Kamera")
                    if (cameraImageUri != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.layoutError.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefreshLayout.isRefreshing = false
                isWebAtTop = true
                binding.swipeRefreshLayout.isEnabled = true
                CookieManager.getInstance().flush()
                handleNotificationIntent(intent)
                dismissSplashScreen()

                // Sinkronkan ulang token FCM setelah halaman selesai dimuat dan cookie sesi login aktif
                val prefs = getSharedPreferences("tekno_service_prefs", Context.MODE_PRIVATE)
                val savedToken = prefs.getString("fcm_device_token", null)
                if (!savedToken.isNullOrEmpty()) {
                    syncFcmTokenToServer(savedToken)
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && error?.errorCode != -1) {
                    binding.webView.visibility = View.GONE
                    binding.layoutError.visibility = View.VISIBLE
                    binding.swipeRefreshLayout.isRefreshing = false
                    dismissSplashScreen()
                }
            }
        }
    }

    private var isWebAtTop: Boolean = true

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary, R.color.accent)
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }
        // Pastikan SwipeRefreshLayout HANYA aktif jika konten benar-benar berada di puncak (isWebAtTop == true)
        // dan WebView tidak sedang di-scroll vertikal.
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            // Mengembalikan true artinya child view masih bisa di-scroll ke atas (sehingga refresh TIDAK dipicu)
            !isWebAtTop || binding.webView.canScrollVertically(-1)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        finish()
                    } else {
                        Toast.makeText(baseContext, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                    }
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })
    }

    private fun setupErrorHandling() {
        binding.btnRetry.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.loadUrl(BuildConfig.BASE_URL)
        }
    }

    private fun requestSystemPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsToRequest.add(Manifest.permission.CAMERA)

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun initFirebaseMessaging() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                if (!token.isNullOrEmpty()) {
                    val prefs = getSharedPreferences("tekno_service_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("fcm_device_token", token).apply()
                    syncFcmTokenToServer(token)
                }
            }
        }
    }

    private fun syncFcmTokenToServer(token: String) {
        Thread {
            try {
                val isGuard = BuildConfig.ROLE_TYPE == "KARYAWAN"
                val registerUrl = if (isGuard) {
                    "https://portal.teknoindo.net/api/notifications/register-fcm"
                } else {
                    "https://portal.teknoindo.net/portal/api/auth/register-fcm"
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.flush()
                val cookieString = cookieManager.getCookie(registerUrl)
                    ?: cookieManager.getCookie("https://portal.teknoindo.net")
                    ?: cookieManager.getCookie("https://portal.teknoindo.net/portal")

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val jsonPayload = org.json.JSONObject().apply {
                    put("fcm_token", token)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = jsonPayload.toString().toRequestBody(mediaType)
                val requestBuilder = okhttp3.Request.Builder()
                    .url(registerUrl)
                    .post(body)

                if (!cookieString.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", cookieString)
                }

                client.newCall(requestBuilder.build()).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    private fun startBackgroundService() {
        // Dinonaktifkan total: Sistem sekarang 100% menggunakan FCM murni seperti WhatsApp / Telegram
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    /**
     * Native JavaScript Interface Bridge
     * Memungkinkan antarmuka web memicu:
     * 1. Heads-Up Notification Native (OS)
     * 2. In-App Floating Pop-up Card / Banner
     * 3. System Confirmation Dialog Box (Yes/No Callback ke JS)
     */
    inner class WebAppInterface(private val activity: Activity, private val webView: WebView) {

        // 1. Heads-Up Notification (Melayang dari atas layar HP)
        @JavascriptInterface
        fun showNativeNotification(title: String, message: String, isEmergency: Boolean) {
            val channelId: String = if (isEmergency) {
                TeknoFirebaseMessagingService.CHANNEL_EMERGENCY_ID
            } else {
                TeknoFirebaseMessagingService.CHANNEL_CUSTOMER_ID
            }
            val builder = NotificationCompat.Builder(activity as Context, channelId)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            // Untuk varian pelanggan (MyTekno) tampilkan avatar maskot dinamis,
            // Untuk varian NOC/Teknisi (TeknoGuard) tampilkan logo router resmi TeknoIndo
            try {
                if (!isEmergency && BuildConfig.ROLE_TYPE != "KARYAWAN") {
                    val textCombined = "$title $message".lowercase()
                    val mascotResId = when {
                        // 1. Status Selesai / Internet Pulih
                        textCombined.contains("selesai") || textCombined.contains("normal") || textCombined.contains("pulih") || textCombined.contains("kembali lancar") || textCombined.contains("aktif kembali") || textCombined.contains("berhasil") -> {
                            R.drawable.mascot_berhasil
                        }
                        // 2. Status Ditangani Teknisi
                        textCombined.contains("teknisi") || textCombined.contains("lapangan") || textCombined.contains("diproses") -> {
                            R.drawable.mascot_teknisi
                        }
                        // 3. Status Ditangani Admin
                        textCombined.contains("admin") || textCombined.contains("verifikasi") || textCombined.contains("dicek") || textCombined.contains("cs") || textCombined.contains("layanan") -> {
                            R.drawable.mascot_admin
                        }
                        // 4. Perbaikan Kabel Fisik
                        textCombined.contains("kabel") || textCombined.contains("dropcore") || textCombined.contains("patchcord") || textCombined.contains("sambungan") -> {
                            R.drawable.mascot_atur_kabel
                        }
                        // 5. Gangguan Jaringan / LOS
                        textCombined.contains("los") || textCombined.contains("gangguan") || textCombined.contains("terputus") || textCombined.contains("kritis") -> {
                            R.drawable.mascot_terkejut
                        }
                        // 6. Menunggu / Pelanggan Umum
                        textCombined.contains("menunggu") || textCombined.contains("laporan terkirim") -> {
                            R.drawable.mascot_customer
                        }
                        else -> {
                            R.drawable.mascot_customer
                        }
                    }

                    val mascotBitmap = android.graphics.BitmapFactory.decodeResource(
                        activity.resources,
                        mascotResId
                    )
                    if (mascotBitmap != null) {
                        builder.setLargeIcon(mascotBitmap)
                    }
                } else {
                    // Logo resmi router TeknoIndo untuk TeknoGuard
                    val logoBitmap = android.graphics.BitmapFactory.decodeResource(
                        activity.resources,
                        R.drawable.app_splash_logo
                    )
                    if (logoBitmap != null) {
                        builder.setLargeIcon(logoBitmap)
                    }
                }
            } catch (e: Exception) {
                // Ignore decoding error
            }

            val notification = builder.build()
            val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Gunakan ID deterministik berdasarkan hash teks judul agar menggantikan notifikasi lama dari kategori/kantor yang sama
            val stableNotificationId = title.hashCode()
            manager.notify(stableNotificationId, notification)
        }

        // 2. Pop-up Notifikasi In-App (Jendela/Kotak Pop-up di dalam layar HP)
        @JavascriptInterface
        fun showInAppPopup(title: String, message: String) {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(R.mipmap.ic_launcher)
                    .setPositiveButton("Tutup", DialogInterface.OnClickListener { dialog, _ ->
                        dialog.dismiss()
                    })
                    .show()
            }
        }

        // 3. Dialog Box / Kotak Konfirmasi (Pilihan Aksi Yes / No atau Batal / Lanjut)
        @JavascriptInterface
        fun showConfirmDialog(title: String, message: String, callbackId: String) {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(R.mipmap.ic_launcher)
                    .setPositiveButton("Ya / Lanjutkan", DialogInterface.OnClickListener { dialog, _ ->
                        webView.evaluateJavascript("window.onDialogResult('$callbackId', true)", null)
                        dialog.dismiss()
                    })
                    .setNegativeButton("Batal", DialogInterface.OnClickListener { dialog, _ ->
                        webView.evaluateJavascript("window.onDialogResult('$callbackId', false)", null)
                        dialog.dismiss()
                    })
                    .setCancelable(false)
                    .show()
            }
        }

        // 4. Sinkronisasi Posisi Scroll dari Konten Web Internal
        @JavascriptInterface
        fun setScrollAtTop(atTop: Boolean) {
            activity.runOnUiThread {
                isWebAtTop = atTop
                binding.swipeRefreshLayout.isEnabled = atTop
            }
        }

        // 5. Ambil Token FCM Terkini untuk Sinkronisasi ke Web / Database
        @JavascriptInterface
        fun getFcmToken(): String? {
            val prefs = activity.getSharedPreferences("tekno_service_prefs", Context.MODE_PRIVATE)
            return prefs.getString("fcm_device_token", null)
        }

        // 6. Notifikasi bahwa Pengguna Berhasil Login (Memicu Sinkronisasi Token dengan Sesi Baru)
        @JavascriptInterface
        fun notifyLoginSuccess() {
            val prefs = activity.getSharedPreferences("tekno_service_prefs", Context.MODE_PRIVATE)
            val savedToken = prefs.getString("fcm_device_token", null)
            if (!savedToken.isNullOrEmpty()) {
                syncFcmTokenToServer(savedToken)
            }
        }
    }
}
