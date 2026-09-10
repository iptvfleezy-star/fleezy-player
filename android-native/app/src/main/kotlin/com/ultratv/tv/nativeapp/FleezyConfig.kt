package com.ultratv.tv.nativeapp

/**
 * Fleezy-specific runtime configuration.
 *
 * Keep provider-facing values centralized here so the customer UI never needs
 * to expose the Xtream host. We can later replace this value with a
 * fleezy.stream configuration endpoint without changing the login screen.
 */
object FleezyConfig {
    val XTREAM_BASE_URL: String = if (BuildConfig.DEBUG) {
        "http://192.168.2.211:8001"
    } else {
        "http://pro.fleezy.stream"
    }

    const val PROVIDER_NAME = "Fleezy"
}
