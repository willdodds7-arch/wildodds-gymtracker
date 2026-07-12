package com.wildodds.gymtracker.data.analytics

import com.wildodds.gymtracker.data.db.dao.AnalyticsOutboxDao

/**
 * Drains the outbox in batches: peek → upload → delete-on-success. Extracted from the WorkManager
 * job so the retry/consent behaviour is unit-testable with a fake DAO + fake uploader.
 */
class AnalyticsOutbox(
  private val dao: AnalyticsOutboxDao,
  private val uploader: AnalyticsUploader,
  private val batchSize: Int = 200
) {

  sealed class Result {
    data object Success : Result()   // outbox fully drained (or already empty)
    data object Retry : Result()     // upload failed; rows kept for a later attempt
  }

  suspend fun drain(): Result {
    while (true) {
      val batch = dao.peek(batchSize)
      if (batch.isEmpty()) return Result.Success
      try {
        uploader.upload(batch)
      } catch (e: Exception) {
        // Keep the rows; WorkManager backoff will retry. Fire-and-forget means a failed upload
        // is never surfaced to the user.
        return Result.Retry
      }
      dao.deleteByIds(batch.map { it.id })
      if (batch.size < batchSize) return Result.Success
    }
  }
}
