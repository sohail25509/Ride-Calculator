
package com.ridecalculator.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MonitoringService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
