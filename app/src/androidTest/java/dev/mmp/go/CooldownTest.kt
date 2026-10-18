package dev.mmp.go

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mmp.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CooldownTest {
    @Test
    fun cooldownRestoresOnSameBootAndExpiresWithoutWallClockDependency() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cooldown-test-${newId()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(name, mode)
        }
        try {
            val arrival = EndpointCooldown("pair", 1, 100000)
            CooldownStore(isolated, 7) { 100000 }.save(listOf(arrival))
            assertEquals(listOf(arrival), CooldownStore(isolated, 7) { 159999 }.load())
            assertTrue(CooldownStore(isolated, 7) { 160000 }.load().isEmpty())
            assertTrue(CooldownStore(isolated, 8) { 110000 }.load().isEmpty())
            assertTrue(CooldownStore(isolated, 7) { 99999 }.load().isEmpty())
            assertTrue(CooldownStore(isolated, -1) { 110000 }.load().isEmpty())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
