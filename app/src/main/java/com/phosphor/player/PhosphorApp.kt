package com.phosphor.player

import android.app.Application
import android.content.Context

class PhosphorApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Earliest possible hook — catches even startup crashes.
        // (Java crashes are written to Downloads immediately; native-crash
        // and exit-reason reports from a previous run are collected when the
        // UI starts — see MainActivity.)
        CrashReporter.install(this)
    }
}
