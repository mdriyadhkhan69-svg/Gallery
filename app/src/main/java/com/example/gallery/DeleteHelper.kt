package com.example.gallery

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Shob jaygar delete ek niyome: setting + confirmation panel + authorization + real result check. */
class DeleteHelper(
    caller: ActivityResultCaller,
    private val ctx: () -> Context,
    private val onDone: (removed: List<Uri>, failed: Int) -> Unit
) {
    private val tag = "GalleryDbg"
    private val chunk = 50
    private val hd = Handler(Looper.getMainLooper())
    private var pending: List<Uri> = emptyList()
    private var queue: List<Uri> = emptyList()
    var quiet = false
    private var toBin = true
    private var rounds = 0
    private var denied = false
    private var busy = false
    private var waitingResult = false
    private var stage = 0
    private var verifyTries = 0
    private var progress: MoveProgress? = null

    private val launcher = caller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        Log.i(tag, "system result ok=$ok busy=$busy waiting=$waitingResult")
        if (!busy || !waitingResult) return@registerForActivityResult
        waitingResult = false
        after(ok)
    }

    fun confirmAndDelete(uris: List<Uri>, onConfirm: () -> Unit = {}) {
        if (uris.isEmpty() || busy) return
        val c = ctx()
        val n = uris.size
        if (Build.VERSION.SDK_INT >= 31 && !MediaStore.canManageMedia(c)) {
            showDeleteSheet(
                c,
                "Allow Gallery to manage media once, so photos move without extra permission panels.",
                "Open settings", Colors.BLUE, Color.WHITE
            ) {
                c.startActivity(
                    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", c.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        if (Prefs.useBin && Deleter.binAvailable()) {
            val msg = if (n == 1) "Move this item to the Recycle Bin?" else "Move $n items to the Recycle Bin?"
            showDeleteSheet(c, msg, "Move to Recycle Bin", Colors.BLUE, Colors.BLUE) { onConfirm(); begin(uris, true) }
        } else {
            val msg = if (n == 1) "Delete this item? This cannot be undone." else "Delete $n items? This cannot be undone."
            showDeleteSheet(c, msg, "Delete", 0xFFFF4D4D.toInt(), Color.WHITE) { onConfirm(); begin(uris, false) }
        }
    }

    fun begin(uris: List<Uri>, bin: Boolean) {
        if (busy || uris.isEmpty()) return
        busy = true
        waitingResult = false
        pending = uris.distinct()
        stage = 0
        queue = emptyList()
        toBin = bin
        rounds = 0
        denied = false
        verifyTries = 0
        progress?.dismiss()
        progress = null
        if (!quiet) {
            progress = try {
                MoveProgress(ctx(), if (bin) "Moving to Recycle Bin…" else "Deleting…").also { it.show(); it.waiting() }
            } catch (e: Exception) { null }
        }
        Log.i(tag, "begin: ${pending.size} items toBin=$bin sdk=${Build.VERSION.SDK_INT} manage=${if (Build.VERSION.SDK_INT >= 31) MediaStore.canManageMedia(ctx()) else false} first=${pending.firstOrNull()}")
        attempt(pending)
    }

    private fun updateProgress() {
        val p = progress ?: return
        try {
            val c = ctx()
            val gone = pending.count { !Deleter.exists(c, it) }
            if (gone > 0) p.set(gone * 95 / pending.size.coerceAtLeast(1))
        } catch (e: Exception) { }
    }

    private fun attempt(list: List<Uri>) {
        updateProgress()
        var rest = list
        while (rest.isNotEmpty()) {
            val part = rest.take(chunk)
            rest = rest.drop(chunk)
            val sender = try {
                if (stage == 0) Deleter.perform(ctx(), part, toBin) else Deleter.performWrite(ctx(), part)
            } catch (e: Exception) {
                Log.e(tag, "perform failed", e); null
            }
            if (sender != null) {
                queue = rest
                waitingResult = true
                try {
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                    return
                } catch (e: Exception) {
                    Log.e(tag, "launch failed", e)
                    waitingResult = false
                    queue = emptyList()
                }
            }
        }
        queue = emptyList()
        finish()
    }

    private fun after(ok: Boolean) {
        if (!ok) {
            denied = true
            queue = emptyList()
            finish()
            return
        }
        updateProgress()
        if (stage == 1) Deleter.trashNow(ctx(), pending)
        if (Build.VERSION.SDK_INT < 30 && ++rounds <= pending.size) {
            val c = ctx()
            val left = pending.filter { Deleter.exists(c, it) }
            if (left.isNotEmpty()) { attempt(left); return }
        }
        if (queue.isNotEmpty()) {
            val q = queue
            queue = emptyList()
            attempt(q)
            return
        }
        finish()
    }

    private fun finish() {
        verifyTries = 0
        verify()
    }

    /** MediaStore update hote deri hote pare, tai real state check kore tarpor result */
    private fun verify() {
        val c = try { ctx() } catch (e: Exception) { busy = false; progress?.dismiss(); progress = null; return }
        val left = pending.filter { Deleter.exists(c, it) }
        val maxTries = if (toBin && stage == 0 && Build.VERSION.SDK_INT >= 30) 3 else 12
        if (left.isNotEmpty() && !denied && verifyTries < maxTries) {
            verifyTries++
            updateProgress()
            hd.postDelayed({ verify() }, 400)
            return
        }
        conclude(c, left)
    }

    private fun conclude(c: Context, left: List<Uri>) {
        if (!busy) return
        if (toBin && stage == 0 && !denied && left.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            Log.w(tag, "trash request left ${left.size} items, retrying with write request")
            stage = 1
            queue = emptyList()
            verifyTries = 0
            attempt(left)
            return
        }
        val removed = pending.filter { it !in left }
        val verb = if (toBin) "moved" else "deleted"
        val msg = when {
            left.isEmpty() -> if (toBin) "Moved to Recycle Bin" else "Deleted"
            denied && removed.isEmpty() -> "Cancelled. Permission was not granted"
            denied -> "${removed.size} $verb, ${left.size} cancelled"
            removed.isEmpty() -> "Couldn't be $verb. Android rejected the request"
            else -> "${left.size} of ${pending.size} item(s) couldn't be $verb"
        }
        Log.i(tag, "finish: removed=${removed.size} left=${left.size} denied=$denied")
        left.forEach { Log.w(tag, "still active: $it") }
        val p = progress
        progress = null
        if (left.isEmpty() && p != null) p.success(msg)
        else {
            p?.dismiss()
            if (!quiet) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
        }
        busy = false
        waitingResult = false
        if (removed.isNotEmpty()) {
            val rs = removed.toSet()
            Library.snapshot = Library.snapshot.filter { it.uri !in rs }
        }
        onDone(removed, left.size)
    }
}