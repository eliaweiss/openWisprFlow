package app.openwisprflow

import android.app.Application

/** Sets up logging before any activity or service runs, and records crashes. */
class OwfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        NetworkStatus.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.crash(TAG, thread, error)
            previous?.uncaughtException(thread, error)
        }
        AppLog.d(TAG, "process started")
    }

    private companion object {
        const val TAG = "openWisprFlow"
    }
}
