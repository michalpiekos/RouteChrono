package dev.mmp.go

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.mmp.core.ENDPOINT_COOLDOWN_MS
import dev.mmp.core.EndpointCooldown
import org.json.JSONArray
import org.json.JSONObject

internal class CooldownStore(
    context: Context,
    private val boot: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val prefs = context.getSharedPreferences("endpoint_cooldowns", Context.MODE_PRIVATE)

    fun load(): List<EndpointCooldown> {
        if (boot < 0 || prefs.getInt("boot", -1) != boot) return emptyList()
        val time = now()
        return runCatching {
            val array = JSONArray(prefs.getString("arrivals", "[]"))
            (0 until array.length()).mapNotNull { index ->
                val row = array.getJSONObject(index)
                val arrival = EndpointCooldown(row.getString("pair"), row.getInt("side"), row.getLong("mono"))
                arrival.takeIf { it.side in 0..2 && time - it.arrivedMonoMs in 0 until ENDPOINT_COOLDOWN_MS }
            }
        }.getOrDefault(emptyList())
    }

    fun save(cooldowns: List<EndpointCooldown>) {
        val time = now()
        val array = JSONArray()
        cooldowns.filter { time - it.arrivedMonoMs in 0 until ENDPOINT_COOLDOWN_MS }.forEach {
            array.put(JSONObject().put("pair", it.pairId).put("side", it.side).put("mono", it.arrivedMonoMs))
        }
        check(prefs.edit().putInt("boot", boot).putString("arrivals", array.toString()).commit()) {
            "Could not persist endpoint cooldowns"
        }
    }
}
