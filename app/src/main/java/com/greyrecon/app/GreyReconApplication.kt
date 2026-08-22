package com.greyrecon.app

import android.app.Application
import android.content.Intent
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.mcp.McpService

class GreyReconApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SecureKeyStore(this).mcpEnabled) {
            startForegroundService(Intent(this, McpService::class.java))
        }
    }
}
