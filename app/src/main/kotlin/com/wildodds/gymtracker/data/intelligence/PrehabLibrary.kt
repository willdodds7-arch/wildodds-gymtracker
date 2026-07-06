package com.wildodds.gymtracker.data.intelligence

import android.content.Context
import com.google.gson.Gson

/** A curated prehab/rehab movement inserted as guidance (not logged) when accommodating an area. */
data class PrehabExercise(val name: String, val sets: Int, val reps: String, val note: String)

/** Pure parser for the prehab asset — keyed by [BodyRegion]. Unknown regions are ignored. */
object PrehabParser {
  private data class Raw(val prehab: List<RawRegion> = emptyList())
  private data class RawRegion(val region: String = "", val exercises: List<PrehabExercise> = emptyList())

  fun parse(json: String): Map<BodyRegion, List<PrehabExercise>> {
  val raw = runCatching { Gson().fromJson(json, Raw::class.java) }.getOrNull() ?: return emptyMap()
  val result = mutableMapOf<BodyRegion, List<PrehabExercise>>()
  for (r in raw.prehab) {
  val region = runCatching { BodyRegion.valueOf(r.region.trim().uppercase()) }.getOrNull() ?: continue
  result[region] = r.exercises.filter { it.name.isNotBlank() }
  }
  return result
  }
}

/** Android loader + cache for the prehab asset. */
object PrehabLibrary {
  private const val ASSET_NAME = "prehab_library.json"
  @Volatile private var cached: Map<BodyRegion, List<PrehabExercise>>? = null

  fun forRegion(context: Context, region: BodyRegion): List<PrehabExercise> = load(context)[region] ?: emptyList()

  private fun load(context: Context): Map<BodyRegion, List<PrehabExercise>> =
  cached ?: synchronized(this) {
  cached ?: run {
  val json = runCatching {
  context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
  }.getOrElse { "" }
  PrehabParser.parse(json).also { cached = it }
  }
  }
}
