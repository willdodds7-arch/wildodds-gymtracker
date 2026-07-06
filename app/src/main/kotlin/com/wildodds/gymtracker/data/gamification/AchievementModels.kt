package com.wildodds.gymtracker.data.gamification

/** Grouping for the Profile UI. */
enum class AchievementCategory { CONSISTENCY, VOLUME, MILESTONES, HABITS, EXPLORATION, SIDE_QUESTS }

/**
 * Aggregated, already-computed facts about the user's local training. The engine reads ONLY this —
 * keeping evaluation a pure function of a value object, so it's trivially testable and never touches
 * a database. Unknown/unavailable signals default to 0 / false so they simply never unlock.
 */
data class MetricsSnapshot(
  val currentStreakDays: Int = 0,
  val longestStreakDays: Int = 0,
  val sessionsCompleted: Int = 0,
  val totalVolumeKg: Long = 0,
  val habitBestStreakDays: Int = 0,
  val distinctExercises: Int = 0,
  val programsCompleted: Int = 0,
  val usedTravelMode: Boolean = false,
  // ── Side quests (each a one-off boolean, surfaced as a 0/1 metric) ──
  val didQuadraticFormula: Boolean = false,   // 10×10 squats ≥40% 1RM in one session
  val benched100: Boolean = false,            // bench press ≥100 kg for ≥1 rep
  val skippedCalvesForMonth: Boolean = false, // trained ≥30 days, no calf work in last 30
  val squattedEveryDayForWeek: Boolean = false, // a squat set every day for 7 straight days
  val trainedAtWombatHours: Boolean = false,  // a session logged 00:00–01:00
  val stayedBelowZone3: Boolean = false,      // wearable session peaking below zone 3
  val trainedArmsOnly: Boolean = false,       // a session hitting only arms
  val reached200Bpm: Boolean = false,         // 200+ BPM during a weights session
  val didBulgarianSplitSquat: Boolean = false,// logged a Bulgarian split squat set
  val einsteinProgress: Boolean = false       // progressed an exercise by <1 kg
)

/**
 * One achievement definition. [valueOf] extracts the relevant current number from a [MetricsSnapshot]
 * and [target] is the threshold to unlock — so progress and unlock are one consistent calculation.
 */
data class AchievementDefinition(
  val id: String,
  val title: String,
  val description: String,
  val category: AchievementCategory,
  val target: Int,
  val valueOf: (MetricsSnapshot) -> Int
) {
  fun isUnlocked(m: MetricsSnapshot): Boolean = valueOf(m) >= target
}

/** Progress toward (or completion of) one achievement, for the Profile UI. */
data class AchievementProgress(
  val definition: AchievementDefinition,
  val current: Int,
  val target: Int,
  val unlocked: Boolean
) {
  /** 0f..1f fraction toward the target (1f when unlocked). */
  val fraction: Float get() = if (target <= 0) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
}

/** A newly-earned achievement returned by an evaluation pass. */
data class AchievementUnlock(val definition: AchievementDefinition, val unlockedAt: Long)
