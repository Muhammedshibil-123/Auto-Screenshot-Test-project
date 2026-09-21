package com.simonbrs.autoscreenshot

import android.app.Application
import com.simonbrs.autoscreenshot.security.MediaVault

class ZoroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaVault.init(this)
    }
}
