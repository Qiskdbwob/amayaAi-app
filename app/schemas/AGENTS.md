# App Schema Snapshot Instructions

## Scope
- This file applies to `app/schemas/` and the exported Room schema snapshots in this folder.

## Rules
- Keep these snapshots aligned with `data/local/db/AppDatabase.kt`, entities, and migration changes.
- When a schema version changes, update this file and the generated JSON snapshots in the same pass.
- Crosscheck recent commits touching Room schema, DAO, or migration code before finalizing schema updates.

# 🧱 DESAIN VERSIONING (v1 → v15)

## ✅ V1 (Initial Release — Foundation)

**Isi:**

* `projects`
* `files`
* `file_metadata`
* `conversations`
* `cron_jobs`
* `files_fts`

**Rules:**

* Semua column sudah dipikirkan nullable/default
* Hindari future breaking change

---

## 🔼 V2 (Non-breaking additive)

**Contoh:**

* Tambah column:

  * `projects.description TEXT`
  * `files.mime_type TEXT`

**Migration:**

```sql
ALTER TABLE projects ADD COLUMN description TEXT;
ALTER TABLE files ADD COLUMN mime_type TEXT;
```

👉 AMAN (tidak reset)

---

## 🔼 V3 (Index & performance)

**Contoh:**

* Tambah index:

```sql
CREATE INDEX index_files_mime_type ON files(mime_type);
```

👉 Tidak ubah data → aman

---

## 🔼 V4 (FTS improvement ⚠️ tricky)

**Contoh:**

* Tambah field ke FTS (misal: `extension`)

**Migration strategy:**

```sql
DROP TABLE IF EXISTS files_fts;

CREATE VIRTUAL TABLE files_fts USING FTS4(
    file_name,
    relative_path,
    extension,
    content='files'
);

INSERT INTO files_fts(rowid, file_name, relative_path, extension)
SELECT id, file_name, relative_path, extension FROM files;
```

👉 **WAJIB reindex (tidak bisa ALTER)**

---

## 🔼 V5 (Relational improvement)

**Contoh:**

* Tambah table baru:

  * `tags`
  * `file_tags`

👉 Best practice:

* Jangan ubah table lama
* Tambah table baru

---

## 🔼 V6 (Column rename ❗ dangerous)

Room tidak support rename langsung → pakai copy table

**Strategy:**

```sql
CREATE TABLE files_new (...);

INSERT INTO files_new (...)
SELECT ... FROM files;

DROP TABLE files;

ALTER TABLE files_new RENAME TO files;
```

👉 Ini yang sering bikin dev gagal

---

## 🔼 V7 (Data normalization)

Contoh:

* Pisah `messagesJson` → table `messages`

👉 Migration:

* Extract JSON
* Insert ke table baru

---

## 🔼 V8 (Constraint change)

Contoh:

* Tambah UNIQUE constraint

👉 Harus recreate table (copy pattern lagi)

---

## ✅ V9 (Provider/model catalog cleanup)

Migration `8 → 9` drops the unused provider/model catalog tables:

* `provider_connections`
* `model_catalog`
* `provider_model_availability`
* `manual_model_overrides`
* `model_aliases`
* `model_routes`
* `agent_profiles`

Provider/model settings now use DataStore plus encrypted credential storage.

---

## ✅ V10 (Mode ownership foundation)

Migration `9 → 10` adds:

* `conversations.assistant_mode`
* `conversations.owner_id`
* legacy `agents` foundation

Existing local conversations with a workspace migrate to `PROJECT`; other local conversations remain `CHAT`.

---

## ✅ V11 (Projects and multi-agent groups)

Migration `10 → 11` adds:

* project instructions and reference paths;
* normalized `agent_groups` with workspace, instructions, references, and compatibility capability profile;
* many `agents` per group with a foreign key;
* persisted `delegation_tasks`;
* Agent conversation owners remapped from legacy agent IDs to group IDs.

## ✅ V12 (Per-agent runtime configuration)

Migration `11 → 12` adds:

* `agents.capability_profile` for per-agent tool configuration;
* `conversations.agent_id` so Agent sessions reopen with the correct member;
* legacy Agent sessions assigned to the first member in their group when available.

## ✅ V13 (Per-agent references)

Migration `12 → 13` adds `agents.reference_paths_json`; group references remain shared, member references become private to each agent context.

## ✅ V14 (Agent-owned reminders and jobs)

Migration `13 → 14` adds nullable `cron_jobs.agent_id` plus its lookup index. Legacy jobs remain unowned; new Agent jobs stay scoped to their Agent.

## ✅ V15 (Agent default models)

Migration `14 → 15` adds `agents.default_model_keys_json`. Keys reference active Manage Models entries; an empty list inherits the global active model.

## ✅ V16 (Group-local Agent IDs)

Migration `15 → 16` adds `agents.local_id`, backfills IDs by creation order per group, and adds a unique `(group_id, local_id)` index. Model prompts, mentions, and delegation use this ID; Room `agents.id` remains internal database identity.

---

# 🔥 TEMPLATE MIGRATION (WAJIB PUNYA)

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            // SQL disini

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
```

---

# ⚠️ RULE KRITIS (INI YANG NYELAMETIN DB KAMU)

## ❌ JANGAN PERNAH

* pakai `fallbackToDestructiveMigration()` di production
* ubah entity tanpa migration
* ubah nama column langsung

---

## ✅ WAJIB

* Simpan semua migration (jangan dihapus)
* Test upgrade dari versi lama
* Gunakan default value saat tambah column

---

# 🧪 TEST STRATEGY (PRO LEVEL)

Simulasi real:

1. Install app v1
2. Insert data
3. Upgrade ke v11
4. Verify:

   * data utuh
   * FTS jalan
   * foreign key valid

---

# 🧰 STRUKTUR FOLDER (SCALABLE)

```
data/
 ├── local/
 │    ├── entity/
 │    ├── dao/
 │    ├── db/
 │    │    ├── AppDatabase.kt
 │    │    └── migrations/
 │    │         ├── Migration1_2.kt
 │    │         ├── Migration2_3.kt
 │    │         └── ...
```

---

# 🧠 STRATEGI KHUSUS FTS (PENTING BANGET)

Karena kamu pakai:

```sql
files_fts (content = files)
```

👉 RULE:

* Jangan anggap FTS = normal table
* Setiap perubahan schema → REBUILD

Checklist:

* DROP FTS
* CREATE ulang
* INSERT dari `files`

---

# 🚀 BONUS: FAIL-SAFE STRATEGY (ANTI DATA LOSS)

Kalau takut migration gagal:

## 1. Backup sebelum migration

```kotlin
context.getDatabasePath("app_db").copyTo(...)
```

## 2. Restore jika gagal

---

# 🔥 KESIMPULAN

Strategi kamu harus:

> ✅ Additive first
> ✅ Recreate only when necessary
> ✅ FTS selalu rebuild
> ❌ Tidak pernah destructive

---

