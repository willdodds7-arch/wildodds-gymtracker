package com.wildodds.gymtracker.data.profile

/**
 * A light-hearted "real-life equivalent" for a lifted weight, shown on the post-session summary.
 * Pure / no Android deps so it is trivially unit-testable.
 *
 * [describe] maps a weight in kg to a recognisable object of roughly that mass. The catalogue spans
 * 30 kg to 300 kg; weights below 30 or above 300 clamp to the nearest end so there is always a phrase.
 */
object WeightEquivalents {

  /** Ascending (kg, object phrase). The phrase slots into "the weight of <phrase>". */
  internal val CATALOGUE: List<Pair<Int, String>> = listOf(
    30  to "a large bag of dog food",
    40  to "a full beer keg",
    50  to "an adult kangaroo",
    60  to "an average adult woman",
    70  to "an average adult man",
    80  to "a newborn giraffe",
    90  to "a baby elephant",
    100 to "a kitchen fridge",
    110 to "a full-grown male gorilla",
    120 to "a giant panda",
    130 to "a fully stocked vending machine",
    140 to "a baby grand piano",
    150 to "a road motorcycle",
    160 to "a fully loaded washing machine and a fridge",
    170 to "an adult male lion",
    180 to "a dairy cow's worth of milk",
    190 to "a vending machine plus its owner",
    200 to "a grand piano",
    215 to "a concert harp and its harpist",
    230 to "an adult male polar bear",
    245 to "a Vespa scooter with two riders",
    260 to "a fully grown male tiger and its lunch",
    275 to "a grand piano plus the pianist",
    300 to "a smart car minus the wheels"
  )

  /** The closest catalogue object at or below [weightKg] (clamps to the lightest/heaviest entry). */
  fun describe(weightKg: Float): String {
    val w = weightKg.toInt()
    if (w <= CATALOGUE.first().first) return CATALOGUE.first().second
    if (w >= CATALOGUE.last().first) return CATALOGUE.last().second
    return CATALOGUE.last { it.first <= w }.second
  }

  /** Past-tense verb for the heaviest lift, derived from the exercise name (falls back to "lifted"). */
  fun verbFor(exerciseName: String): String {
    val n = exerciseName.lowercase()
    return when {
      "deadlift" in n          -> "deadlifted"
      "squat" in n             -> "squatted"
      "bench" in n             -> "benched"
      "press" in n             -> "pressed"
      "row" in n               -> "rowed"
      "curl" in n              -> "curled"
      "pull" in n              -> "pulled"
      "lunge" in n             -> "lunged"
      "hinge" in n || "rdl" in n -> "hinged"
      else                     -> "lifted"
    }
  }

  /** Full summary line, e.g. "You squatted the weight of a kitchen fridge for 10 reps". */
  fun summaryLine(exerciseName: String, weightKg: Float, reps: Int): String {
    val verb = verbFor(exerciseName)
    val obj = describe(weightKg)
    val repText = if (reps == 1) "1 rep" else "$reps reps"
    return "You $verb the weight of $obj for $repText."
  }
}
