package com.simple.proxyconnect

import android.app.Application

class SimpleProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SimpleProxyApp
            private set
    }
}
