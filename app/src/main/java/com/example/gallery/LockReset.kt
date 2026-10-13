package com.example.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object LockReset {
    const val DEFAULT_MS = 3_600_000L
    private const val DAY = 86_400_000L
    private const val CH = "lock_reset"

    private fun now() = System.currentTimeMillis()
    private fun gl(k: String, d: Long = 0L) = Prefs.getStr(k, d.toString()).toLongOrNull() ?: d
    private fun pl(k: String, v: Long) = Prefs.putStr(k, v.toString())

    fun running() = gl("reset_end") > now()
    fun remaining() = (gl("reset_end") - now()).coerceAtLeast(0L)
    fun duration() = gl("reset_dur", DEFAULT_MS).coerceIn(60_000L, DAY)
    fun setDuration(ms: Long) = pl("reset_dur", ms.coerceIn(60_000L, DAY))

    fun resetVisible() = gl("reset_vis") > now()
    fun visLeft() = (gl("reset_vis") - now()).coerceAtLeast(0L)
    fun timerVisible() = resetVisible() && Prefs.getStr("reset_timer_vis") == "1"

    fun toggleReset(): Boolean {
        val on = !resetVisible()
        pl("reset_vis", if (on) now() + 30 * 60_000L else 0L)
        if (!on) { Prefs.putStr("reset_timer_vis", "0"); Prefs.putStr("reset_stop_vis", "0") }
        return on
    }

    fun toggleTimer() {
        if (!resetVisible()) return
        val on = Prefs.getStr("reset_timer_vis") != "1"
        Prefs.putStr("reset_timer_vis", if (on) "1" else "0")
        if (!on) Prefs.putStr("reset_stop_vis", "0")
    }

    fun stopVisible() = timerVisible() && Prefs.getStr("reset_stop_vis") == "1"

    fun toggleStop() {
        if (!timerVisible()) return
        Prefs.putStr("reset_stop_vis", if (stopVisible()) "0" else "1")
    }

    fun isProtected(key: String) = running() && key in Prefs.getSet("reset_albums")

    private fun clearBackup() {
        Prefs.putStr("lock_hash_bak", "")
        Prefs.putStr("lock_salt_bak", "")
        Prefs.setSet("reset_locked_bak", emptySet())
        Prefs.setSet("reset_albums", emptySet())
        Prefs.putStr("reset_stop_vis", "0")
    }

    /** Timer shesh hole albums abar show hoy. State change hole true. */
    fun tick(): Boolean {
        val end = gl("reset_end")
        if (end == 0L || end > now()) return false
        val hidden = Prefs.getSet("hidden").toMutableSet()
        hidden.removeAll(Prefs.getSet("reset_albums"))
        Prefs.setSet("hidden", hidden)
        clearBackup()
        pl("reset_end", 0L)
        Changes.dirty = true
        return true
    }

    fun perform(ctx: Context) {
        tick()
        if (running()) return
        val locked = Prefs.getSet("locked")
        val hidden = Prefs.getSet("hidden")
        Prefs.putStr("lock_hash_bak", Prefs.getStr("lock_hash"))
        Prefs.putStr("lock_salt_bak", Prefs.getStr("lock_salt"))
        Prefs.setSet("reset_locked_bak", locked)
        Prefs.setSet("reset_albums", locked - hidden)
        Prefs.setSet("hidden", hidden + locked)
        Prefs.setSet("locked", emptySet())
        Prefs.putStr("lock_hash", "")
        Prefs.putStr("lock_salt", "")
        pl("reset_end", now() + duration())
        pl("reset_vis", 0L)
        Prefs.putStr("reset_timer_vis", "0")
        Prefs.putStr("reset_stop_vis", "0")
        Changes.dirty = true
        notifyNow(ctx)
    }

    /** Password diye reset cancel: ager password, locked albums, hidden state sob ferot ashe */
    fun stop(ctx: Context) {
        if (!running()) return
        val hidden = Prefs.getSet("hidden").toMutableSet()
        hidden.removeAll(Prefs.getSet("reset_albums"))
        Prefs.setSet("hidden", hidden)
        Prefs.setSet("locked", Prefs.getSet("reset_locked_bak"))
        Prefs.putStr("lock_hash", Prefs.getStr("lock_hash_bak"))
        Prefs.putStr("lock_salt", Prefs.getStr("lock_salt_bak"))
        clearBackup()
        pl("reset_end", 0L)
        pl("reset_vis", 0L)
        Prefs.putStr("reset_timer_vis", "0")
        try { ctx.getSystemService(NotificationManager::class.java).cancel(1001) } catch (e: Exception) { }
        Changes.dirty = true
    }

    fun notifyNow(ctx: Context) {
        val end = gl("reset_end")
        if (end <= now()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Lock reset", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle("Your lock password is reset")
            .setContentText("Lock albums will be available again when the countdown ends")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(end)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(end - now())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pi)
            .build()
        try { nm.notify(1001, n) } catch (e: Exception) { }
    }
}