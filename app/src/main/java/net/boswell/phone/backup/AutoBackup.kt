package net.boswell.phone.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A backup every week into a folder the person picked, while the phone
 * charges, keeping the newest [KEEP]. API keys are never included: these
 * files sit in a folder, possibly a synced one.
 */
object AutoBackup {
    const val KEEP = 3
    private const val EVERY_DAYS = 7
    private const val WORK = "auto-backup"
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun folder(c: Context): Uri? = p(c).getString("backup_folder", null)?.let(Uri::parse)
    fun last(c: Context): Long = p(c).getLong("backup_last", 0)
    fun lastResult(c: Context): String? = p(c).getString("backup_last_result", null)

    /** Turn on with [tree] (from the folder picker), or off with null. */
    fun setFolder(c: Context, tree: Uri?) {
        val old = folder(c)
        if (old != null && old != tree) runCatching {
            c.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (tree == null) {
            p(c).edit().remove("backup_folder").apply()
            WorkManager.getInstance(c).cancelUniqueWork(WORK)
            return
        }
        c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        p(c).edit().putString("backup_folder", tree.toString()).apply()
        schedule(c)
    }

    /** A readable name for the chosen folder. */
    fun folderName(c: Context): String? = folder(c)?.let { t ->
        runCatching { DocumentsContract.getTreeDocumentId(t).substringAfterLast(':').ifBlank { "the chosen folder" } }.getOrNull()
    }

    fun schedule(c: Context) {
        if (folder(c) == null) return
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()).build())
    }

    fun due(c: Context) = folder(c) != null && System.currentTimeMillis() - last(c) > EVERY_DAYS * 86_400_000L

    /** Write one backup now and drop the oldest beyond [KEEP]. Returns what happened, in words. */
    fun runNow(c: Context): String {
        val tree = folder(c) ?: return "no folder chosen"
        val result = runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(c.contentResolver, parent, "application/zip", Backup.suggestedName())
                ?: error("the folder wouldn't take a new file")
            val s = try { Backup.export(c, doc, includeKeys = false) }
                catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(c.contentResolver, doc) }; throw e }
            prune(c, tree)
            "Backed up ${s.recordings} recordings"
        }.getOrElse { "Backup failed: ${it.message}" }
        p(c).edit().putString("backup_last_result", result).apply()
        if (!result.startsWith("Backup failed")) p(c).edit().putLong("backup_last", System.currentTimeMillis()).apply()
        return result
    }

    /** Keep the newest [KEEP] Boswell backups in the folder; never touch anything else. */
    private fun prune(c: Context, tree: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = c.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(Triple(cur.getString(0), cur.getString(1), cur.getLong(2))) }
        }.orEmpty().filter { it.second.matches(Regex("""boswell-backup-\d{4}-\d{2}-\d{2}( \(\d+\))?\.zip""")) }
        for (old in ours.sortedByDescending { it.third }.drop(KEEP))
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, old.first)) }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (AutoBackup.due(applicationContext)) net.boswell.phone.capture.CaptureRepository.log("auto backup: " + AutoBackup.runNow(applicationContext))
        Result.success()
    }
}
