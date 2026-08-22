package com.wildodds.gymtracker.data.db

import androidx.room.TypeConverter
import com.wildodds.gymtracker.data.db.entity.StoredSetPrescription
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room type converters for the reader's structured columns (schema v26).
 *
 * Both directions are total: a malformed or hand-edited value reads back as empty rather than
 * throwing, because a corrupt notes-adjacent column must never make a user's program unopenable.
 *
 * [json] is configured for forward compatibility ([Json.ignoreUnknownKeys]) so a future field added
 * to [StoredSetPrescription] does not break a database written by an older build, and
 * `encodeDefaults = false` keeps the stored text small and — crucially — **stable**: the same draft
 * always serialises to the same bytes, which is what makes the import idempotency hash meaningful.
 */
object ReaderConverters {

  val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  @TypeConverter
  @JvmStatic
  fun setListToJson(value: List<StoredSetPrescription>?): String =
    if (value.isNullOrEmpty()) "" else json.encodeToString(value)

  @TypeConverter
  @JvmStatic
  fun jsonToSetList(value: String?): List<StoredSetPrescription> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<StoredSetPrescription>>(value) }.getOrDefault(emptyList())
  }

  /**
   * Modifiers are a small, closed vocabulary of snake_case tokens, so a comma-joined string is
   * both smaller and easier to read in a `sqlite3` dump than JSON — and it can never need escaping.
   */
  @TypeConverter
  @JvmStatic
  fun modifiersToString(value: List<String>?): String =
    value.orEmpty().filter { it.isNotBlank() }.joinToString(",")

  @TypeConverter
  @JvmStatic
  fun stringToModifiers(value: String?): List<String> =
    value.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
}
