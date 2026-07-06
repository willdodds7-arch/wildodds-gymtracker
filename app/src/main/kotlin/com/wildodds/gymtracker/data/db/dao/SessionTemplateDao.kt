package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.SessionTemplate
import com.wildodds.gymtracker.data.db.entity.SessionTemplateExercise
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionTemplateDao {
  @Query("SELECT * FROM session_templates ORDER BY name COLLATE NOCASE ASC")
  fun getAllTemplates(): Flow<List<SessionTemplate>>

  @Query("SELECT * FROM session_templates ORDER BY name COLLATE NOCASE ASC")
  suspend fun getAllTemplatesOnce(): List<SessionTemplate>

  @Query("SELECT * FROM session_templates WHERE id = :id")
  suspend fun getTemplateById(id: Long): SessionTemplate?

  @Query("SELECT * FROM session_template_exercises WHERE templateId = :templateId ORDER BY orderIndex")
  fun getExercisesForTemplate(templateId: Long): Flow<List<SessionTemplateExercise>>

  @Query("SELECT * FROM session_template_exercises WHERE templateId = :templateId ORDER BY orderIndex")
  suspend fun getExercisesForTemplateOnce(templateId: Long): List<SessionTemplateExercise>

  @Insert
  suspend fun insertTemplate(template: SessionTemplate): Long

  @Insert
  suspend fun insertExercises(exercises: List<SessionTemplateExercise>)

  @Update
  suspend fun updateTemplate(template: SessionTemplate)

  @Query("DELETE FROM session_template_exercises WHERE templateId = :templateId")
  suspend fun deleteExercisesForTemplate(templateId: Long)

  @Query("DELETE FROM session_templates WHERE id = :id")
  suspend fun deleteTemplate(id: Long)
}
