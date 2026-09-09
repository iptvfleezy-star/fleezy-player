package com.ultratv.tv.nativeapp

/**
 * Fleezy-specific runtime configuration.
 *
 * Keep provider-facing values centralized here so the customer UI never needs
 * to expose the Xtream host. We can later replace this constant with a
 * fleezy.stream configuration endpoint without changing the login screen.
 */
object FleezyConfig {
    const val XTREAM_BASE_URL = "http://pro.fleezy.stream"
    const val PROVIDER_NAME = "Fleezy"
}
