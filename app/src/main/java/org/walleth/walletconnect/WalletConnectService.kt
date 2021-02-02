package org.walleth.walletconnect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.ligi.kaxt.getNotificationManager
import org.walletconnect.Session
import org.walletconnect.Session.MethodCall
import org.walletconnect.Session.MethodCall.*
import org.walletconnect.impls.WCSessionStore
import org.walleth.R
import timber.log.Timber
import kotlin.random.Random

class WalletConnectService : LifecycleService() {
    val moshi: Moshi by inject()
    private val okhttp: OkHttpClient by inject()
    private val sessionStore: WCSessionStore by inject()

    val handler by lazy { WalletConnectHandler(moshi, okhttp, sessionStore) }

    private val binder = LocalBinder()

    var uiPendingCallback: (() -> Unit)? = null

    var uiPendingCall: MethodCall? = null
    var uiPendingStatus: Session.Status? = null

    val notificationId = Random.nextInt()

    fun takeCall(action: (c: MethodCall) -> Unit) {
        uiPendingCall?.let {
            action(it)
            getNotificationManager().cancel(notificationId)
            uiPendingCall = null
        }

    }

    private val sessionCallback = object : Session.Callback {
        override fun onMethodCall(call: MethodCall) {
            uiPendingCall = call
            uiPendingCallback?.invoke()


            if (uiPendingCallback == null && (call is Custom || call is SignMessage || call is SendTransaction)) {
                val wcIntent = Intent(baseContext, WalletConnectConnectionActivity::class.java)
                val contentIntent = PendingIntent.getActivity(baseContext, 0, wcIntent, PendingIntent.FLAG_UPDATE_CURRENT)

                if (Build.VERSION.SDK_INT > 25) {
                    val channel = NotificationChannel("walletconnect", "Geth Service", NotificationManager.IMPORTANCE_HIGH)
                    channel.description = "WalletConnectNotifications"
                    getNotificationManager().createNotificationChannel(channel)
                }

                val notification = NotificationCompat.Builder(this@WalletConnectService, "walletconnect").apply {
                    setContentTitle("WalletConnect Interaction")

                    if (call is Custom || call is SignMessage) {
                        setContentText("Please sign the message")
                    } else {
                        setContentText("Please sign the transaction")
                    }
                    setAutoCancel(true)
                    setContentIntent(contentIntent)
                    setSmallIcon(R.drawable.ic_walletconnect_logo)
                }.build()

                getNotificationManager().notify(notificationId, notification)
            }

        }

        override fun onStatus(status: Session.Status) {
            uiPendingStatus = status
            uiPendingCallback?.invoke()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.data.let {
            handler.processURI(it.toString())
            handler.session?.addCallback(sessionCallback)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.session?.reject()
        handler.session?.kill()
    }

    inner class LocalBinder : Binder() {
        fun getService(): WalletConnectService = this@WalletConnectService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
}