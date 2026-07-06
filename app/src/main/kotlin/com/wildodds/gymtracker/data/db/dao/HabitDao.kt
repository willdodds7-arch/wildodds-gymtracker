package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.Habit
import com.wildodds.gymtracker.data.db.entity.HabitCompletion
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {

  @Query("SELECT * FROM habits ORDER BY createdAt ASC")
  fun allHabits(): Flow<List<Habit>>

  @Query("SELECT * FROM habits ORDER BY createdAt ASC")
  suspend fun allHabitsOnce(): List<Habit>

  @Query("SELECT * FROM habit_completions WHERE completedDate = :date")
  fun completionsForDate(date: String): Flow<List<HabitCompletion>>

  @Query("SELECT * FROM habit_completions WHERE completedDate = :date")
  suspend fun completionsForDateOnce(date: String): List<HabitCompletion>

  @Query("SELECT COUNT(*) > 0 FROM habit_completions WHERE habitId = :habitId AND completedDate = :date")
  suspend fun isCompleted(habitId: Long, date: String): Boolean

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHabit(habit: Habit): Long

  @Delete
  suspend fun deleteHabit(habit: Habit)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun markComplete(completion: HabitCompletion)

  @Query("DELETE FROM habit_completions WHERE habitId = :habitId AND completedDate = :date")
  suspend fun markIncomplete(habitId: Long, date: String)

  @Query("DELETE FROM habit_completions WHERE habitId = :habitId")
  suspend fun deleteAllCompletionsForHabit(habitId: Long)
}
