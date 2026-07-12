package com.wildodds.gymtracker.data.analytics

/**
 * The single funnel every analytics event flows through (Rule 4). One interface so the Supabase
 * implementation is swappable and so call sites are trivially testable. Fire-and-forget: [log]
 * never blocks the caller and never throws.
 */
interface AnalyticsClient {
  fun log(event: AnalyticsEvent)
}

/** No-op — used before consent is known and anywhere analytics must be provably inert. */
object NoOpAnalyticsClient : AnalyticsClient {
  override fun log(event: AnalyticsEvent) {}
}
