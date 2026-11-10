package com.foldshield.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences("foldshield_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return

        try {
            val svcIntent = Intent(context, ShieldVpnService::class.java).apply {
                this.action = "START_SHIELD"
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (_: Exception) {}
    }
}