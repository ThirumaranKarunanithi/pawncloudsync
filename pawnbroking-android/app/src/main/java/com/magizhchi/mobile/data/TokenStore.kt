package com.magizhchi.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "pb_auth",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    var shopId: String?
        get() = prefs.getString("shop_id", null)
        set(v) { prefs.edit().putString("shop_id", v).apply() }

    var userId: Long
        get() = prefs.getLong("user_id", 0)
        set(v) { prefs.edit().putLong("user_id", v).apply() }

    fun clear() = prefs.edit().clear().apply()
}
