package com.opensurvey.sdk.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

import com.opensurvey.sdk.interfaces.ConsoleLogger

/**
 * An in-memory logger for debugging purposes.
 * Provided as a singleton by Hilt to be accessible throughout the app.
 */
@Singleton
class DebugLogger @Inject constructor() : ConsoleLogger {

    companion object {
        const val TAG = "DEBUG_LOGGER"
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs = _logs.asStateFlow()

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Adds a new log entry. This is the primary method to be called from other classes.
     *
     * @param tag A short tag to identify the source of the log (e.g., "BleManager", "CreateBaseVM").
     * @param message The detailed log message.
     */
    override fun log(tag: String, message: String) {
        val timestamp = timestampFormat.format(Date())
        val newLogEntry = "$timestamp [$tag]: $message"

        android.util.Log.d(TAG, newLogEntry) // Ensure we log to logcat too

        // Update the state flow with the new log entry at the top of the list.
        _logs.update { currentLogs ->
            listOf(newLogEntry) + currentLogs
        }
    }

    /**
     * Returns all collected logs as a single, formatted string, ready for sharing or copying.
     */
    fun getLogsAsString(): String {
        // We reverse the list to get chronological order (oldest first).
        return logs.value.reversed().joinToString("\n")
    }

    /**
     * Clears all log entries from memory.
     */
    override fun clearLogs() {
        _logs.value = emptyList()
    }
}