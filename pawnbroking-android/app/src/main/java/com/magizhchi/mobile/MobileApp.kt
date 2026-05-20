package com.magizhchi.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                "pawn_events", "Pawn events", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Real-time updates from your shop's desktop app" }
        )
    }
}
