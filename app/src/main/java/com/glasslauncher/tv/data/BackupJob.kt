package com.glasslauncher.tv.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** The weekly backup (Settings › System): a backup in the TV's Downloads, locked with the code chosen then. */
class BackupJob : JobService() {
    companion object {
        private const val ID = 0x6d3
        private const val WEEK = 7 * 24 * 3600_000L

        fun arm(c: Context) {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            js.schedule(JobInfo.Builder(ID, ComponentName(c, BackupJob::class.java)).setPeriodic(WEEK).setPersisted(true).build())
        }

        fun cancel(c: Context) {
            (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(ID)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val code = getSharedPreferences("glass", Context.MODE_PRIVATE).getString("backupAutoCode", "").orEmpty()
            if (code.isNotEmpty()) runCatching { Backup.saveToDownloads(this, Backup.make(this, code)) }
                .onSuccess { Glog.w("Weekly backup saved") }.onFailure { Glog.w("Weekly backup failed", it) }
            // And to the WebDAV folder, when there is one (Settings › System › Backup to WebDAV).
            if (code.isNotEmpty() && WebDav.conf(this).ready) runCatching { WebDav.backUp(this, code) }
                .onFailure { Glog.w("Weekly WebDAV backup failed", it) }
            jobFinished(params, false)
        }, "backup-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
