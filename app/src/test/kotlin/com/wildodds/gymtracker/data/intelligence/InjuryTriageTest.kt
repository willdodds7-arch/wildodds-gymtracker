package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safety-critical: red flags ALWAYS route to a professional and never produce modifications. */
class InjuryTriageTest {

  private fun answers(
  region: BodyRegion = BodyRegion.KNEE,
  quality: PainQuality = PainQuality.ACHE,
  severity: Int = 2,
  redFlags: Set<RedFlag> = emptySet(),
  duration: PainDuration = PainDuration.ACUTE
  ) = TriageAnswers(region, quality, severity = severity, redFlags = redFlags, duration = duration)

  @Test
  fun anyRedFlagAlwaysRoutesToProfessionalWithNoModifications() {
  RedFlag.entries.forEach { flag ->
  val r = InjuryTriage.triage(answers(redFlags = setOf(flag)))
  assertEquals("flag $flag must be RED", TriageLevel.RED, r.level)
  assertTrue("RED must produce NO avoid tags / modifications", r.avoidTags.isEmpty())
  assertFalse("RED is never applicable", InjuryTriage.canApply(r.level))
  assertTrue(r.rationale.isNotBlank())
  }
  }

  @Test
  fun severePainIsRedEvenWithoutAFlag() {
  assertEquals(TriageLevel.RED, InjuryTriage.triage(answers(severity = 8)).level)
  assertEquals(TriageLevel.RED, InjuryTriage.triage(answers(severity = 9, quality = PainQuality.ACHE)).level)
  }

  @Test
  fun sharpAndModeratePainIsRed() {
  assertEquals(TriageLevel.RED, InjuryTriage.triage(answers(quality = PainQuality.SHARP, severity = 6)).level)
  }

  @Test
  fun moderateNiggleIsAmberWithRegionAvoidTags() {
  val r = InjuryTriage.triage(answers(region = BodyRegion.KNEE, quality = PainQuality.PINCH, severity = 5))
  assertEquals(TriageLevel.AMBER, r.level)
  assertTrue(InjuryTriage.canApply(r.level))
  assertEquals(setOf("deep-knee-flexion", "loaded-knee-flexion"), r.avoidTags)
  }

  @Test
  fun chronicMildIsAmber() {
  assertEquals(TriageLevel.AMBER,
  InjuryTriage.triage(answers(quality = PainQuality.ACHE, severity = 2, duration = PainDuration.CHRONIC)).level)
  }

  @Test
  fun minorMildAcheIsGreenWithModifications() {
  val r = InjuryTriage.triage(answers(region = BodyRegion.SHOULDER, quality = PainQuality.ACHE, severity = 2))
  assertEquals(TriageLevel.GREEN, r.level)
  assertTrue(InjuryTriage.canApply(r.level))
  assertEquals(setOf("overhead", "shoulder-load"), r.avoidTags)
  }

  @Test
  fun disclaimerIsPresentAndNonMedical() {
  assertTrue(InjuryTriage.DISCLAIMER.isNotBlank())
  assertTrue(InjuryTriage.DISCLAIMER.contains("not medical advice", ignoreCase = true))
  assertTrue(InjuryTriage.DISCLAIMER.contains("physiotherapist", ignoreCase = true) ||
  InjuryTriage.DISCLAIMER.contains("doctor", ignoreCase = true))
  }

  @Test
  fun everyRegionHasAnAvoidMapping() {
  BodyRegion.entries.forEach { region ->
  assertTrue("region $region missing from avoid map",
  InjuryTriage.REGION_AVOID_TAGS.containsKey(region))
  }
  }
}
