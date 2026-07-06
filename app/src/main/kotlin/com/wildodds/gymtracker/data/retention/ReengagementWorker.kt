package com.wildodds.gymtracker.data.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.intelligence.ReadinessEngine
import com.wildodds.gymtracker.data.intelligence.ReadinessInput
import com.wildodds.gymtracker.data.intelligence.ReadinessLevel
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Periodic background check (Phase 7A). Computes dropout risk on-device, then gates the nudge through
 * [NudgePolicy] — which refuses if reminders are off, it's quiet hours, the frequency cap hasn't
 * elapsed, the user snoozed, the risk is low, OR Phase 4B readiness says the user should rest. Only
 * then (and only with notification permission) does it post one quiet notification.
 */
class ReengagementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val app = applicationContext
    val enabled = ThemePreferences(app).flag(SettingsRegistry.REENGAGEMENT, false).first()
    if (!enabled) return Result.success()   // opted out → never nudge

    val db = AppDatabase.getInstance(app)
    val repo = GymRepository(db)
    val features = repo.computeDropoutFeatures()
    val assessment = DropoutRiskEngine.assess(features)

    // Cross-feature 4B check: never nudge someone who's correctly resting.
    val recent = db.sessionCompletionDao().getRecentCompletions(1).firstOrNull()
    val readiness = ReadinessEngine.score(ReadinessInput(
      recentStrain = recent?.strainRating,
      recentFatigueScore = recent?.fatigueScore,
      daysSinceLastSession = features.daysSinceLastSession
    ))
    val restRecommended = readiness?.level == ReadinessLevel.REST

    val now = System.currentTimeMillis()
    val ctx = NudgeContext(
      enabled = true,
      risk = assessment.risk,
      restRecommended = restRecommended,
      lastNudgeAt = NudgeSettingsStore.lastNudgeAt(app),
      snoozedUntil = NudgeSettingsStore.snoozedUntil(app),
      minIntervalHours = NudgeSettingsStore.minIntervalHours(app),
      quietStartHour = NudgeSettingsStore.quietStart(app),
      quietEndHour = NudgeSettingsStore.quietEnd(app)
    )

    val decision = NudgePolicy.decide(ctx, now, ZoneId.systemDefault())
    if (decision.allowed && ReengagementNotifier.hasPermission(app)) {
      ReengagementNotifier.notify(app, "Wild Odds", DropoutRiskEngine.encouragement(assessment))
      NudgeSettingsStore.recordNudge(app, now)
    }
    return Result.success()
  }
}
