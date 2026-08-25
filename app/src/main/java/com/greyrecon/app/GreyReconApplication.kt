package com.greyrecon.app

import android.app.Application
import android.content.Intent
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.mcp.McpService

class GreyReconApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SecureKeyStore(this).mcpEnabled) {
            try {
                startForegroundService(Intent(this, McpService::class.java))
            } catch (e: IllegalStateException) {
                // Android disallows starting a foreground service from a process the system itself
                // creates in the background (app restarted after being killed, device boot, etc.) --
                // confirmed live via Crashlytics: ForegroundServiceStartNotAllowedException (API 31+,
                // extends IllegalStateException, so this catch covers it without referencing an
                // API-31-only class on a minSdk-26 build). Not fatal to swallow: SettingsScreen's own
                // MCP toggle calls startForegroundService from a real foreground UI action, which
                // always succeeds -- the user can just re-toggle it if this attempt was skipped.
            }
        }
    }
}
