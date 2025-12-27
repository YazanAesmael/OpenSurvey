package com.opensurvey.sdk.di

import javax.inject.Qualifier

/**
 * A Hilt qualifier to distinguish the application-level CoroutineScope
 * from other potential scopes (e.g., a user-session-specific scope).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope