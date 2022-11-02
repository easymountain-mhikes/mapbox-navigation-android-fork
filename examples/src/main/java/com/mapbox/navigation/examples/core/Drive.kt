package com.mapbox.navigation.examples.core

data class Drive(
    val sessionId: String,
    val startedAt: String,
    val userId: String,
    val endedAt: String,
    val historyStoragePath: String,
    val driveMode: String,
    val appVersion: String,
    val appMode: String,
    val navSdkVersion: String,
    val navNativeSdkVersion: String,
    val appSessionId: String?,
)
