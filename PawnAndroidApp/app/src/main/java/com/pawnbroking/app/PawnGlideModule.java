package com.pawnbroking.app;

import android.content.Context;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Replaces Glide's default {@code HttpUrlFetcher} (which has poor recovery
 * from dropped sockets) with an OkHttp client that auto-retries on
 * connection failure. Also raises the disk-cache size so opening a bill
 * a second time hits the cache instead of the network — the speed-up
 * the user asked for.
 */
@GlideModule
public final class PawnGlideModule extends AppGlideModule {

    private static final long DISK_CACHE_SIZE = 200L * 1024 * 1024; // 200 MB

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_SIZE));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout   (30, TimeUnit.SECONDS)
                .writeTimeout  (30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // Modest keep-alive: reuse connections within a few seconds
                // (most bill screens load 3-6 images in a burst) but expire
                // them quickly so a backgrounded app doesn't come back with
                // a stale socket.
                .connectionPool(new okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
                .build();
        registry.replace(GlideUrl.class, InputStream.class,
                         new OkHttpUrlLoader.Factory(client));
    }

    /** Don't pull in any AndroidManifest-declared GlideModules — none ship
     *  with our libs today and parsing the manifest at app start wastes time. */
    @Override
    public boolean isManifestParsingEnabled() { return false; }
}
