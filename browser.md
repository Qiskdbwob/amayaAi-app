# DAFTAR LENGKAP TOOL MCP BROWSER (PUPPETEER / PLAYWRIGHT)

## A. MANAJEMEN TAB & BROWSER (Browser Lifecycle)
1. browser_new_page         : Membuka tab/halaman baru yang kosong di browser.
2. browser_navigate         : Mengarahkan browser ke URL target (membuka situs web).
3. browser_switch_page       : Berpindah fokus antar-tab yang sedang terbuka.
4. browser_close_page        : Menutup tab tertentu untuk menghemat memori.

## B. INTERAKSI PENGGUNA (Simulasi Manusia)
5. browser_click            : Mengklik elemen (tombol, link, checkbox) berdasarkan CSS Selector.
6. browser_type / fill      : Mengetik teks atau mengisi kolom input/formulir.
7. browser_hover            : Mengapungkan kursor mouse di atas elemen (memicu dropdown menu).
8. browser_select_option    : Memilih opsi dari menu runtuh (dropdown <select>).
9. browser_press_key        : Menekan tombol keyboard khusus (Enter, Tab, Backspace, dll).

## C. INSPEKSI & EKSTRAKSI DATA (Scraping & Reading)
10. browser_get_snapshot    : Mengambil struktur teks & pohon aksesibilitas halaman untuk AI.
11. browser_get_html        : Mengambil seluruh source code HTML yang sudah dirender.
12. browser_get_content     : Mengekstraksi teks mentah yang terlihat di layar (tanpa kode HTML).
13. browser_screenshot      : Mengambil tangkapan gambar halaman (sebagian atau satu halaman penuh).

## D. KONTROL TINGKAT LANJUT (Flow & Scripting)
14. browser_wait_for_selector : Menunggu elemen tertentu muncul di layar sebelum beraksi.
15. browser_wait_for_nav     : Menunggu proses loading perpindahan halaman selesai sepenuhnya.
16. browser_set_viewport     : Mengubah resolusi layar (simulasi tampilan Desktop vs Mobile).
17. browser_evaluate         : Menjalankan skrip JavaScript kustom di konsol browser.


[User Prompt] -> "Cari laptop ASUS di Tokopedia..."
      |
      v
1. [browser_new_page]       => AI membuka tab baru untuk memulai sesi bersih.
      |
      v
2. [browser_navigate]       => Membuka URL "https://tokopedia.com".
      |
      v
3. [browser_wait_for_selector] => Menunggu kolom pencarian Tokopedia muncul di layar.
      |
      v
4. [browser_type]           => Mengetik kata kunci "Laptop ASUS" di kolom pencarian.
      |
      v
5. [browser_press_key]       => Menekan tombol "Enter" untuk mengeksekusi pencarian.
      |
      v
6. [browser_wait_for_nav]   => Menunggu halaman hasil pencarian selesai dimuat (loading).
      |
      v
7. [browser_click]          => Mengklik tombol filter "Harga Terendah".
      |
      v
8. [browser_get_snapshot]   => AI membaca data teks hasil pencarian untuk dianalisis.
      |
      v
9. [browser_screenshot]     => Mengambil gambar halaman sebagai bukti visual kepada pengguna.
      |
      v
[Output ke Pengguna]       => AI memberikan daftar harga laptop beserta file gambarnya.


ALUR EKSEKUSI TOOL OLEH AI:
--------------------------------------------------------------------------------------
[LANGKAH 1] -> Memanggil `browser_navigate` ke "https://internal.com"
[LANGKAH 2] -> Memanggil `browser_type` pada selector `#username` dengan teks "admin123"
[LANGKAH 3] -> Memanggil `browser_type` pada selector `#password` dengan teks "rahasia123"
[LANGKAH 4] -> Memanggil `browser_click` pada tombol `#btn-submit`
[LANGKAH 5] -> Memanggil `browser_wait_for_navigation` untuk memastikan login sukses
[LANGKAH 6] -> Memanggil `browser_get_content` pada elemen panel `.notification-box`
[LANGKAH 7] -> Analisis Internal AI: Membaca teks mentah untuk mendeteksi kata "Error"
[LANGKAH 8] -> Memanggil `browser_close_page` untuk menutup browser dengan aman
--------------------------------------------------------------------------------------
HASIL AKHIR -> AI melaporkan kepada Anda: "Login berhasil, ditemukan 2 log error pada sistem."
