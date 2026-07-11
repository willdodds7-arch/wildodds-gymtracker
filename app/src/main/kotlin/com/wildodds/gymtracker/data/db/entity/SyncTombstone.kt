package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity

/**
 * Records a locally-deleted syncable row so the deletion propagates to the server (soft delete)
 * and on to other devices. Written automatically by an AFTER DELETE trigger on every syncable
 * table (see SyncTriggers) — no repository code has to remember to do it.
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["entityType", "syncId"])
data class SyncTombstone(
  val entityType: String, // the table name, e.g. "programs"
  val syncId: String,
  val deletedAt: Long
)
