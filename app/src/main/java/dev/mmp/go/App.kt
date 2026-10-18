package dev.mmp.go

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header(
                                "User-Agent",
                                "mmp-go/0.2.0 (Android; personal journey recorder)",
                            )
                            .build()
                    )
                }
                .build()
        )
        for ((code, action) in listOf(1 to "geofence", 2 to "activity")) {
            val intent = Intent().setClassName(this, "dev.mmp.go.TriggerReceiver").setAction(action)
            val flags =
                PendingIntent.FLAG_NO_CREATE or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            PendingIntent.getBroadcast(this, code, intent, flags)?.let { pending ->
                if (action == "geofence" && Tracking.play(this)) {
                    try {
                        LocationServices.getGeofencingClient(this).removeGeofences(pending)
                    } catch (_: RuntimeException) {}
                }
                pending.cancel()
            }
        }
        Store.get(this).let { s -> s.executor.execute { s.recover() } }
    }
}
