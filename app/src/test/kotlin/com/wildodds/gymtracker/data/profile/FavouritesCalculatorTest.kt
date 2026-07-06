package com.wildodds.gymtracker.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure unit tests for the Profile "favourites" derivations (no Android). */
class FavouritesCalculatorTest {

  @Test
  fun favouriteMuscle_weightsPrimaryFullAndSecondaryHalf() {
    // Bench Press: primary CHEST, secondary FRONT_DELT + TRICEPS.
    // 10 sets of bench → Chest 10, Front Delts 5, Triceps 5. Chest wins.
    val rows = listOf(ExerciseSetCount("Bench Press", 10))
    assertEquals("Chest", FavouritesCalculator.favouriteMuscle(rows))
  }

  @Test
  fun favouriteMuscle_accumulatesAcrossExercises() {
    // Lots of curls (biceps primary) should beat a handful of bench-driven chest sets.
    val rows = listOf(
      ExerciseSetCount("Barbell Curl", 12),   // Biceps 12 (+ Forearms 6)
      ExerciseSetCount("Hammer Curl", 8),     // Biceps 8 + Forearms 8
      ExerciseSetCount("Bench Press", 6)      // Chest 6
    )
    assertEquals("Biceps", FavouritesCalculator.favouriteMuscle(rows))
  }

  @Test
  fun favouriteMuscle_nullWhenNothingMaps() {
    assertNull(FavouritesCalculator.favouriteMuscle(emptyList()))
    assertNull(FavouritesCalculator.favouriteMuscle(listOf(ExerciseSetCount("Underwater Basket Weaving", 5))))
  }

  @Test
  fun favouriteMuscle_ignoresZeroOrNegativeCounts() {
    val rows = listOf(ExerciseSetCount("Bench Press", 0), ExerciseSetCount("Squat", -3))
    assertNull(FavouritesCalculator.favouriteMuscle(rows))
  }

  @Test
  fun favouriteSplit_picksHighestCount() {
    val rows = listOf(
      SplitCount("3x Full Body", 5),
      SplitCount("6x Bro Split", 11),
      SplitCount("4x Upper Lower", 9)
    )
    assertEquals("6x Bro Split", FavouritesCalculator.favouriteSplit(rows))
  }

  @Test
  fun favouriteSplit_nullWhenEmptyOrBlank() {
    assertNull(FavouritesCalculator.favouriteSplit(emptyList()))
    assertNull(FavouritesCalculator.favouriteSplit(listOf(SplitCount("", 7), SplitCount("   ", 3))))
  }
}
