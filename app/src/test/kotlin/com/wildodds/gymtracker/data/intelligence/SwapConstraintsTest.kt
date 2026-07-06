package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure plumbing: swap-sheet UI selections → engine SubstitutionConstraints. */
class SwapConstraintsTest {

  private val fullGym = setOf(
  Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE, Equipment.BODYWEIGHT
  )

  @Test
  fun painTagsMapToJointStressAvoidTags() {
  val c = SwapConstraints.build(fullGym, painTags = setOf("Knee"), gymBusy = false)
  assertEquals(setOf("deep-knee-flexion", "loaded-knee-flexion"), c.avoidTags)
  assertEquals(fullGym, c.availableEquipment)
  }

  @Test
  fun multiplePainTagsUnionTheirStressTags() {
  val c = SwapConstraints.build(fullGym, painTags = setOf("Lower back", "Shoulder"), gymBusy = false)
  assertTrue("lumbar-loading" in c.avoidTags)
  assertTrue("spinal-compression" in c.avoidTags)
  assertTrue("overhead" in c.avoidTags)
  assertTrue("shoulder-load" in c.avoidTags)
  }

  @Test
  fun noPainTagsMeansNoAvoids() {
  val c = SwapConstraints.build(fullGym, painTags = emptySet(), gymBusy = false)
  assertTrue(c.avoidTags.isEmpty())
  }

  @Test
  fun gymBusyDropsMachinesToPreferFreeWeights() {
  val c = SwapConstraints.build(fullGym, painTags = emptySet(), gymBusy = true)
  assertFalse(Equipment.MACHINE in c.availableEquipment)
  assertTrue(Equipment.BARBELL in c.availableEquipment)
  assertTrue(Equipment.DUMBBELL in c.availableEquipment)
  }

  @Test
  fun maxDifficultyIsPassedThrough() {
  val c = SwapConstraints.build(fullGym, emptySet(), gymBusy = false, maxDifficulty = 4)
  assertEquals(4, c.maxDifficulty)
  }

  @Test
  fun painTagLabelsAreAllMapped() {
  // Every offered label resolves to at least one stress tag.
  SwapConstraints.PAIN_TAGS.forEach { tag ->
  assertTrue("tag $tag has no mapping", SwapConstraints.PAIN_TAG_MAP[tag]?.isNotEmpty() == true)
  }
  }
}
