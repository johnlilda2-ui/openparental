package app.monii.managed

import android.app.Application
import android.util.Log
import app.monii.managed.identity.DeviceStore
import app.monii.managed.supervisor.WatchdogScheduler
import app.monii.managed.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessaging

class MoniiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchdogScheduler.schedule(this)
        SyncScheduler.schedule(this)
        val store = DeviceStore(this)
        // Always ask the backend to accept the current Firebase token again.
        // This repairs stale database registrations after FCM invalidates a token.
        store.requestFcmSync()
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                store.setFcmToken(token)
                SyncScheduler.runOnce(this)
            }
            .addOnFailureListener { error ->
                Log.e("MoniiApp", "Could not refresh FCM registration token", error)
            }
    }
}
