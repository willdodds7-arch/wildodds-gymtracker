package com.wildodds.gymtracker.data.gamification

/**
 * Pure, idempotent achievement evaluation. Given the current [MetricsSnapshot] and the set of ids
 * already unlocked, it returns ONLY the achievements newly crossing their threshold this pass.
 *
 * Idempotency is the key correctness property (especially across re-sync): an already-unlocked id is
 * never returned again, so it can never be double-awarded no matter how many times evaluation runs.
 */
object AchievementEngine {

  /** @return the achievements that just unlocked (target met AND not already in [alreadyUnlocked]). */
  fun evaluate(
    metrics: MetricsSnapshot,
    alreadyUnlocked: Set<String>,
    now: Long
  ): List<AchievementUnlock> =
    AchievementCatalog.ALL
      .filter { it.id !in alreadyUnlocked && it.isUnlocked(metrics) }
      .map { AchievementUnlock(it, now) }

  /** Progress toward every achievement (earned + locked) for the Profile UI, in catalog order. */
  fun progress(metrics: MetricsSnapshot, unlocked: Set<String>): List<AchievementProgress> =
    AchievementCatalog.ALL.map { def ->
      val value = def.valueOf(metrics)
      AchievementProgress(
        definition = def,
        current = value.coerceAtMost(def.target),
        target = def.target,
        unlocked = def.id in unlocked || value >= def.target
      )
    }

  /** The single most-advanced locked achievement — the "next up" nudge (or null if all earned). */
  fun nextUp(metrics: MetricsSnapshot, unlocked: Set<String>): AchievementProgress? =
    progress(metrics, unlocked).filter { !it.unlocked }.maxByOrNull { it.fraction }
}
