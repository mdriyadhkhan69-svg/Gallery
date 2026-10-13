package com.example.gallery

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
    private val chunk = 100
    private var pending: List<Uri> = emptyList()
    private var queue: List<Uri> = emptyList()
    var quiet = false
    private var toBin = true
    private var rounds = 0
    private var denied = false
    private var stage = 0

    private val launcher = caller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Log.i(tag, "authorization result ok=${r.resultCode == Activity.RESULT_OK} stage=$stage")
        after(r.resultCode == Activity.RESULT_OK)
    }

    fun confirmAndDelete(uris: List<Uri>, onConfirm: () -> Unit = {}) {
        if (uris.isEmpty()) return
        val c = ctx()
        val n = uris.size
        if (Prefs.useBin && Deleter.binAvailable()) {
            val msg = if (n == 1) "Move this item to the Recycle Bin?" else "Move $n items to the Recycle Bin?"
            showDeleteSheet(c, msg, "Move to Recycle Bin", Colors.BLUE, Colors.BLUE) { onConfirm(); begin(uris, true) }
        } else {
            val msg = if (n == 1) "Delete this item? This cannot be undone." else "Delete $n items? This cannot be undone."
            showDeleteSheet(c, msg, "Delete", 0xFFFF4D4D.toInt(), Color.WHITE) { onConfirm(); begin(uris, false) }
        }
    }

    fun begin(uris: List<Uri>, bin: Boolean) {
        pending = uris
        queue = emptyList()
        toBin = bin
        rounds = 0
        denied = false
        stage = 0
        attempt(uris)
    }

    private fun attempt(list: List<Uri>) {
        var rest = list
        while (rest.isNotEmpty()) {
            val part = rest.take(chunk)
            rest = rest.drop(chunk)
            val sender = try {
                if (stage == 0) Deleter.perform(ctx(), part, toBin) else Deleter.performWrite(ctx(), part)
            } catch (e: Exception) {
                Log.e(tag, "perform failed stage=$stage", e); null
            }
            if (sender != null) {
                queue = rest
                try {
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                    return
                } catch (e: Exception) {
                    Log.e(tag, "launch failed", e)
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
        val c = ctx()
        val left = pending.filter { Deleter.exists(c, it) }
        if (toBin && stage == 0 && !denied && left.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            Log.w(tag, "trash request left ${left.size} items, retrying with write request")
            stage = 1
            queue = emptyList()
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
        Log.i(tag, "finish: removed=${removed.size} left=${left.size} denied=$denied stage=$stage")
        if (!quiet) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
        onDone(removed, left.size)
    }
}