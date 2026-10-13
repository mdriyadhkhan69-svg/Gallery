package com.example.gallery

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
    private var pending: List<Uri> = emptyList()
    var quiet = false
    private var toBin = true
    private var rounds = 0

    private val launcher = caller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
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
        toBin = bin
        rounds = 0
        attempt(uris)
    }

    private fun attempt(list: List<Uri>) {
        val sender = try { Deleter.perform(ctx(), list, toBin) } catch (e: Exception) { null }
        if (sender != null) {
            try {
                launcher.launch(IntentSenderRequest.Builder(sender).build())
                return
            } catch (e: Exception) { }
        }
        finish()
    }

    private fun after(ok: Boolean) {
        if (ok && Build.VERSION.SDK_INT < 30 && ++rounds <= pending.size) {
            val c = ctx()
            val left = pending.filter { Deleter.exists(c, it) }
            if (left.isNotEmpty()) { attempt(left); return }
        }
        finish()
    }

    private fun finish() {
        val c = ctx()
        val left = pending.filter { Deleter.exists(c, it) }
        val removed = pending.filter { it !in left }
        val verb = if (toBin) "moved" else "deleted"
        val msg = when {
            left.isEmpty() -> if (toBin) "Moved to Recycle Bin" else "Deleted"
            removed.isEmpty() -> "Couldn't be $verb"
            else -> "${left.size} of ${pending.size} item(s) couldn't be $verb"
        }
        if (!quiet) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
        onDone(removed, left.size)
    }
}