package com.pasiflonet.mobile

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // האתחול עבר ל-MainActivity.checkApiAndInit() 
        // כדי לאפשר הזנת API ID ו-Hash ידנית
    }
}
