package com.wildodds.gymtracker.data.account

import com.google.gson.GsonBuilder
import com.wildodds.gymtracker.data.sync.TrainingSnapshot
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Top-level shape of the user's data export (GDPR/CCPA portability). */
data class AccountExport(
  val schema: String = "wildodds-account-export/v1",
  val exportedAt: Long,
  val account: Account,
  val training: TrainingSnapshot,
  /** Consent state + a pointer for the coarse, non-PII usage telemetry (which the client can't
   *  read back directly — RLS is insert-only). Full server-side telemetry is available via the
   *  request path on the public privacy page. */
  val analyticsNote: AnalyticsNote
) {
  data class Account(val userId: String?, val email: String?, val username: String?)
  data class AnalyticsNote(
    val analyticsConsent: String,
    val description: String =
      "Usage analytics are coarse, non-identifying events (screen/feature usage only) that this " +
        "app can send but cannot read back. Your full analytics history can be requested via the " +
        "privacy page; none of it contains your workout numbers, notes, name, email or location."
  )
}

/**
 * Serialises an [AccountExport] to a single-entry zip (`wildodds-export.json` inside). Pure — no
 * Android/DB/network — so the export content is unit-testable and the SAF layer just writes bytes.
 */
object AccountExporter {
  private val gson = GsonBuilder().setPrettyPrinting().create()

  const val JSON_ENTRY_NAME = "wildodds-export.json"

  fun toJson(export: AccountExport): String = gson.toJson(export)

  fun toZipBytes(export: AccountExport): ByteArray {
    val json = toJson(export).toByteArray(Charsets.UTF_8)
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
      zip.putNextEntry(ZipEntry(JSON_ENTRY_NAME))
      zip.write(json)
      zip.closeEntry()
    }
    return bos.toByteArray()
  }
}
