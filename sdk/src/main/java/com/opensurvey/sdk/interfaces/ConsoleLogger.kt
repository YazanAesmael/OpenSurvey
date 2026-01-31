package com.opensurvey.sdk.interfaces

import kotlinx.coroutines.flow.StateFlow

interface ConsoleLogger {
    val logs: StateFlow<List<String>>
    fun log(tag: String, message: String)
    fun clearLogs()
}
