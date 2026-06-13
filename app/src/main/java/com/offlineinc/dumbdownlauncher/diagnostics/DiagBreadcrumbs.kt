package com.offlineinc.dumbdownlauncher.diagnostics

/**
 * Process-wide, fire-and-forget sink that lets non-diagnostics code (e.g. the
 * quack location stack) drop a structured breadcrumb into events.jsonl without
 * depending on the verbose rolling-logcat toggle being on.
 *
 * [DiagnosticsService] registers its event writer here while it is running and
 * the user has opted in to battery analysis. When diagnostics is off — or the
 * service simply isn't up (production builds, opt-in disabled) — [record] is a
 * cheap no-op because no sink is registered.
 *
 * Safe to call from any thread: the registered sink ultimately writes through
 * [JsonlWriter], whose append path is synchronized.
 *
 * This is the only public surface of the diagnostics package; everything else
 * is internal. Callers pass a flat payload of JSON-encodable primitives
 * (String / Int / Long / Boolean / null), e.g.:
 *
 *     DiagBreadcrumbs.record(
 *         "quack_location_result",
 *         mapOf(
 *             "outcome" to "delivered",
 *             "source" to "gps",
 *             "fell_back_to_gps" to true,
 *             "elapsed_ms" to 4231L,
 *         ),
 *     )
 */
object DiagBreadcrumbs {

    /** Receives a breadcrumb (type + payload) and persists it. */
    fun interface Sink {
        fun record(type: String, payload: Map<String, Any?>)
    }

    @Volatile
    private var sink: Sink? = null

    /** Called by [DiagnosticsService] when collection starts. */
    internal fun register(s: Sink) {
        sink = s
    }

    /**
     * Called by [DiagnosticsService] on teardown. Only clears the sink if it
     * still points at [s], so a races with a freshly-started service can't
     * accidentally unregister the new sink.
     */
    internal fun unregister(s: Sink) {
        if (sink === s) sink = null
    }

    /**
     * Record a breadcrumb. No-op (and never throws) when diagnostics isn't
     * collecting, so callers can sprinkle these freely on hot paths.
     */
    fun record(type: String, payload: Map<String, Any?>) {
        val s = sink ?: return
        try {
            s.record(type, payload)
        } catch (_: Throwable) {
            // Diagnostics must never affect app behaviour.
        }
    }
}
