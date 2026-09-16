package com.example.tindago.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.tindago.data.AppRepository
import com.example.tindago.ui.localization.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One on-disk backup file. [id] is an absolute path (app folder) or a document URI string (SAF tree). */
data class BackupFileInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdAt: Long
)

/** Outcome of a backup write. */
data class BackupResult(
    val success: Boolean,
    val fileName: String? = null,
    val locationLabel: String? = null,
    val error: String? = null
)

/**
 * Owns backup file storage (V3.0 — Automatic Backup feature).
 *
 * Files are written either to the default **app-specific folder**
 * (`Android/data/<pkg>/files/backups`, no permission required) or to a
 * user-chosen **SAF tree** (`TindaGo/backups`). Retention only ever prunes
 * automatic backups; manual and pre-restore snapshots are never auto-deleted.
 */
object BackupManager {

    const val AUTO_PREFIX = "tindago-autobackup-"
    const val MANUAL_PREFIX = "tindago-backup-"
    const val PRE_RESTORE_PREFIX = "tindago-prerestore-"

    private const val APP_DIR = "TindaGo"
    private const val BACKUP_SUBDIR = "backups"
    private const val DEFAULT_DIR = "backups"
    private const val FILE_SUFFIX = ".json"

    // ── Location abstraction ────────────────────────────────────────────

    private interface Location {
        fun label(context: Context): String
        suspend fun write(context: Context, name: String, bytes: ByteArray): Boolean
        suspend fun list(context: Context): List<BackupFileInfo>
        suspend fun delete(context: Context, id: String): Boolean
        fun isAccessible(context: Context): Boolean
    }

    /** Default location — app-specific external storage; always writable, no permission. */
    private class AppFolderLocation(private val dir: File) : Location {
        override fun label(context: Context): String = dir.absolutePath

        override suspend fun write(context: Context, name: String, bytes: ByteArray): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    true
                } catch (e: Exception) {
                    false
                }
            }

        override suspend fun list(context: Context): List<BackupFileInfo> =
            withContext(Dispatchers.IO) {
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
                    ?.map { BackupFileInfo(it.absolutePath, it.name, it.length(), it.lastModified()) }
                    ?: emptyList()
            }

        override suspend fun delete(context: Context, id: String): Boolean =
            withContext(Dispatchers.IO) { File(id).delete() }

        override fun isAccessible(context: Context): Boolean = true
    }

    /** Custom location — a SAF tree the user granted persistent access to. */
    private class TreeLocation(private val treeUri: Uri) : Location {

        override fun label(context: Context): String =
            displayName(context, DocumentsContract.getTreeDocumentId(treeUri)) ?: treeUri.toString()

        private fun displayName(context: Context, docId: String): String? = try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }

        /** Walk to `TindaGo/backups`, creating the folders as needed. Returns the backups document id. */
        private fun resolveBackupsDocId(context: Context): String? {
            return try {
                var parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                parentDocId = findOrCreateDir(context, parentDocId, APP_DIR) ?: return null
                findOrCreateDir(context, parentDocId, BACKUP_SUBDIR)
            } catch (e: Exception) {
                null
            }
        }

        private fun findOrCreateDir(context: Context, parentDocId: String, name: String): String? {
            findChildDocId(context, parentDocId, name)?.let { return it }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val created = DocumentsContract.createDocument(
                context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            return DocumentsContract.getDocumentId(created)
        }

        private fun findChildDocId(context: Context, parentDocId: String, name: String): String? {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return c.getString(0)
                }
            }
            return null
        }

        override suspend fun write(context: Context, name: String, bytes: ByteArray): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val dirDocId = resolveBackupsDocId(context) ?: return@withContext false
                    val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
                    val fileUri = DocumentsContract.createDocument(
                        context.contentResolver, dirUri, BackupSerializer.MIME_TYPE, name
                    ) ?: return@withContext false
                    context.contentResolver.openOutputStream(fileUri, "wt")?.use { it.write(bytes) }
                        ?: return@withContext false
                    true
                } catch (e: Exception) {
                    false
                }
            }

        override suspend fun list(context: Context): List<BackupFileInfo> =
            withContext(Dispatchers.IO) {
                val dirDocId = resolveBackupsDocId(context) ?: return@withContext emptyList()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
                val out = mutableListOf<BackupFileInfo>()
                try {
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
                        ),
                        null, null, null
                    )?.use { c ->
                        val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modifiedIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        while (c.moveToNext()) {
                            val name = c.getString(nameIdx) ?: continue
                            if (!name.endsWith(FILE_SUFFIX)) continue
                            val docId = c.getString(idIdx)
                            out.add(
                                BackupFileInfo(
                                    id = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                                    name = name,
                                    sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                                    createdAt = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // fall through with whatever was collected
                }
                out
            }

        override suspend fun delete(context: Context, id: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(id))
                } catch (e: Exception) {
                    false
                }
            }

        override fun isAccessible(context: Context): Boolean = try {
            resolveBackupsDocId(context) != null
        } catch (e: Exception) {
            false
        }
    }

    // ── Location resolution ─────────────────────────────────────────────

    private fun resolveLocation(context: Context, settings: AppSettings): Location {
        val uriStr = settings.backupLocationUri
        if (uriStr.isNotBlank()) {
            val tree = TreeLocation(Uri.parse(uriStr))
            if (tree.isAccessible(context)) return tree
        }
        return defaultLocation(context)
    }

    private fun defaultLocation(context: Context): Location {
        val dir = context.getExternalFilesDir(DEFAULT_DIR) ?: File(context.filesDir, DEFAULT_DIR)
        return AppFolderLocation(dir)
    }

    /** Human-readable path of the location a backup will be written to. */
    fun locationLabel(context: Context, settings: AppSettings): String =
        resolveLocation(context, settings).label(context)

    /** True when a persisted custom location exists AND is currently accessible. */
    fun hasUsableCustomLocation(context: Context, settings: AppSettings): Boolean {
        val uriStr = settings.backupLocationUri
        if (uriStr.isBlank()) return false
        return TreeLocation(Uri.parse(uriStr)).isAccessible(context)
    }

    /** Validate a freshly picked tree URI (already permission-granted by the caller). */
    fun isTreeAccessible(context: Context, treeUri: String): Boolean =
        TreeLocation(Uri.parse(treeUri)).isAccessible(context)

    // ── Reading / building ──────────────────────────────────────────────

    /** Build the current versioned backup JSON from persisted Room data + settings. */
    suspend fun buildFromRepository(
        repository: AppRepository,
        settings: AppSettings
    ): JSONObject {
        val data = BackupSerializer.BackupData(
            products = repository.getAllProducts().first(),
            dailyEntry = repository.getLatestDailyEntry().first(),
            specificSales = repository.getAllSpecificSales().first(),
            debts = repository.getAllDebts().first(),
            payments = repository.getAllPayments().first(),
            debtTransactions = repository.getAllDebtTransactions().first(),
            expenses = repository.getAllExpenses().first(),
            restockLogs = repository.getAllRestockLogs().first(),
            endOfDay = repository.getLatestEndOfDayData().first(),
            settings = BackupSerializer.readSettings(settings)
        )
        return BackupSerializer.buildEnvelope(System.currentTimeMillis(), data)
    }

    fun generateFileName(manual: Boolean, preRestore: Boolean = false, now: Long = System.currentTimeMillis()): String {
        val prefix = when {
            preRestore -> PRE_RESTORE_PREFIX
            manual -> MANUAL_PREFIX
            else -> AUTO_PREFIX
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return "$prefix$stamp$FILE_SUFFIX"
    }

    /** Read the raw text of a backup from any SAF document URI. */
    suspend fun readBackupText(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Write / list / prune ────────────────────────────────────────────

    suspend fun createBackup(
        context: Context,
        repository: AppRepository,
        settings: AppSettings,
        manual: Boolean,
        preRestore: Boolean = false
    ): BackupResult {
        val now = System.currentTimeMillis()
        val fileName = generateFileName(manual = manual, preRestore = preRestore, now = now)
        val location = resolveLocation(context, settings)
        val label = location.label(context)
        return try {
            val json = buildFromRepository(repository, settings)
            val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
            val ok = location.write(context, fileName, bytes)
            if (ok) {
                if (!preRestore) pruneAutomatic(context, settings)
                settings.lastBackupAt = now
                settings.lastBackupStatus = "success"
                settings.lastBackupFile = fileName
                settings.lastBackupError = ""
                BackupResult(true, fileName, label)
            } else {
                recordFailure(settings, now, label)
                BackupResult(false, null, label, "write_failed")
            }
        } catch (e: Exception) {
            recordFailure(settings, now, label)
            BackupResult(false, null, label, e.message ?: "unknown_error")
        }
    }

    private fun recordFailure(settings: AppSettings, at: Long, label: String) {
        settings.lastBackupAt = at
        settings.lastBackupStatus = "failed"
        settings.lastBackupError = "write_failed"
        settings.lastBackupFile = ""
    }

    suspend fun listBackups(context: Context, settings: AppSettings): List<BackupFileInfo> =
        resolveLocation(context, settings).list(context).sortedByDescending { it.name }

    /** Keep only the newest [AppSettings.backupRetentionCount] automatic backups. */
    suspend fun pruneAutomatic(context: Context, settings: AppSettings) {
        val keep = settings.backupRetentionCount.coerceAtLeast(1)
        val location = resolveLocation(context, settings)
        location.list(context)
            .filter { it.name.startsWith(AUTO_PREFIX) }
            .sortedByDescending { it.name }
            .drop(keep)
            .forEach { location.delete(context, it.id) }
    }

    suspend fun deleteBackup(context: Context, settings: AppSettings, file: BackupFileInfo): Boolean =
        resolveLocation(context, settings).delete(context, file.id)
}
