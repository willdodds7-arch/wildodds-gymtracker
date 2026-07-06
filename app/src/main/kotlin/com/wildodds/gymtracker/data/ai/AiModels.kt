package com.wildodds.gymtracker.data.ai

data class AiExercise(
    val name: String,
    val sets: Int,
    val repsTarget: String,
    val notes: String = ""
)

data class AiDay(
    val dayNumber: Int,
    val name: String,
    val muscleGroups: String,
    val exercises: List<AiExercise>
)

data class AiParsedProgram(
    val name: String,
    val totalWeeks: Int,
    val days: List<AiDay>
)
