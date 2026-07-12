package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wildodds.gymtracker.data.db.entity.AnalyticsOutboxEntry

@Dao
interface AnalyticsOutboxDao {

  @Insert suspend fun insert(entry: AnalyticsOutboxEntry): Long

  @Query("SELECT * FROM analytics_outbox ORDER BY id ASC LIMIT :limit")
  suspend fun peek(limit: Int): List<AnalyticsOutboxEntry>

  @Query("DELETE FROM analytics_outbox WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("SELECT COUNT(*) FROM analytics_outbox")
  suspend fun count(): Int

  /** Consent withdrawal drops everything still queued — nothing leaves the device after opt-out. */
  @Query("DELETE FROM analytics_outbox")
  suspend fun clear()

  /** Backstop so a device that's offline for months can't accumulate an unbounded outbox. */
  @Query("DELETE FROM analytics_outbox WHERE createdAt < :cutoff")
  suspend fun deleteOlderThan(cutoff: Long)
}
