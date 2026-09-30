package se.sensnology.spotnav.app

import android.util.Log

/** Which of logcat's levels a message belongs at. */
internal enum class LogLevel { INFO, WARN, ERROR }

/** Where the catalogue and price logic writes what it decided. */
internal fun interface RelayLog {
    fun log(level: LogLevel, message: String)

    companion object {
        /** For tests, and for callers with nothing to say about a decision. */
        val NONE = RelayLog { _, _ -> }
    }
}

/** The production sink: logcat, under this package's tag. */
internal object AndroidRelayLog : RelayLog {
    private const val TAG = "SpotNavRelay"

    override fun log(level: LogLevel, message: String) {
        when (level) {
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
    }
}
