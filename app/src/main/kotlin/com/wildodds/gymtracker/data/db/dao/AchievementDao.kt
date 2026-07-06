package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wildodds.gymtracker.data.db.entity.Achievement
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

  @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
  fun observeAll(): Flow<List<Achievement>>

  @Query("SELECT * FROM achievements")
  suspend fun getAll(): List<Achievement>

  @Query("SELECT id FROM achievements")
  suspend fun getUnlockedIds(): List<String>

  /** IGNORE so an already-earned achievement keeps its original unlockedAt (idempotent award). */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(achievement: Achievement)

  @Query("DELETE FROM achievements")
  suspend fun deleteAll()
}
