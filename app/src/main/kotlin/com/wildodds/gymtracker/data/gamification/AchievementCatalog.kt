package com.wildodds.gymtracker.data.gamification

/**
 * The fixed set of achievement definitions. Adding one is a single entry here — the engine, progress
 * calc and UI are all data-driven from this list. Ids are stable strings (persisted), so never rename
 * an existing id; add a new one instead.
 */
object AchievementCatalog {

  val ALL: List<AchievementDefinition> = listOf(
    // ── Consistency (workout-day streaks) ──
    def("streak_3", "Getting going", "Train 3 days in a row", AchievementCategory.CONSISTENCY, 3) { it.currentStreakDays.coerceAtLeast(it.longestStreakDays) },
    def("streak_7", "One week strong", "A 7-day training streak", AchievementCategory.CONSISTENCY, 7) { it.longestStreakDays },
    def("streak_30", "Unbreakable", "A 30-day training streak", AchievementCategory.CONSISTENCY, 30) { it.longestStreakDays },

    // ── Milestones (sessions completed) ──
    def("sessions_1", "First session", "Complete your first session", AchievementCategory.MILESTONES, 1) { it.sessionsCompleted },
    def("sessions_10", "Warmed up", "Complete 10 sessions", AchievementCategory.MILESTONES, 10) { it.sessionsCompleted },
    def("sessions_50", "Committed", "Complete 50 sessions", AchievementCategory.MILESTONES, 50) { it.sessionsCompleted },
    def("sessions_100", "Centurion", "Complete 100 sessions", AchievementCategory.MILESTONES, 100) { it.sessionsCompleted },
    def("programs_1", "Finisher", "Complete a whole program", AchievementCategory.MILESTONES, 1) { it.programsCompleted },
    def("programs_5", "Program collector", "Complete 5 programs", AchievementCategory.MILESTONES, 5) { it.programsCompleted },

    // ── Volume (total kg lifted) ──
    def("volume_10k", "Ten tonnes", "Lift 10,000 kg in total", AchievementCategory.VOLUME, 10_000) { it.totalVolumeKg.toIntClamped() },
    def("volume_50k", "Heavy hitter", "Lift 50,000 kg in total", AchievementCategory.VOLUME, 50_000) { it.totalVolumeKg.toIntClamped() },
    def("volume_100k", "Six figures", "Lift 100,000 kg in total", AchievementCategory.VOLUME, 100_000) { it.totalVolumeKg.toIntClamped() },

    // ── Habits ──
    def("habit_7", "Habit started", "A 7-day habit streak", AchievementCategory.HABITS, 7) { it.habitBestStreakDays },
    def("habit_30", "Habit locked in", "A 30-day habit streak", AchievementCategory.HABITS, 30) { it.habitBestStreakDays },

    // ── Exploration / variety ──
    def("variety_10", "Well rounded", "Train 10 different exercises", AchievementCategory.EXPLORATION, 10) { it.distinctExercises },
    def("variety_25", "Movement explorer", "Train 25 different exercises", AchievementCategory.EXPLORATION, 25) { it.distinctExercises },
    def("travel_1", "Have gym, will travel", "Log a travel-mode session", AchievementCategory.EXPLORATION, 1) { if (it.usedTravelMode) 1 else 0 },

    // ── Side quests (one-off, quirky) ──
    def("sq_quadratic", "Quadratic Formula", "10 sets of 10 squats at 40%+ of your 1RM in one session", AchievementCategory.SIDE_QUESTS, 1) { if (it.didQuadraticFormula) 1 else 0 },
    def("sq_4wd", "4 wheel drive", "Bench press 100 kg for at least 1 rep", AchievementCategory.SIDE_QUESTS, 1) { if (it.benched100) 1 else 0 },
    def("sq_really_bro", "Really bro?", "Don't train calves for an entire month", AchievementCategory.SIDE_QUESTS, 1) { if (it.skippedCalvesForMonth) 1 else 0 },
    def("sq_ivan_djuric", "Ivan Djuric", "Do at least 1 set of squats every day for a week", AchievementCategory.SIDE_QUESTS, 1) { if (it.squattedEveryDayForWeek) 1 else 0 },
    def("sq_wombat_hours", "Wombat hours", "Log a session between midnight and 1am", AchievementCategory.SIDE_QUESTS, 1) { if (it.trainedAtWombatHours) 1 else 0 },
    def("sq_mild_discomfort", "Train until mild discomfort", "Log a wearable session and never reach heart-rate zone 3, 4 or 5", AchievementCategory.SIDE_QUESTS, 1) { if (it.stayedBelowZone3) 1 else 0 },
    def("sq_arnold2", "Arnold 2.0", "Complete a session where you only hit arms", AchievementCategory.SIDE_QUESTS, 1) { if (it.trainedArmsOnly) 1 else 0 },
    def("sq_who_hurt_you", "Who hurt you?", "Reach 200+ BPM during a weights session", AchievementCategory.SIDE_QUESTS, 1) { if (it.reached200Bpm) 1 else 0 },
    def("sq_bulgaria_1", "България номер 1", "Complete a set of Bulgarian split squats", AchievementCategory.SIDE_QUESTS, 1) { if (it.didBulgarianSplitSquat) 1 else 0 },
    def("sq_einstein", "Einstein", "Progress on an exercise by less than 1 kg", AchievementCategory.SIDE_QUESTS, 1) { if (it.einsteinProgress) 1 else 0 }
  )

  private val byId = ALL.associateBy { it.id }
  fun byId(id: String): AchievementDefinition? = byId[id]

  private fun def(
    id: String, title: String, description: String, category: AchievementCategory,
    target: Int, valueOf: (MetricsSnapshot) -> Int
  ) = AchievementDefinition(id, title, description, category, target, valueOf)

  private fun Long.toIntClamped(): Int = if (this >= Int.MAX_VALUE) Int.MAX_VALUE else toInt()
}
