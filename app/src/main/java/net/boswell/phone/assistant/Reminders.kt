package net.boswell.phone.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** A reminder the assistant set: a notification later, nothing more. */
object Reminders {
    fun schedule(context: Context, text: String, minutes: Long) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf("text" to text))
                .build()
        )
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AssistantNotify.post(applicationContext, AssistantNotify.ANSWERS, "Reminder", inputData.getString("text") ?: return Result.success())
        return Result.success()
    }
}
