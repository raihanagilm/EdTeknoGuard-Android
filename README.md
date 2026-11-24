# EdTeknoGuard Mobile (Android APK & Native Service)

Aplikasi Android Native WebView Wrapper & Background Polling Engine untuk sistem **TeknoGuard** (Karyawan/NOC) dan **MyTekno** (Portal Mandiri Pelanggan/Warga, sebelumnya TeknoCust).

Untuk panduan lengkap arsitektur dan prompt instruksi Android Studio, lihat **[PROMPT_ANDROID_STUDIO.md](./PROMPT_ANDROID_STUDIO.md)**.

## Spesifikasi:
- **Base URL TeknoGuard:** `http://172.21.20.62:7890/login`
- **Base URL MyTekno:** `http://172.21.20.62:7890/portal`
- **Minimum SDK:** Android 7.0 (API 24)
- **Compile / Target SDK:** Android 15 (API 35)
- **Fitur Khusus:**
  - Foreground Service + WakeLock untuk Pemantauan Redaman & Tiket Latar Belakang (Alarm Sirine Native & Heads-Up Pop-up Banner ala WhatsApp)
  - Native JavaScript Bridge (`AndroidBridge.showNativeNotification`)
  - Permintaan Izin Otomatis (`POST_NOTIFICATIONS` di Android 13+) Tanpa Pop-up Baterai Mengganggu
  - Multi Product Flavors (`guard` -> TeknoGuard.apk, `mytekno` -> MyTekno.apk)
  - Pull-to-refresh & Custom WebChromeClient (Kamera, GPS Geolocation, & File Upload)

## Cara Build Menjadi APK di Android Studio:

1. Buka project di **Android Studio**.
2. Pilih variant di panel **Build Variants**:
   - `guardDebug` / `guardRelease` untuk **TeknoGuard.apk**
   - `myteknoDebug` / `myteknoRelease` untuk **MyTekno.apk**
3. Klik **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Output APK tersimpan di:
   - `app/build/outputs/apk/guard/debug/TeknoGuard.apk`
   - `app/build/outputs/apk/mytekno/debug/MyTekno.apk`
