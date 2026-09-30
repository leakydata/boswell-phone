package net.boswell.phone.todo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.boswell.phone.R
import net.boswell.phone.assistant.AssistantNotify
import net.boswell.phone.ui.MainActivity
import java.util.concurrent.TimeUnit

/** A to-do with a due time becomes a notification then, with a Done button. */
object TodoReminders {
    private fun work(id: Long) = "todo-$id"

    fun schedule(context: Context, id: Long, dueEpoch: Double) {
        val delay = ((dueEpoch * 1000).toLong() - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork(work(id), ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TodoDueWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("id" to id)).build())
    }

    fun cancel(context: Context, id: Long) = WorkManager.getInstance(context).cancelUniqueWork(work(id))

    fun notify(context: Context, t: Todo) {
        AssistantNotify.ensureChannels(context)
        val done = PendingIntent.getBroadcast(context, t.id.toInt(),
            Intent(context, TodoDoneReceiver::class.java).putExtra("id", t.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).putExtra("open", "todo"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, AssistantNotify.ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(t.text)
            .setContentText(t.category)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "Done", done)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_BASE + t.id.toInt(), n) }
    }

    const val ID_BASE = 50_000
}

class TodoDueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = TodoStore(applicationContext)
        try {
            val t = store.get(inputData.getLong("id", -1)) ?: return Result.success()
            if (!t.done) TodoReminders.notify(applicationContext, t)
        } finally { store.close() }
        return Result.success()
    }
}

/** The notification's Done button. */
class TodoDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val store = TodoStore(context)
        try { store.setDone(id, true) } finally { store.close() }
        NotificationManagerCompat.from(context).cancel(TodoReminders.ID_BASE + id.toInt())
    }
}
