package com.klipper4a

import androidx.multidex.MultiDexApplication
import com.bugsnag.android.Bugsnag
import com.klipper4a.utils.TLSSocketFactory
import com.klipper4a.utils.preferences.MainPreferences
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import javax.net.ssl.HttpsURLConnection

class Klipper4aApplication : MultiDexApplication() {
    val preferences by lazy { MainPreferences(this) }
    var bugsnagStarted = false

    override fun onCreate() {
        super.onCreate()
        initializeSSLContext()

        // Start Koin
        startKoin {
            androidLogger()
            androidContext(this@Klipper4aApplication)
            modules(appModule)
        }

        if (preferences.enableBugReporting) {
            startBugsnag()
        }
    }

    fun startBugsnag() {
        if (bugsnagStarted) return

        Bugsnag.start(this)
        bugsnagStarted = true
    }

    fun initializeSSLContext() {
        val noSSLv3Factory: TLSSocketFactory = TLSSocketFactory()

        HttpsURLConnection.setDefaultSSLSocketFactory(noSSLv3Factory)
    }
}