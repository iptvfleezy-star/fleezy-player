package com.ultratv.tv.nativeapp

/**
 * Fleezy Player privacy stub.
 *
 * Upstream Ultra TV shipped remote crash/event telemetry to a Cloudflare Worker.
 * Fleezy disables all remote telemetry at the source. Calls are intentionally
 * kept as no-ops for now so the rest of the upstream code remains compile-safe
 * while we refactor the app.
 */
object RemoteLog {
    @Volatile var telemetryEnabled: Boolean = false

    fun init(
        ctx: android.content.Context,
        mac: String,
        versionName: String,
        versionCode: Int,
    ) = Unit

    fun event(tag: String, message: String, level: String = "info") = Unit
    fun info(tag: String, message: String) = Unit
    fun warn(tag: String, message: String) = Unit
    fun error(tag: String, message: String) = Unit
    fun debug(tag: String, message: String) = Unit
    fun crashSync(thread: Thread, error: Throwable) = Unit
}
