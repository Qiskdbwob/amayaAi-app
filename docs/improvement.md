## Konsep Pemisahan Mode dan Kapabilitas AI pada Aplikasi Android

Saat ini aplikasi hanya memiliki satu mode chat biasa. Seluruh tool tersedia dalam satu tempat dan setiap session memiliki akses ke folder path atau workspace yang sama. Ke depannya, sistem akan dipisahkan menjadi tiga mode agar fungsi, izin, dan pengalaman pengguna lebih jelas.

### 1. Chat Biasa

Mode ini bekerja seperti percakapan ChatGPT pada umumnya dan digunakan untuk kebutuhan sehari-hari, pertanyaan cepat, serta tugas yang tidak memerlukan workspace permanen.

Karakteristik:

* Tidak terikat dengan project atau folder path tertentu.
* Hanya memiliki tool dasar yang paling penting.
* Tidak memiliki akses penuh untuk membaca, menulis, atau mengubah file project.
* Cocok untuk percakapan umum, pencarian informasi, analisis ringan, dan penggunaan file sementara.

Contoh tool yang tersedia:

* Web search.
* Fetch URL (konsep seperti curl untuk mengambil konten dari URL secara langsung).
* Upload dan analisis file.
* Terminal atau code execution terbatas (sementara dinonaktifkan).
* MCP atau connector tertentu yang telah diizinkan.
* Tool sederhana lainnya seperti chatbot pada umumnya.

### 2. Project

Mode Project digunakan untuk pekerjaan yang memiliki folder path, file, instruksi, dan konteks yang perlu dipertahankan dalam jangka panjang.

Setiap project memiliki workspace sendiri dan dapat memuat beberapa session chat yang berbeda. Seluruh session dalam project dapat mengakses file, referensi, dan custom instruction yang sama.

Karakteristik:

* Memiliki folder path atau workspace khusus (seperti sistem saat ini sebelum perubahan).
* Dapat membaca, membuat, mengedit, memindahkan, dan menghapus file dalam workspace project.
* Mendukung upload dokumen dan gambar sebagai referensi (pengaturan berada di chat screen, mengikuti konsep ChatGPT Project).
* Memiliki custom instruction khusus untuk setiap project (pengaturan berada di chat screen, mengikuti konsep ChatGPT Project).
* Satu project dapat memiliki beberapa session chat.
* Workspace memory terikat pada project_id dan tidak dapat mengakses workspace di luar project tersebut.
* Memiliki akses ke hampir seluruh tool.
* Tool browser automation tidak tersedia di dalam mode Project.

Contoh struktur:

```text
Project
├── Workspace
├── Files
├── References
├── Custom Instructions
└── Chat Sessions
```

Mode ini cocok untuk coding, riset, pengelolaan dokumen, pembuatan aplikasi, dan pekerjaan lain yang membutuhkan workspace permanen.

### 3. Agent

Mode Agent digunakan untuk membuat AI dengan peran, kemampuan, instruksi, knowledge, dan workspace tersendiri.

Agent dikelompokkan dalam **group**, yang merepresentasikan konteks organisasi atau kebutuhan pengguna. Terdapat dua jenis group utama:

#### a. Group Organisasi

Group ini merepresentasikan struktur organisasi seperti perusahaan. Contohnya:

```text
PT Indah Berkarya
├── CEO
├── CMO
└── PM
```

Agent dalam group ini memiliki peran yang jelas dan universal, seperti CEO untuk strategi bisnis, CMO untuk pemasaran, dan PM untuk produk.

#### b. Group Personal Agent

Group ini berisi agent-agent yang dibuat untuk kebutuhan pribadi atau spesifik pengguna. Contohnya:

```text
Personal Agents
├── Automation X
├── Automation IG
└── Explore Wisata
```

Agent dalam group ini biasanya fokus pada tugas tertentu seperti automasi, eksplorasi, atau workflow khusus.

---

Setiap AI Agent dapat memiliki:

* Nama dan peran.
* Custom instruction.
* Folder path atau workspace sendiri.
* Dokumen referensi sendiri (rules, guideline, atau data lainnya).
* Memory atau konteks jangka panjang masing-masing.
* Daftar tool dan kapabilitas (dapat diaktifkan atau dinonaktifkan).
* Beberapa session chat.
* Akses ke seluruh tool yang tersedia.

### Aturan Kolaborasi Antar Agent

* Agent dapat memanggil agent lain melalui mekanisme delegation.
* Pemanggilan agent dilakukan melalui input chat dengan bantuan dropdown.
* Pengguna memilih group terlebih dahulu, kemudian memilih agent dari group tersebut.

Contoh UI pemanggilan:

```text
Select Agent
├── PT Indah Berkarya >
│   ├── CEO
│   ├── CMO
│   └── PM
└── Personal Agents >
    ├── Automation X
    ├── Automation IG
    └── Explore Wisata
```

Dengan mekanisme ini, pemanggilan agent tetap terstruktur dan jelas berdasarkan group.

### Mekanisme Pemanggilan Agent

Agent dapat dipanggil dengan dua cara:

1. AI secara otomatis memutuskan bahwa ia membutuhkan pendapat agent lain.
2. Pengguna memanggil agent secara eksplisit melalui dropdown pemilihan agent.

Ketika sebuah agent dipanggil, sistem membuat delegation task yang berisi:

* Agent pemanggil.
* Agent tujuan.
* Pertanyaan atau tugas.
* Konteks yang relevan.
* File atau dokumen yang diperlukan.
* Hasil jawaban agent tujuan.

Riwayat delegation dapat dilihat dari session agent pemanggil maupun dari halaman agent yang dipanggil.

### Perbedaan Utama Setiap Mode

| Mode       | Workspace       | Tool                     | Multi-session | Agent Collaboration |
| ---------- | --------------- | ------------------------ | ------------- | ------------------- |
| Chat Biasa | Tidak permanen  | Tool dasar               | Ya            | Tidak               |
| Project    | Folder project  | Semua kecuali automation | Ya            | Opsional            |
| AI Agent   | Workspace agent | Semua tool               | Ya            | Ya                  |

### Struktur Sidebar

```text
Chats
├── New Chat
└── Recent Chats

Projects
├── Project A
│   ├── Files
│   ├── Instructions
│   ├── References
│   └── Sessions
└── Project B

AI Agents
├── PT Indah Berkarya
│   ├── CEO
│   ├── CMO
│   └── PM
└── Personal Agents
    ├── Automation X
    ├── Automation IG
    └── Explore Wisata
```

Dengan pemisahan ini, Chat Biasa tetap ringan dan sederhana, Project berfungsi sebagai workspace utama untuk pekerjaan berbasis file, sedangkan Agent menjadi sistem AI yang lebih mandiri, terstruktur dalam group, dan dapat bekerja sama dengan agent lain sesuai kebutuhan.
