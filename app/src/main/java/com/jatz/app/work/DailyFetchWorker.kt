package com.jatz.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.RemoteDropApi
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Runs once a day, fetches whatever the nightly GitHub Actions curator has
 * published since the last check, and reschedules itself for the following
 * morning. This is the "delivered by 8am" half of JATZ; the other half is
 * .github/workflows/daily.yml, which is what actually finds the records.
 *
 * WorkManager's PeriodicWorkRequest can't target a fixed time-of-day, so this
 * self-chains as a OneTimeWorkRequest instead — the standard workaround for
 * "run daily at a specific local time".
 */
class DailyFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var addedVintage = 0
        var addedModern = 0
        try {
            val index = RemoteDropApi.fetchIndex()
            if (index != null) {
                for (date in index.dates) {
                    if (LibraryStore.hasDrop(applicationContext, date)) continue
                    val drop = RemoteDropApi.fetchDrop(date) ?: continue
                    if (LibraryStore.saveDrop(applicationContext, drop)) {
                        addedVintage += drop.counts.vintage
                        addedModern += drop.counts.modern
                    }
                }
            }
        } catch (_: Exception) {
            // Network hiccup or the nightly job hasn't published yet — retried
            // tomorrow regardless, the app keeps showing the last good drop.
        } finally {
            scheduleNext(applicationContext)
        }

        if (addedVintage + addedModern > 0) {
            notifyNewDrop(applicationContext, addedVintage, addedModern)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "jatz_daily_fetch"

        /** Call once at app startup. KEEP: harmless if a run is already scheduled. */
        fun scheduleInitial(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, buildRequest())
        }

        /** Called by the worker itself after each run — always replaces. */
        private fun scheduleNext(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, buildRequest())
        }

        private fun buildRequest() =
            OneTimeWorkRequestBuilder<DailyFetchWorker>()
                .setInitialDelay(nextDelayMs(), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()

        /** Milliseconds until the next 07:00 local time (today if not passed yet, else tomorrow). */
        private fun nextDelayMs(): Long {
            val now = ZonedDateTime.now()
            var target = now.withHour(7).withMinute(0).withSecond(0).withNano(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return Duration.between(now, target).toMillis().coerceAtLeast(60_000L)
        }
    }
}
