package com.magizhchi.mobile.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.magizhchi.mobile.MainActivity
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.DeviceReq
import com.magizhchi.mobile.data.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class PawnFcmService : FirebaseMessagingService() {

    @Inject lateinit var api: Api
    @Inject lateinit var store: TokenStore

    override fun onNewToken(token: String) {
        val userId = store.userId
        if (userId == 0L) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                api.registerDevice(DeviceReq(userId, token, android.os.Build.MODEL ?: "android"))
            }
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val title = msg.notification?.title ?: msg.data["title"] ?: "Update"
        val body  = msg.notification?.body  ?: msg.data["body"]  ?: ""
        val table = msg.data["table"]; val rowPk = msg.data["row_pk"]

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("table", table); putExtra("row_pk", rowPk)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, Random.nextInt(),
            intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val n = NotificationCompat.Builder(this, "pawn_events")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Random.nextInt(), n)
    }
}
