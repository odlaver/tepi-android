# Tepi untuk Android

Dynamic island di sekitar kamera depan HP Android. Saudara dari [Tepi untuk Windows](https://github.com/odlaver/tepi).

Di HP Xiaomi dengan HyperOS 3, Tepi mengisi **Super Island bawaan sistem**. Di HP lain, Tepi menggambar island sendiri di sekitar kamera.

## Fitur

**Island dan tab.** Ketuk island untuk membukanya. Geser ke kiri atau kanan untuk pindah tab:

| Tab | Isi |
|---|---|
| Beranda | Sapaan, jam, kelas dan sholat berikutnya, ringkasan semua tab |
| Musik | Cover, kontrol, progres, lirik sinkron dari LRCLIB (cadangannya NetEase) |
| Timer | Fokus dan istirahat, timer 5/10/15 menit, stopwatch dengan putaran |
| Agenda | Kelas hari ini dan tugas dari Kulia, Todoist, Notion, atau kalender iCal |
| Ibadah | Lima waktu sholat hari ini (metode Kemenag, dihitung di HP) dan hitungan minum |
| Agen | Sesi Claude Code dan agen lain di laptop, kalau Tepi Windows tersambung |
| Catatan | Catatan cepat |
| Rak | Teks dan file yang dibagikan ke Tepi |

**Aktivitas yang muncul sendiri:** pesan masuk dengan balas cepat, panggilan, unduhan dan progres, navigasi Google Maps, timer aplikasi Jam, pengisian daya, baterai lemah, mode dering, headset, titik saat kamera atau mikrofon dipakai, dan pengingat (sholat, kelas, minum, istirahat mata, peregangan).

**Lainnya:**
- terjemahan di HP lewat ML Kit, dari menu seleksi teks;
- izinkan atau tolak permintaan agen di laptop, dengan sidik jari;
- pilih aplikasi mana yang notifikasinya dibaca Tepi;
- sakelar utama untuk mematikan semuanya sekaligus.

**Gestur:**

| Gestur | Aksi |
|---|---|
| Ketuk kamera | Buka Beranda, ketuk lagi untuk menutup |
| Ketuk island | Buka panel aktivitas |
| Geser ke bawah | Buka panel |
| Geser ke atas | Tutup panel atau buang aktivitas |
| Tahan | Menu cepat: timer, catatan, sembunyikan 1 jam, pengaturan |
| Geser ke samping | Pindah tab |

Ada juga tile Tepi di Quick Settings dan pintasan di ikon Tepi.

## Super Island HyperOS

Tepi mengirim timer, pengingat, pesan, unduhan, dan permintaan agen sebagai notifikasi fokus HyperOS (`miui.focus.param`), sehingga tampil di Super Island bawaan dengan animasi sistem. Musik, panggilan, dan navigasi tetap ditampilkan HyperOS sendiri.

Butuh HyperOS 3 (`notification_focus_protocol` 3), izin notifikasi, dan **Notifikasi unggulan** untuk Tepi. Keduanya bisa dinyalakan dari Pengaturan › Island › Island HyperOS.

## Pasang

Unduh APK dari [halaman Tepi](https://odlaver.github.io/tepi/) atau [Releases](https://github.com/odlaver/tepi-android/releases/latest). Butuh Android 10 ke atas dengan prosesor arm64.

Izin yang diminta, semuanya bisa dinyalakan dari layar Pemasangan di Tepi:

| Izin | Untuk apa |
|---|---|
| Tampil di atas aplikasi lain | Menggambar island Tepi dan mengenali aplikasi layar penuh |
| Akses notifikasi | Membaca lagu yang diputar, pesan masuk, dan notifikasi berprogres |
| Aksesibilitas (opsional) | Satu jendela di atas status bar supaya island bisa disentuh; tidak membaca isi layar |
| Autostart dan baterai | Xiaomi, Oppo, Vivo, dan Huawei menghentikan Tepi di latar tanpa ini |
| Notifikasi dan Notifikasi unggulan (opsional) | Super Island HyperOS |

Android 13 ke atas mengunci akses notifikasi dan aksesibilitas untuk aplikasi dari luar Play Store. Buka kuncinya di Info aplikasi › ⋮ › Izinkan setelan terbatas.

## Privasi

Tidak ada akun, server, atau analitik. Yang keluar dari HP hanya:

- judul dan artis lagu ke LRCLIB atau NetEase, kalau lirik online nyala;
- permintaan ke layanan yang kamu sambungkan (Kulia, Todoist, Notion, tautan iCal);
- model terjemahan ML Kit, diunduh sekali setelah kamu setujui;
- sambungan ke Tepi di laptop, terenkripsi AES dan ditandatangani HMAC, hanya di jaringan lokal.

Token dan sesi disimpan terenkripsi dengan Android Keystore. Rinciannya ada di Tepi › Tentang › Yang dibaca Tepi.

## Sambungan ke laptop

Di Tepi untuk Windows: Pengaturan › Agen › Hubungkan HP, lalu Buat kode. Di HP: Integrasi › Laptop, ketik alamat dan kode 12 karakter yang muncul. Laptop dan HP harus satu Wi-Fi, dan firewall Windows perlu mengizinkan Tepi di jaringan privat.

## Build sendiri

Butuh JDK 21 dan Android SDK (platform 36).

```
./gradlew assembleDebug
```

APK ada di `app/build/outputs/apk/debug/`. Untuk menandatangani dengan kunci sendiri, isi `TEPI_STORE` dan `TEPI_STORE_PASSWORD` di `~/.gradle/gradle.properties`; tanpa itu Gradle memakai kunci debug.

## Pustaka pihak ketiga

- [HyperIsland ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit) (Apache-2.0), penyusun payload Super Island HyperOS.
- [ML Kit Translate](https://developers.google.com/ml-kit/language/translation) dan Language ID, terjemahan di HP.

## Lisensi

Kode: MIT. Lihat [LICENSE](LICENSE).
