package com.monkeycode.blelostfinder

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BleLostFinderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
