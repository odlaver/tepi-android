# Tepi untuk Android

Dynamic island di sekitar kamera depan HP Android. Saudara dari [Tepi untuk Windows](https://github.com/odlaver/tepi).

## Fitur

- Musik: cover, kontrol, dan lirik sinkron dari LRCLIB, cadangannya NetEase.
- Pesan masuk dengan balas cepat dan tombol aksi notifikasi.
- Panggilan masuk dan berlangsung, unduhan dan progres, navigasi Google Maps, timer aplikasi Jam.
- Pengisian daya, baterai lemah, mode dering, perangkat audio, titik saat kamera atau mikrofon dipakai.
- Timer fokus dan istirahat, timer, stopwatch dengan putaran.
- Pengingat sholat metode Kemenag (dihitung di HP), minum, istirahat mata, peregangan.
- Kulia (jadwal, tugas, pengingat kelas), Todoist, Notion, kalender iCal.
- Catatan cepat dan rak untuk teks atau file yang dibagikan ke Tepi.
- Terjemahan di HP lewat ML Kit, teks tidak dikirim keluar.
- Izinkan atau tolak permintaan agen (Claude Code dan lainnya) dari Tepi di laptop, dengan sidik jari.

Ketuk kamera untuk Beranda, ketuk lagi untuk menutup. Geser ke bawah membuka panel, geser ke atas menutup, tahan untuk menu cepat, geser ke samping untuk pindah aktivitas. Ada juga tile Tepi di Quick Settings dan pintasan di ikon Tepi.

## Pasang

Unduh APK dari [halaman Tepi](https://odlaver.github.io/tepi/) atau [Releases](https://github.com/odlaver/tepi-android/releases/latest). Butuh Android 10 ke atas dengan prosesor arm64.

Izin yang diminta, semuanya bisa dinyalakan dari layar Pemasangan di Tepi:

| Izin | Untuk apa |
|---|---|
| Tampil di atas aplikasi lain | Menggambar island |
| Akses notifikasi | Membaca lagu yang diputar, pesan masuk, dan notifikasi berprogres |
| Aksesibilitas (opsional) | Satu jendela di atas status bar supaya island bisa disentuh; tidak membaca isi layar |
| Autostart dan baterai | Xiaomi, Oppo, Vivo, dan Huawei menghentikan Tepi di latar tanpa ini |

Android 13 ke atas mengunci akses notifikasi dan aksesibilitas untuk aplikasi dari luar Play Store. Buka kuncinya di Info aplikasi › ⋮ › Izinkan setelan terbatas.

## Privasi

Tidak ada akun, server, atau analitik. Yang keluar dari HP hanya:

- judul dan artis lagu ke LRCLIB atau NetEase, kalau lirik online nyala;
- permintaan ke layanan yang kamu sambungkan (Kulia, Todoist, Notion, tautan iCal);
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

## Lisensi

MIT. Lihat [LICENSE](LICENSE).
