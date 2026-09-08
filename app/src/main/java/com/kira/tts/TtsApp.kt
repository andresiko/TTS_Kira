package com.kira.tts

import android.app.Application

/** Installs the process-wide crash logger as early as possible. */
class TtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
