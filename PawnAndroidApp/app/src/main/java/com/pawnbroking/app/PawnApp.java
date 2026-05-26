package com.pawnbroking.app;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.pawnbroking.app.services.ApiService;

public class PawnApp extends Application {

    public static final String PREFS      = "pawn_prefs";
    public static final String KEY_THEME  = "theme_mode";
    public static final String THEME_DARK = "DARK";
    public static final String THEME_LIGHT = "LIGHT";

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(this);
        ApiService.bindContext(this);
    }

    /** Call this whenever the saved preference changes. */
    public static void applyTheme(android.content.Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(KEY_THEME, THEME_DARK);
        if (THEME_LIGHT.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    public static boolean isDark(android.content.Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return THEME_DARK.equals(prefs.getString(KEY_THEME, THEME_DARK));
    }

    public static void saveTheme(android.content.Context ctx, String mode) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_THEME, mode).apply();
    }
}
