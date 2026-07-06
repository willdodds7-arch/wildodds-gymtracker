package com.wildodds.gymtracker.data.intelligence

/** The kind of adjustment the adaptive engine proposes for the next session. */
enum class PlanAdjustmentType {
  PROCEED,          // train as planned
  SCALE_VOLUME,     // same load, trim a back-off set or two
  LIGHTER_SESSION,  // drop load and volume for today (recovery low)
  INSERT_DELOAD,    // a planned deload to break a stall
  EASE_IN           // gentle re-entry after a lay-off
}

/**
 * The three inputs the engine fuses. Each is optional so the engine works with whatever's known:
 * progress (a program-wide stall from the 3B rules), recovery (today's [ReadinessLevel]), and
 * schedule (days since the last logged session).
 */
data class AdaptiveInput(
  val readiness: ReadinessLevel? = null,
  val programStalled: Boolean = false,
  val daysSinceLastSession: Int? = null
)

/**
 * A concrete, explained, OVERRIDABLE adjustment. [loadScale]/[volumeScale] (1.0 = unchanged) are the
 * actual reversible levers applied to the next session's prefill; [headline]/[detail] explain why.
 */
data class PlanAdjustment(
  val type: PlanAdjustmentType,
  val headline: String,
  val detail: String,
  val loadScale: Float = 1f,
  val volumeScale: Float = 1f
) {
  /** Whether there is anything to accept (PROCEED has no overlay to apply). */
  val isActionable: Boolean get() = type != PlanAdjustmentType.PROCEED
}

/**
 * Deterministic adaptive-plan rules. Given progress + recovery + schedule, it returns exactly one
 * adjustment, chosen by a fixed priority so the same inputs always yield the same plan:
 *
 *   under-recovered (REST) → stall → mildly-down (CAUTION) → long lay-off → proceed.
 *
 * All decisions live here (pure, testable). The optional LLM layer only rephrases [detail]; it never
 * changes the type or the scales — so behaviour stays verifiable and works fully offline.
 */
object AdaptivePlanEngine {

  /** A lay-off of this many days triggers a gentle ease-in. */
  const val LONG_LAYOFF_DAYS = 5

  fun adjust(input: AdaptiveInput): PlanAdjustment {
    // 1. Recovery is low → lighten today regardless of progress. Suggested, never forced.
    if (input.readiness == ReadinessLevel.REST) {
      return PlanAdjustment(
        PlanAdjustmentType.LIGHTER_SESSION,
        headline = "Scale today back",
        detail = "Your recovery looks low today. Dropping load ~15% and trimming a set keeps the " +
          "habit without digging a deeper hole — back to normal once you've recovered.",
        loadScale = 0.85f, volumeScale = 0.8f
      )
    }

    // 2. Progress has stalled → a planned deload usually unsticks it (3B rule, surfaced proactively).
    if (input.programStalled) {
      return PlanAdjustment(
        PlanAdjustmentType.INSERT_DELOAD,
        headline = "Insert a deload",
        detail = "Several lifts have stalled. A lighter week — about 10% off, slightly less volume — " +
          "lets you rebuild and push past the plateau.",
        loadScale = 0.9f, volumeScale = 0.8f
      )
    }

    // 3. Recovery a little down → keep the load, trim volume.
    if (input.readiness == ReadinessLevel.CAUTION) {
      return PlanAdjustment(
        PlanAdjustmentType.SCALE_VOLUME,
        headline = "Trim the volume",
        detail = "Recovery is a touch down. Keep your working weights but cut a back-off set or two — " +
          "you'll still get the session in.",
        loadScale = 1f, volumeScale = 0.85f
      )
    }

    // 4. Long lay-off → ease back in to avoid an avoidable sore week.
    if ((input.daysSinceLastSession ?: 0) >= LONG_LAYOFF_DAYS) {
      return PlanAdjustment(
        PlanAdjustmentType.EASE_IN,
        headline = "Ease back in",
        detail = "It's been a while since your last session. Starting ~5% lighter with slightly less " +
          "volume makes the return smoother.",
        loadScale = 0.95f, volumeScale = 0.9f
      )
    }

    // 5. Everything looks good → train as planned.
    return PlanAdjustment(
      PlanAdjustmentType.PROCEED,
      headline = "Train as planned",
      detail = "Recovery and progress both look good — go for your planned session."
    )
  }
}
