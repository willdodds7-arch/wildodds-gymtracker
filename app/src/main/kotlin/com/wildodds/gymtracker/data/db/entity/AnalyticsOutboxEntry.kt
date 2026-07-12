package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A queued analytics event awaiting upload (fire-and-forget outbox). Written only when consent
 * is granted; drained by the analytics WorkManager job. Deliberately NOT a synced entity — it
 * never appears in SyncTriggers/SyncDao, so analytics rows never ride the training-data sync.
 *
 * `propertiesJson` is a small JSON object built solely from [AnalyticsEvent.properties], whose
 * values are all coarse code-defined vocab — no PII by construction.
 */
@Entity(tableName = "analytics_outbox")
data class AnalyticsOutboxEntry(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val eventName: String,
  val screen: String?,
  val propertiesJson: String,
  // Anonymised per-app-run id (random UUID minted at process start), NOT tied to the user id.
  val sessionId: String,
  val appVersion: String,
  val osVersion: String,
  val deviceClass: String,   // coarse: "phone" | "tablet"
  val createdAt: Long
)
