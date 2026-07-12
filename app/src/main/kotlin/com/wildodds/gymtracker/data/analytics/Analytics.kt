package com.wildodds.gymtracker.data.analytics

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.wildodds.gymtracker.BuildConfig
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.dao.AnalyticsOutboxDao
import com.wildodds.gymtracker.data.db.entity.AnalyticsOutboxEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-wide analytics entry point. [AnalyticsGate.log] is the ONLY thing call sites touch.
 *
 * Consent enforcement (Rule 4): when consent is not "granted", events are DROPPED — not queued.
 * They never touch the outbox, so nothing can leak on a later opt-in flip. Withdrawing consent
 * also clears whatever was already queued. There is no "pending" state that survives opt-out.
 */
object AnalyticsGate {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { encodeDefaults = true }

  // Anonymised per-process session id — a fresh random value each app run, never linked to the
  // account. (Deliberately not persisted: a new run is a new session.)
  private val sessionId: String = java.util.UUID.randomUUID().toString()

  @Volatile private var appContext: Context? = null

  fun init(context: Context) { appContext = context.applicationContext }

  /** Fire-and-forget; safe to call from anywhere, never throws, returns immediately. */
  fun log(event: AnalyticsEvent) {
    val ctx = appContext ?: return
    scope.launch {
      val queued = runCatching { persistIfConsented(ctx, event) }.getOrDefault(false)
      if (queued) runCatching { AnalyticsScheduler.uploadNow(ctx) } // nudge the uploader
    }
  }

  /**
   * The consent gate + outbox write, as an awaitable suspend function (no launched coroutine) so
   * the Rule-4 behaviour is deterministically testable. Returns true iff a row was queued.
   * DROPS (returns false without writing) whenever consent isn't "granted".
   */
  internal suspend fun persistIfConsented(
    ctx: Context,
    event: AnalyticsEvent,
    dao: AnalyticsOutboxDao = AppDatabase.getInstance(ctx).analyticsOutboxDao()
  ): Boolean {
    val consent = ThemePreferences(ctx).analyticsConsent.first()
    if (consent != "granted") return false // DROP — never queued

    val screen = event.properties["screen"]
    val props = event.properties.filterKeys { it != "screen" }
    dao.insert(
      AnalyticsOutboxEntry(
        eventName = event.name,
        screen = screen,
        propertiesJson = json.encodeToString(props),
        sessionId = sessionId,
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = Build.VERSION.SDK_INT.toString(),
        deviceClass = deviceClass(ctx),
        createdAt = System.currentTimeMillis()
      )
    )
    return true
  }

  /** Called when consent is withdrawn: purge anything still queued so nothing leaves post-opt-out. */
  fun onConsentRevoked(context: Context) {
    val ctx = context.applicationContext
    scope.launch { runCatching { AppDatabase.getInstance(ctx).analyticsOutboxDao().clear() } }
  }

  private fun deviceClass(ctx: Context): String {
    val smallestDp = ctx.resources.configuration.smallestScreenWidthDp
    return if (smallestDp >= 600) "tablet" else "phone"
  }
}
