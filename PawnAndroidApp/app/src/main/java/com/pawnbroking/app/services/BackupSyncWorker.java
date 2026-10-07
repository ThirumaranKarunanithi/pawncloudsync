package com.pawnbroking.app.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Daily background fetch of the newest backup file.
 *
 * Runs at most once a day on an unmetered (Wi-Fi) connection — backups are
 * 100MB+, so pulling one over mobile data would be an expensive surprise.
 * After a successful download it prunes older local copies down to
 * {@link com.pawnbroking.app.config.AppConfig#BACKUP_KEEP_LOCAL}.
 */
public class BackupSyncWorker extends Worker {
    private static final String TAG        = "BackupSyncWorker";
    private static final String UNIQUE_JOB = "daily-backup-sync";

    public BackupSyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /** Registers the daily job. Safe to call on every app start (KEEP policy). */
    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)   // Wi-Fi only
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    BackupSyncWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_JOB, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Cancels the daily job (called on logout). */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork(UNIQUE_JOB);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        // Not signed in → nothing to fetch. Not a failure; try again tomorrow.
        if (!ApiService.isLoggedIn(ctx)) return Result.success();

        try {
            JSONArray list = ApiService.listBackupsSync(50);
            if (list.length() == 0) return Result.success();

            // The cloud returns newest-first, so index 0 is the latest backup.
            JSONObject newest = list.optJSONObject(0);
            if (newest == null) return Result.success();

            String fileName  = newest.optString("file_name", "");
            String companyId = newest.optString("company_id", "");
            String relPath   = newest.optString("relative_path", "");
            if (fileName.isEmpty() || companyId.isEmpty()) return Result.success();

            if (BackupStore.isDownloaded(ctx, fileName)) {
                Log.i(TAG, "latest backup already cached: " + fileName);
                return Result.success();
            }

            File dest = BackupStore.localFile(ctx, fileName);
            ApiService.downloadBackupSync(companyId, relPath, fileName, dest);
            Log.i(TAG, "downloaded backup " + fileName + " (" + dest.length() + " bytes)");

            // Only now that a fresh copy is safely on disk do we drop old ones.
            int pruned = BackupStore.pruneOldCopies(ctx);
            if (pruned > 0) Log.i(TAG, "pruned " + pruned + " old local backup(s)");

            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "backup sync failed: " + e);
            return Result.retry();      // transient network/cloud issue
        }
    }
}
