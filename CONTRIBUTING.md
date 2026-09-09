# 🤝 Kontribusi ke PayListen

Terima kasih sudah mau berkontribusi! Repo ini terbuka untuk siapa pun.

## Cara Berkontribusi

### 1. Fork & Clone
```
Fork repo ini ke akun GitHub kamu (tombol Fork di kanan atas)
git clone https://github.com/USERNAME-KAMU/PayListen.git
```

### 2. Build & Coba
```
Buka di Android Studio → Sync Gradle → Run
Atau: gradle assembleDebug (butuh JDK 17)
```
APK debug bisa langsung dipasang & dites tanpa konfigurasi apa pun.

### 3. Buat Perubahan
- Satu pull request = satu fitur/perbaikan (jangan campur banyak hal)
- Ikuti gaya kode yang ada (Kotlin, UI programatik, tema gelap)
- Tidak menambah kredensial/URL server/secret apa pun

### 4. Pull Request
```
git checkout -b fitur-baru
git commit -m "deskripsi singkat perubahan"
git push origin fitur-baru
```
Lalu buka Pull Request di GitHub. Tuliskan: apa yang diubah & kenapa.

## Aturan Penting

| Aturan | Alasan |
|--------|--------|
| ❌ Jangan commit keystore / password / API key | Ini repo publik |
| ❌ Jangan hardcode URL server tertentu | App ini universal — URL diisi user |
| ❌ Jangan ubah package `com.paylisten.app` | Menimpa app produksi user |
| ✅ Minimal perubahan, jelas tujuannya | Mudah direview |
| ✅ Bahasa Indonesia / Inggris sama-sama OK | — |

## Laporkan Bug

Pakai tab **Issues** di repo ini. Sertakan:
1. Langkah memunculkan bug
2. Tampilan/log yang muncul
3. Versi Android & tipe HP

## Ide Fitur yang Dicari

- Dukungan format notifikasi e-wallet baru
- Mode hemat baterai
- Widget status di home screen
- Terjemahan bahasa lain (values-*/strings.xml)

---

Lisensi: MIT — kontribusi kamu ikut lisensi ini.
