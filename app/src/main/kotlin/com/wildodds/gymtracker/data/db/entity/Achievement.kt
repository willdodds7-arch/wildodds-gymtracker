package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single unlocked achievement (Phase 6A). The primary key is the catalog definition id, so the
 * award is naturally idempotent at the storage layer too — re-inserting the same id never creates a
 * second row. [unlockedAt] is when it was first earned.
 */
@Entity(tableName = "achievements")
data class Achievement(
  @PrimaryKey val id: String,
  val unlockedAt: Long
)
