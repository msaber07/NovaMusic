package com.example.novaplayer

import android.app.Application
import android.content.Context

class NovaPlayerApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }
}
