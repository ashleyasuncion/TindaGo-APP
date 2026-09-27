# Phase 2: Android Sync — Implementation Plan

> Reviewed against actual codebase at `git/app/TindaGo_APP/`
> Date: 2026-09-26 | Status: PLAN — execute one task at a time, compile-check after each.

## 0. Findings

- No JSON lib — only `org.json` (keep it).
- No HTTP lib — proposal adds Ktor 2.3.12 (risk: built for Kotlin 1.9.x, project is Kotlin 2.0.21).
- DAO accessors in `AppDatabase.kt` all match proposal.
- `DailyEntryDao` / `EndOfDayDao` lack `getAll()` — Task 2 required.
- `RestockLogEntity.id` is `String` → Supabase `restock_log.id` must be `text`.
- `INTERNET` permission missing — Task 1 required.
- BLOCKING: `AppViewModel : ViewModel()` (not `AndroidViewModel`). No `getApplication()`. Uses `initRepository()` from `NavGraph.kt`. Task 5 must use `initSync(context, db)` pattern.
- Missing: Supabase SQL, `user_id` scoping, refresh_token storage, `HttpClient.close()`.

## Task 1 — Dependencies + Permissions

**Why:** `ktor-client-android` wraps Android's built-in `HttpURLConnection`. Safest engine — no OkHttp, no CIO DNS issues, zero native code. Two small deps. Alternative (zero-dep) is raw `HttpURLConnection`; see Open Decisions.

**File: `app/build.gradle.kts`** — inside `dependencies { }`, near other `implementation` lines (after WorkManager block, before Testing):

```kotlin
// Supabase sync (Phase 2)
implementation("io.ktor:ktor-client-core:2.3.12")
implementation("io.ktor:ktor-client-android:2.3.12")
```

> Preferred long-term: move versions to `gradle/libs.versions.toml`:
> ```toml
> ktor = "2.3.12"
> ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
> ktor-client-android = { group = "io.ktor", name = "ktor-client-android", version.ref = "ktor" }
> ```
> Then `implementation(libs.ktor.client.core)` etc. Hardcoded strings are acceptable for now.

**File: `app/src/main/AndroidManifest.xml`** — inside `<manifest>`, before `<application>` (after SEND_SMS permission):

```xml
<!-- Phase 2: cloud sync to Supabase -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Verify:** Sync Gradle (no code change yet). `./gradlew :app:assembleDebug` must still pass.

## Task 2 — getAllEntries() for Two DAOs

**Why:** `DailyEntryDao` currently only exposes `getLatestEntry(): Flow` + `getByDate()`. `EndOfDayDao` only exposes `getLatest(): Flow` + `getByDate()`. Sync needs every row (one-shot read, not a reactive stream), so we add a `suspend fun ... : List` query to each. No `.first()` needed at the call site.

**File: `app/src/main/java/com/example/tindago/data/local/dao/DailyEntryDao.kt`** — add inside the interface:

```kotlin
@Query("SELECT * FROM daily_entries ORDER BY date DESC")
suspend fun getAllEntries(): List<DailyEntryEntity>
```

**File: `app/src/main/java/com/example/tindago/data/local/dao/EndOfDayDao.kt`** — add inside the interface:

```kotlin
@Query("SELECT * FROM end_of_day_data ORDER BY date DESC")
suspend fun getAllEntries(): List<EndOfDayEntity>
```

**Verify:** Rebuild — Room KSP must pass (`./gradlew :app:assembleDebug`).

## Task 3 — SupabaseConfig.kt

**Create file: `app/src/main/java/com/example/tindago/data/sync/SupabaseConfig.kt`**

> Where to find values: Supabase Dashboard → **Settings → API**. Copy **Project URL** + **anon public** key into the two constants. Fixes vs draft: stores `refresh_token` + `user.id` (needed for `user_id` scoping + future token refresh), not just `access_token`.

```kotlin
package com.example.tindago.data.sync

import android.content.Context
import android.content.SharedPreferences

object SupabaseConfig {

    // ── REPLACE WITH YOUR ACTUAL SUPABASE VALUES ──
    const val SUPABASE_URL = "https://YOUR_PROJECT_ID.supabase.co"
    const val SUPABASE_ANON_KEY = "YOUR_ANON_KEY_HERE"
    // ───────────────────────────────────────────────

    private const val PREFS_NAME = "tindago_sync_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        uid: String?,
        email: String
    ) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, uid)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun getUid(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    fun getEmail(context: Context): String? =
        prefs(context).getString(KEY_USER_EMAIL, null)

    fun isLoggedIn(context: Context): Boolean =
        getToken(context) != null

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun saveLastSyncTime(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, timestamp).apply()
    }

    fun getLastSyncTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)
}
```

**Verify:** compiles (no usage yet).

## Task 4 — Supabase SQL Schema v2 (run BEFORE any sync test) — SUPERSEDES DRAFT

> ⚠️ The original draft (single-column PKs + bare `user_id`, no RLS scoping)
> was SUPERSEDED 2026-09-26: a pre-existing 12-table schema was found in the
> dashboard (incl. `suppliers`, `store_settings`, `synced_at`, `USING(true)`
> RLS). Single-column PKs let two stores overwrite each other on upsert, and
> `store_settings(id=1)` / date PKs collide across users. DO NOT RUN the old
> script. Run the **v2 script below** (composite PKs + per-user RLS) after
> `DROP TABLE ... CASCADE` on a fresh dev DB.

Run in Supabase Dashboard → SQL Editor. **v2: composite PKs `(user_id, id)` /
`(user_id, date)`** so Store A's `products.id=1` never overwrites Store B's.
RLS = `auth.uid() = user_id` (not `USING(true)`). **Critical types:** same as
before — `restock_log.id = text`, `daily_entries.date` / `end_of_day_data.date`
= text (now second PK column), all other ids `bigint`. Plus `synced_at`,
`suppliers`, `store_settings(user_id PK — one row per user, web-managed, NOT
phone-synced)`.

```sql
-- ============================================================
-- TindaGo Supabase schema v2 — multi-store safe (composite PKs)
-- 10 Room tables + suppliers + store_settings
-- Pre-req on fresh dev DB: DROP TABLE IF EXISTS sms_log,
-- restock_log, end_of_day_data, daily_entries, expenses,
-- debt_transactions, debt_payments, customer_debts,
-- specific_sales, products, suppliers, store_settings CASCADE;
-- ============================================================

-- ── 1. products ──
create table if not exists products (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  name text not null,
  quantity int not null default 0,
  cost_price double precision not null default 0,
  selling_price double precision not null default 0,
  unit text not null default 'piece',
  low_stock_threshold int not null default 5,
  category text not null default '',
  subcategory text not null default '',
  brand text not null default '',
  package_size text not null default '',
  synced_at timestamptz default now(),
  primary key (user_id, id)
);

-- ── 2. specific_sales ──
create table if not exists specific_sales (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  date text not null,
  description text not null,
  amount double precision not null default 0,
  quantity int not null default 1,
  customer_name text,
  profit double precision not null default 0,
  timestamp bigint not null,
  transaction_id bigint not null default 0,
  payment_method text,
  synced_at timestamptz default now(),
  primary key (user_id, id)
);
create index if not exists idx_sales_user_date on specific_sales(user_id, date);

-- ── 3. customer_debts ──
create table if not exists customer_debts (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  customer_name text not null,
  amount double precision not null default 0,
  remaining_balance double precision not null default 0,
  created_at bigint not null,
  credit_limit int,
  phone_number text not null default '',
  sms_opt_in int,
  synced_at timestamptz default now(),
  primary key (user_id, id)
);

-- ── 4. debt_payments ──
create table if not exists debt_payments (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  debt_id bigint not null,
  amount double precision not null default 0,
  timestamp bigint not null,
  note text,
  synced_at timestamptz default now(),
  primary key (user_id, id),
  foreign key (user_id, debt_id)
    references customer_debts(user_id, id) on delete cascade
);
create index if not exists idx_payments_user_debt on debt_payments(user_id, debt_id);

-- ── 5. debt_transactions ──
create table if not exists debt_transactions (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  debt_id bigint not null,
  type text not null,
  description text,
  amount double precision not null default 0,
  timestamp bigint not null,
  synced_at timestamptz default now(),
  primary key (user_id, id),
  foreign key (user_id, debt_id)
    references customer_debts(user_id, id) on delete cascade
);
create index if not exists idx_txns_user_debt on debt_transactions(user_id, debt_id);

-- ── 6. expenses ──
create table if not exists expenses (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  date text not null,
  category text not null,
  amount double precision not null default 0,
  note text not null default '',
  timestamp bigint not null,
  synced_at timestamptz default now(),
  primary key (user_id, id)
);
create index if not exists idx_expenses_user_date on expenses(user_id, date);

-- ── 7. daily_entries (date PK, per-user) ──
create table if not exists daily_entries (
  user_id uuid not null references auth.users(id) on delete cascade,
  date text not null,
  stock_expenses double precision not null default 0,
  earnings double precision not null default 0,
  synced_at timestamptz default now(),
  primary key (user_id, date)
);

-- ── 8. end_of_day_data (date PK, per-user) ──
create table if not exists end_of_day_data (
  user_id uuid not null references auth.users(id) on delete cascade,
  date text not null,
  cash_in_drawer double precision not null default 0,
  stock_check_done boolean not null default false,
  debt_payments_done boolean not null default false,
  finished boolean not null default false,
  recorded_sales double precision not null default 0,
  actual_sales double precision not null default 0,
  sales_diff double precision not null default 0,
  profit double precision not null default 0,
  expenses double precision not null default 0,
  net_profit double precision not null default 0,
  synced_at timestamptz default now(),
  primary key (user_id, date)
);

-- ── 9. restock_log (TEXT id, per-user) ──
create table if not exists restock_log (
  user_id uuid not null references auth.users(id) on delete cascade,
  id text not null,
  date text not null,
  items_json text not null,
  total_cost double precision not null default 0,
  synced_at timestamptz default now(),
  primary key (user_id, id)
);

-- ── 10. sms_log ──
create table if not exists sms_log (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint not null,
  debt_id bigint not null,
  customer_name text not null,
  phone_number text not null,
  type text not null,
  message_body text not null,
  status text not null,
  timestamp bigint not null,
  synced_at timestamptz default now(),
  primary key (user_id, id)
);
create index if not exists idx_sms_user_debt on sms_log(user_id, debt_id);

-- ── 11. suppliers (web portal only) ──
create table if not exists suppliers (
  user_id uuid not null references auth.users(id) on delete cascade,
  id bigint generated always as identity,
  name text not null,
  contact_person text default '',
  phone text default '',
  email text default '',
  address text default '',
  notes text default '',
  created_at timestamptz default now(),
  primary key (user_id, id)
);

-- ── 12. store_settings (one row per user, web-managed, NOT phone-synced) ──
create table if not exists store_settings (
  user_id uuid not null references auth.users(id) on delete cascade primary key,
  store_name text default '',
  owner_name text default '',
  default_markup double precision default 0,
  low_stock_threshold int default 5,
  default_credit_limit int default 500,
  synced_at timestamptz default now()
);

-- ── RLS: each store sees ONLY its own rows ──
-- (idempotent re-run guard: DROP first so 42710 "policy already exists"
-- never aborts a partial run — observed 2026-09-26)
do $$
declare t text;
begin
  foreach t in array array[
    'products','specific_sales','customer_debts','debt_payments',
    'debt_transactions','expenses','daily_entries','end_of_day_data',
    'restock_log','sms_log','suppliers','store_settings'
  ] loop
    execute format('drop policy if exists "own_rows_%s" on %I', t, t);
  end loop;
end $$;

alter table products enable row level security;
alter table specific_sales enable row level security;
alter table customer_debts enable row level security;
alter table debt_payments enable row level security;
alter table debt_transactions enable row level security;
alter table expenses enable row level security;
alter table daily_entries enable row level security;
alter table end_of_day_data enable row level security;
alter table restock_log enable row level security;
alter table sms_log enable row level security;
alter table suppliers enable row level security;
alter table store_settings enable row level security;

do $$
declare t text;
begin
  foreach t in array array[
    'products','specific_sales','customer_debts','debt_payments',
    'debt_transactions','expenses','daily_entries','end_of_day_data',
    'restock_log','sms_log','suppliers','store_settings'
  ] loop
    execute format(
      'create policy "own_rows_%s" on %I for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id)',
      t, t
    );
  end loop;
end $$;
```

**Verify:** `Success. No rows returned` (DDL returns nothing — normal). Table Editor
shows 12 tables; `products` columns show `user_id` first with PK `(user_id, id)`.
Task 5 impact: `SyncRepository` already sends `user_id` per row (no converter
change); just ensure `getUid()` is non-null at sync time (comes from sign-in's
`user.id`). `store_settings` is intentionally NOT in `syncAll()`.

## Task 5 — SyncRepository.kt (core)

**Create file: `app/src/main/java/com/example/tindago/data/sync/SyncRepository.kt`**

> Largest file: auth + push of all 10 tables. Notes: DAO accessors must match `AppDatabase.kt` (verified above). `dailyEntriesToJson()` / `endOfDayToJson()` use the `suspend getAllEntries()` from Task 2 (no `.first()`). Every row includes `user_id` for multi-tenant scoping. `close()` must be called from `ViewModel.onCleared()`.

```kotlin
package com.example.tindago.data.sync

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.tindago.data.local.AppDatabase

class SyncRepository(
    private val context: Context,
    private val db: AppDatabase
) {
    private val client = HttpClient(Android) {
        engine { connectTimeout = 15_000; socketTimeout = 30_000 }
    }

    private val baseUrl = SupabaseConfig.SUPABASE_URL
    private val apiKey = SupabaseConfig.SUPABASE_ANON_KEY

    fun close() { client.close() }

    // ── AUTH ──────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val response: HttpResponse =
                    client.post("$baseUrl/auth/v1/token?grant_type=password") {
                        header("apikey", apiKey)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                val json = JSONObject(response.bodyAsText())
                if (response.status.isSuccess()) {
                    val token = json.getString("access_token")
                    val refresh = json.optString("refresh_token", null)
                    val uid = json.optJSONObject("user")?.optString("id", null)
                    SupabaseConfig.saveSession(context, token, refresh, uid, email)
                    Result.success("Signed in as $email")
                } else {
                    Result.failure(Exception(json.optString("error_description", "Login failed")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── FULL SYNC ─────────────────────────────────────────

    suspend fun syncAll(): Result<String> = withContext(Dispatchers.IO) {
        val token = SupabaseConfig.getToken(context)
            ?: return@withContext Result.failure(Exception("Not logged in"))

        try {
            val tables = listOf(
                "products" to productsToJson(),
                "customer_debts" to debtsToJson(),
                "debt_payments" to paymentsToJson(),
                "debt_transactions" to debtTransactionsToJson(),
                "specific_sales" to salesToJson(),
                "expenses" to expensesToJson(),
                "daily_entries" to dailyEntriesToJson(),
                "end_of_day_data" to endOfDayToJson(),
                "restock_log" to restockLogToJson(),
                "sms_log" to smsLogToJson()
            )

            var synced = 0
            for ((table, jsonArray) in tables) {
                if (jsonArray.length() == 0) continue
                upsertTable(token, table, jsonArray)
                synced++
            }

            SupabaseConfig.saveLastSyncTime(context, System.currentTimeMillis())
            Result.success("Synced $synced tables successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── HTTP UPSERT ───────────────────────────────────────

    private suspend fun upsertTable(token: String, table: String, data: JSONArray) {
        val response: HttpResponse = client.post("$baseUrl/rest/v1/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(data.toString())
        }
        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            throw Exception("Failed to sync $table: ${response.status} — $error")
        }
    }

    private fun uid(): Any =
        SupabaseConfig.getUid(context) ?: JSONObject.NULL

    // ── ENTITY → JSON CONVERTERS (snake_case for Supabase) ─

    private suspend fun productsToJson(): JSONArray {
        val rows = db.productDao().getAllProducts().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("name", e.name)
                    put("quantity", e.quantity)
                    put("cost_price", e.costPrice)
                    put("selling_price", e.sellingPrice)
                    put("unit", e.unit)
                    put("low_stock_threshold", e.lowStockThreshold)
                    put("category", e.category)
                    put("subcategory", e.subcategory)
                    put("brand", e.brand)
                    put("package_size", e.packageSize)
                })
            }
        }
    }

    private suspend fun salesToJson(): JSONArray {
        val rows = db.specificSaleDao().getAllSales().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("date", e.date)
                    put("description", e.description)
                    put("amount", e.amount)
                    put("quantity", e.quantity)
                    put("customer_name", e.customerName ?: JSONObject.NULL)
                    put("profit", e.profit)
                    put("timestamp", e.timestamp)
                    put("transaction_id", e.transactionId)
                    put("payment_method", e.paymentMethod ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun debtsToJson(): JSONArray {
        val rows = db.customerDebtDao().getAllDebts().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("customer_name", e.customerName)
                    put("amount", e.amount)
                    put("remaining_balance", e.remainingBalance)
                    put("created_at", e.createdAt)
                    put("credit_limit", e.creditLimit ?: JSONObject.NULL)
                    put("phone_number", e.phoneNumber)
                    put("sms_opt_in", e.smsOptIn ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun paymentsToJson(): JSONArray {
        val rows = db.debtPaymentDao().getAllPayments().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("amount", e.amount)
                    put("timestamp", e.timestamp)
                    put("note", e.note ?: JSONObject.NULL)
                })
            }
        }
    }

    private suspend fun debtTransactionsToJson(): JSONArray {
        val rows = db.debtTransactionDao().getAllTransactions().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("type", e.type)
                    put("description", e.description ?: JSONObject.NULL)
                    put("amount", e.amount)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }

    private suspend fun expensesToJson(): JSONArray {
        val rows = db.expenseDao().getAllExpenses().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("date", e.date)
                    put("category", e.category)
                    put("amount", e.amount)
                    put("note", e.note)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }

    private suspend fun dailyEntriesToJson(): JSONArray {
        // Task 2 suspend query — returns List directly, no .first() needed.
        val rows = db.dailyEntryDao().getAllEntries()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("date", e.date)
                    put("user_id", uid())
                    put("stock_expenses", e.stockExpenses)
                    put("earnings", e.earnings)
                })
            }
        }
    }

    private suspend fun endOfDayToJson(): JSONArray {
        // Task 2 suspend query — returns List directly, no .first() needed.
        val rows = db.endOfDayDao().getAllEntries()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("date", e.date)
                    put("user_id", uid())
                    put("cash_in_drawer", e.cashInDrawer)
                    put("stock_check_done", e.stockCheckDone)
                    put("debt_payments_done", e.debtPaymentsDone)
                    put("finished", e.finished)
                    put("recorded_sales", e.recordedSales)
                    put("actual_sales", e.actualSales)
                    put("sales_diff", e.salesDiff)
                    put("profit", e.profit)
                    put("expenses", e.expenses)
                    put("net_profit", e.netProfit)
                })
            }
        }
    }

    private suspend fun restockLogToJson(): JSONArray {
        val rows = db.restockLogDao().getAll().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id) // String PK — Supabase column must be text
                    put("user_id", uid())
                    put("date", e.date)
                    put("items_json", e.itemsJson)
                    put("total_cost", e.totalCost)
                })
            }
        }
    }

    private suspend fun smsLogToJson(): JSONArray {
        val rows = db.smsLogDao().getAllLogs().first()
        return JSONArray().apply {
            rows.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("user_id", uid())
                    put("debt_id", e.debtId)
                    put("customer_name", e.customerName)
                    put("phone_number", e.phoneNumber)
                    put("type", e.type)
                    put("message_body", e.messageBody)
                    put("status", e.status)
                    put("timestamp", e.timestamp)
                })
            }
        }
    }
}
```

**Verify:** compiles (no runtime test until Task 4 SQL is applied).

## Task 6 — AppViewModel.syncNow() (corrected pattern)

**Why corrected:** the draft assumed `AndroidViewModel` with `getApplication()` + a `database` field. Actual code (`ui/screens/AppViewModel.kt:27`) is `class AppViewModel : ViewModel()` with `private var repository: AppRepository?` + `fun initRepository(repo)`. Converting to `AndroidViewModel` would break 15+ `@Preview`s using `remember { AppViewModel() }`. So we follow the existing `initRepository` pattern with `initSync(context, db)`.

**File: `ui/screens/AppViewModel.kt`** — add imports:

```kotlin
import android.content.Context
import com.example.tindago.data.local.AppDatabase
import com.example.tindago.data.sync.SupabaseConfig
import com.example.tindago.data.sync.SyncRepository
```

Add near the `private var repository` declaration (~line 519):

```kotlin
// ── Cloud sync (Phase 2) ──────────────────────────────────
private var syncRepo: SyncRepository? = null
private var appContext: Context? = null

val syncStatus = MutableStateFlow<String?>(null)
val isSyncing = MutableStateFlow(false)
val isLoggedIn = MutableStateFlow(false)

/**
 * Mirrors initRepository(): called once from NavGraph with the
 * application context + Room singleton. Idempotent.
 */
fun initSync(context: Context, db: AppDatabase) {
    if (syncRepo == null) {
        appContext = context.applicationContext
        syncRepo = SyncRepository(appContext!!, db)
    }
}

fun checkLoginStatus() {
    val ctx = appContext ?: return
    isLoggedIn.value = SupabaseConfig.isLoggedIn(ctx)
}

fun signIn(email: String, password: String) {
    val repo = syncRepo ?: return
    viewModelScope.launch {
        syncStatus.value = "Signing in..."
        repo.signIn(email.trim(), password)
            .onSuccess { msg ->
                syncStatus.value = msg
                isLoggedIn.value = true
            }
            .onFailure { e ->
                syncStatus.value = "Login failed: ${e.message}"
            }
    }
}

fun syncNow() {
    val repo = syncRepo ?: return
    if (isSyncing.value) return
    viewModelScope.launch {
        isSyncing.value = true
        syncStatus.value = "Syncing..."
        repo.syncAll()
            .onSuccess { msg -> syncStatus.value = msg }
            .onFailure { e -> syncStatus.value = "Sync failed: ${e.message}" }
        isSyncing.value = false
    }
}

fun signOut() {
    val ctx = appContext ?: return
    SupabaseConfig.logout(ctx)
    isLoggedIn.value = false
    syncStatus.value = "Signed out"
}

override fun onCleared() {
    syncRepo?.close()
    super.onCleared()
}
```

**File: `ui/navigation/NavGraph.kt`** — next to the existing `initRepository` call (~line 79):

```kotlin
LaunchedEffect(Unit) {
    appViewModel.initRepository(repository)
    appViewModel.initSync(context, app.database)
}
```

**Verify:** compiles; previews still work (no constructor change).

## Task 7 — Sync UI in SettingsScreen

Add a new `☁️ Cloud Sync` card at the **top** of the settings scroll column, above the Backup/Export/Import section. Nullable `AppViewModel?` so previews (which pass `null`) keep working.

**File: `ui/screens/SettingsScreen.kt`** — add imports:

```kotlin
import androidx.compose.ui.text.input.PasswordVisualTransformation
```

Append this composable (e.g. end of file, near `SettingsScreenPreview`):

```kotlin
@Composable
fun CloudSyncSection(viewModel: AppViewModel?) {
    if (viewModel == null) return
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.checkLoginStatus() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "☁️ Cloud Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!isLoggedIn) {
                Text(
                    "Sign in to sync your data to the cloud and access reports on the web.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.signIn(email, password) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign In")
                }
            } else {
                Text(
                    "✅ Connected",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.syncNow() },
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSyncing) "Syncing..." else "Sync Now")
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.signOut() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            }

            syncStatus?.let { status ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("fail", ignoreCase = true))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

Then inside `SettingsScreen`'s scrollable column, call `CloudSyncSection(viewModel = viewModel)` **above** the existing Backup/Export/Import section.

**Verify:** `./gradlew :app:assembleDebug` + open Settings → card renders → Sign In flow works.

---

## Testing Checklist

1. **Build** — `./gradlew :app:assembleDebug`. Fix any import / DAO-name mismatches.
2. **Sign In** — Settings → enter Supabase auth user (e.g. `tindago@test.com` / `password123`; must exist in Supabase Auth) → Sign In → expect `Signed in as ...`.
3. **Sync** — Tap Sync Now → expect `Synced X tables successfully`.
4. **Verify** — Supabase Dashboard → Table Editor → `products` shows phone rows.
5. **Offline** — airplane mode → record sale (must work normally) → online → Sync Now → sale appears in Supabase.

## Defense Narrative

1. Mobile works fully offline (unchanged behavior).
2. One-tap cloud sync pushes all 10 tables to Supabase.
3. Supabase dashboard shows real tables.
4. `user_id` on every row answers "what if two stores sync?" (multi-tenant).

## Open Decisions

- [ ] Ktor vs raw `HttpURLConnection` (zero-dep, ~40 lines, no version risk)?
- [ ] Paste real `SUPABASE_URL` + `anon key` before Task 3 test.
- [ ] Row-level security (RLS) policies on Supabase tables (`auth.uid() = user_id`)? Out of scope for Phase 2, needed before production.
- [ ] Pull/down-sync (Supabase → phone)? Out of scope — Phase 2 is push-only.
