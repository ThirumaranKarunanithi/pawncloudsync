package com.pawnbroking.app.services;

import android.content.Context;

import com.pawnbroking.app.config.AppConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Local cache of downloaded backup files, plus the retention policy.
 *
 * Files live in the app's private external files dir under
 * {@code backups/<shopId>/} so they are removed on uninstall and never
 * pollute the user's gallery.
 *
 * RETENTION: we keep the newest {@link AppConfig#BACKUP_KEEP_LOCAL} files and
 * prune older ones ONLY after a newer download has completed successfully
 * (see {@link #pruneOldCopies}). Deleting the previous copy before the new one
 * is verified would leave the phone with nothing if a backup arrives truncated
 * — the whole point of holding a backup is having more than one restore point.
 * Pruning never touches the cloud/box copies, only this local cache.
 */
public final class BackupStore {

    private BackupStore() {}

    /** Root dir for this shop's cached backups (created on demand). */
    public static File dir(Context ctx) {
        String shop = ApiService.getCurrentShopId(ctx);
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();      // fallback: internal
        File d = new File(new File(base, "backups"), shop == null ? "default" : shop);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Where a given backup file name is (or would be) stored locally. */
    public static File localFile(Context ctx, String fileName) {
        return new File(dir(ctx), safeName(fileName));
    }

    /** True if this backup has already been fully downloaded. */
    public static boolean isDownloaded(Context ctx, String fileName) {
        File f = localFile(ctx, fileName);
        return f.exists() && f.length() > 0;
    }

    /** Downloaded backups, newest (by last-modified) first. */
    public static List<File> localFiles(Context ctx) {
        File[] arr = dir(ctx).listFiles(f ->
                f.isFile() && !f.getName().endsWith(".part"));
        List<File> out = new ArrayList<>();
        if (arr != null) {
            out.addAll(Arrays.asList(arr));
            out.sort(Comparator.comparingLong(File::lastModified).reversed());
        }
        return out;
    }

    /**
     * Keeps the newest {@link AppConfig#BACKUP_KEEP_LOCAL} local copies and
     * deletes the rest. Call this ONLY after a successful download so the
     * device is never left without a usable restore point.
     *
     * @return number of files deleted
     */
    public static int pruneOldCopies(Context ctx) {
        List<File> files = localFiles(ctx);
        int keep = Math.max(AppConfig.BACKUP_KEEP_LOCAL, 1);
        int deleted = 0;
        for (int i = keep; i < files.size(); i++) {
            if (files.get(i).delete()) deleted++;
        }
        return deleted;
    }

    /** Total bytes used by the local cache. */
    public static long usedBytes(Context ctx) {
        long total = 0;
        for (File f : localFiles(ctx)) total += f.length();
        return total;
    }

    /** Deletes every locally cached backup (cloud copies are untouched). */
    public static int clearAll(Context ctx) {
        int n = 0;
        for (File f : localFiles(ctx)) if (f.delete()) n++;
        return n;
    }

    /** Strips path separators so a crafted file_name can't escape the dir. */
    public static String safeName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "backup.bin";
        String n = fileName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
