package com.wildodds.gymtracker.data.intelligence

/**
 * Pure mapping from the swap-sheet UI selections to engine [SubstitutionConstraints]. Kept
 * separate and Android-free so the plumbing is unit-testable.
 */
object SwapConstraints {

  /** Quick pain/avoid tags → the joint-stress tags the engine should avoid. */
  val PAIN_TAG_MAP: Map<String, Set<String>> = mapOf(
  "Knee" to setOf(JointStressTags.DEEP_KNEE_FLEXION, JointStressTags.LOADED_KNEE_FLEXION),
  "Lower back" to setOf(JointStressTags.LUMBAR_LOADING, JointStressTags.SPINAL_COMPRESSION),
  "Shoulder" to setOf(JointStressTags.OVERHEAD, JointStressTags.SHOULDER_LOAD),
  "Elbow" to setOf(JointStressTags.ELBOW_LOAD),
  "Wrist" to setOf(JointStressTags.WRIST_LOAD),
  "Hip" to setOf(JointStressTags.HIP_FLEXION)
  )

  /** The pain-tag labels offered in the UI. */
  val PAIN_TAGS: List<String> = PAIN_TAG_MAP.keys.toList()

  /**
   * @param gymBusy when true, machines are dropped from the available set so the engine prefers
   *        free-weight / bodyweight alternatives (the typical "everything's taken" situation).
   */
  fun build(
  availableEquipment: Set<Equipment>,
  painTags: Set<String>,
  gymBusy: Boolean,
  maxDifficulty: Int? = null
  ): SubstitutionConstraints {
  val equipment = if (gymBusy) availableEquipment - Equipment.MACHINE else availableEquipment
  val avoid = painTags.flatMap { PAIN_TAG_MAP[it] ?: emptySet() }.toSet()
  return SubstitutionConstraints(
  availableEquipment = equipment,
  avoidTags = avoid,
  maxDifficulty = maxDifficulty
  )
  }
}
