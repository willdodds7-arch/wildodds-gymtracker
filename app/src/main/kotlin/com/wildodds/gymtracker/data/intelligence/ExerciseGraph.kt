package com.wildodds.gymtracker.data.intelligence

import android.content.Context
import android.util.Log
import com.wildodds.gymtracker.data.ExerciseLibrary

/**
 * Android loader + cache for the exercise graph. Reads the `exercise_graph.json` asset once,
 * resolves muscles via [ExerciseLibrary], and hands back a cached pure [ExerciseGraphEngine].
 * This is the only Android-aware part; all the intelligence lives in the pure engine.
 */
object ExerciseGraph {

  private const val ASSET_NAME = "exercise_graph.json"

  @Volatile private var cached: ExerciseGraphEngine? = null

  fun engine(context: Context): ExerciseGraphEngine =
  cached ?: synchronized(this) {
  cached ?: build(context.applicationContext).also { cached = it }
  }

  private fun build(context: Context): ExerciseGraphEngine {
  val json = runCatching {
  context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
  }.getOrElse { "" }
  val nodes = if (json.isBlank()) emptyList()
  else ExerciseGraphParser.parse(json) { ExerciseLibrary.lookup(it) }
  return ExerciseGraphEngine(nodes, logMiss = { name ->
  Log.d("ExerciseGraph", "no graph node for \"$name\"")
  })
  }
}
