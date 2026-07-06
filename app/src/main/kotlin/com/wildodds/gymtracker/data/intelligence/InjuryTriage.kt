package com.wildodds.gymtracker.data.intelligence

/**
 * Conservative, fully-transparent injury triage. Pure Kotlin (no Android, no AI) so the safety
 * behavior is verifiable by unit tests.
 *
 * THIS IS GENERAL TRAINING GUIDANCE, NOT MEDICAL ADVICE OR DIAGNOSIS. The rules err toward
 * "see a professional": ANY serious sign routes to RED and NEVER auto-modifies the program.
 * No condition is ever named.
 */

enum class BodyRegion(val display: String) {
  SHOULDER("Shoulder"), ELBOW("Elbow"), WRIST("Wrist"), LOW_BACK("Lower back"),
  HIP("Hip"), KNEE("Knee"), ANKLE("Ankle"), NECK("Neck")
}

enum class PainQuality(val display: String) {
  ACHE("Dull ache"), SHARP("Sharp"), STIFF("Stiff"), PINCH("Pinching"), BURNING("Burning")
}

enum class PainTiming(val display: String) {
  DURING_LIFTS("During certain lifts"), AFTER_TRAINING("After training"), GENERAL("Throughout the day")
}

/** Serious signs. ANY of these → RED, no auto-changes. */
enum class RedFlag(val display: String) {
  SHARP_SEVERE_PAIN("Sharp or severe pain"),
  NUMBNESS_TINGLING("Numbness, tingling or weakness"),
  LOCKING_GIVING_WAY("Locking, catching or giving way"),
  PAIN_AT_REST_OR_NIGHT("Pain at rest or at night"),
  RECENT_TRAUMA("A recent fall, blow or twist"),
  SWELLING("Swelling, redness or heat"),
  CANT_BEAR_WEIGHT("Can't bear weight or use it normally"),
  RADIATING_PAIN("Pain that radiates down a limb")
}

enum class PainDuration(val display: String) {
  ACUTE("Less than a week"), SUBACUTE("1–6 weeks"), CHRONIC("More than 6 weeks")
}

data class TriageAnswers(
  val region: BodyRegion,
  val quality: PainQuality,
  val timing: Set<PainTiming> = emptySet(),
  val severity: Int = 0,            // 0..10
  val redFlags: Set<RedFlag> = emptySet(),
  val duration: PainDuration = PainDuration.ACUTE
)

enum class TriageLevel { RED, AMBER, GREEN }

data class TriageResult(
  val level: TriageLevel,
  val rationale: String,
  /** Joint-stress tags to avoid — ALWAYS empty for RED (no modifications). */
  val avoidTags: Set<String>
)

object InjuryTriage {

  /** Non-dismissable disclaimer shown at entry and on results. */
  const val DISCLAIMER =
  "This is general training guidance, not medical advice or a diagnosis. " +
  "If you're in significant pain, unsure, or symptoms persist or worsen, see a qualified " +
  "physiotherapist or doctor. Stop any exercise that causes sharp or worsening pain."

  /** Region → joint-stress tags to avoid when accommodating (mirrors the 3A graph tags). */
  val REGION_AVOID_TAGS: Map<BodyRegion, Set<String>> = mapOf(
  BodyRegion.KNEE to setOf(JointStressTags.DEEP_KNEE_FLEXION, JointStressTags.LOADED_KNEE_FLEXION),
  BodyRegion.LOW_BACK to setOf(JointStressTags.LUMBAR_LOADING, JointStressTags.SPINAL_COMPRESSION),
  BodyRegion.SHOULDER to setOf(JointStressTags.OVERHEAD, JointStressTags.SHOULDER_LOAD),
  BodyRegion.ELBOW to setOf(JointStressTags.ELBOW_LOAD),
  BodyRegion.WRIST to setOf(JointStressTags.WRIST_LOAD),
  BodyRegion.HIP to setOf(JointStressTags.HIP_FLEXION),
  BodyRegion.NECK to setOf(JointStressTags.OVERHEAD),
  BodyRegion.ANKLE to emptySet()
  )

  private const val RED_RATIONALE =
  "Your answers include signs that should be checked by a professional. Don't train through it — " +
  "see a physiotherapist or doctor before modifying your program."
  private const val AMBER_RATIONALE =
  "This can often be trained around, but be cautious: we'll swap aggravating lifts and add some " +
  "prehab. Monitor it — if it sharpens, spreads, or doesn't settle in a couple of weeks, see a professional."
  private const val GREEN_RATIONALE =
  "This looks like a minor niggle. We'll swap the lifts most likely to aggravate it and add light " +
  "prehab. Stop and reassess if anything gets sharper."

  /** True only when it is safe to auto-apply modifications (never on RED). */
  fun canApply(level: TriageLevel): Boolean = level == TriageLevel.AMBER || level == TriageLevel.GREEN

  fun triage(a: TriageAnswers): TriageResult {
  // RED — any serious sign, severe pain, or sharp-and-moderate pain. No modifications at all.
  val red = a.redFlags.isNotEmpty() ||
  a.severity >= 8 ||
  (a.quality == PainQuality.SHARP && a.severity >= 6)
  if (red) return TriageResult(TriageLevel.RED, RED_RATIONALE, emptySet())

  val amber = a.severity >= 4 ||
  a.quality in setOf(PainQuality.SHARP, PainQuality.BURNING, PainQuality.PINCH) ||
  a.duration == PainDuration.CHRONIC
  val level = if (amber) TriageLevel.AMBER else TriageLevel.GREEN
  return TriageResult(level, if (amber) AMBER_RATIONALE else GREEN_RATIONALE,
  REGION_AVOID_TAGS[a.region] ?: emptySet())
  }
}
