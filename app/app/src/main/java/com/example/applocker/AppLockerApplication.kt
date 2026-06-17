package com.example.applocker

import android.app.Application
import android.util.Log

class AppLockerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("AppLockerApplication", "Application onCreate")
    }
}
