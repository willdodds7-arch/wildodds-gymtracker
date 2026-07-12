package com.wildodds.gymtracker.data.analytics

import com.wildodds.gymtracker.data.backend.SupabaseModule
import com.wildodds.gymtracker.data.db.entity.AnalyticsOutboxEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Uploads batches of queued analytics rows. Interface so the WorkManager job is testable with a fake. */
interface AnalyticsUploader {
  /** Inserts the batch; throws on failure so the caller can retry without dropping the rows. */
  suspend fun upload(entries: List<AnalyticsOutboxEntry>)
}

class SupabaseAnalyticsUploader(
  private val client: SupabaseClient = SupabaseModule.client
) : AnalyticsUploader {

  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Row(
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_name") val eventName: String,
    val screen: String?,
    val properties: JsonObject,
    @SerialName("app_version") val appVersion: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_class") val deviceClass: String
    // user_id + created_at are set server-side (auth.uid() default / now()).
  )

  override suspend fun upload(entries: List<AnalyticsOutboxEntry>) {
    if (entries.isEmpty()) return
    val rows = entries.map {
      Row(
        sessionId = it.sessionId,
        eventName = it.eventName,
        screen = it.screen,
        properties = json.decodeFromString(JsonObject.serializer(), it.propertiesJson),
        appVersion = it.appVersion,
        osVersion = it.osVersion,
        deviceClass = it.deviceClass
      )
    }
    client.postgrest.from("analytics_events").insert(rows)
  }
}
