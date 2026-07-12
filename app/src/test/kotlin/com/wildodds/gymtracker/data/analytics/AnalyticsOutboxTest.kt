package com.wildodds.gymtracker.data.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.AnalyticsOutboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AnalyticsOutboxTest {

  private lateinit var db: AppDatabase

  /** Fails the first [failTimes] uploads, then succeeds — models a flaky network. */
  private class FlakyUploader(var failTimes: Int) : AnalyticsUploader {
    val uploaded = mutableListOf<AnalyticsOutboxEntry>()
    var attempts = 0
    override suspend fun upload(entries: List<AnalyticsOutboxEntry>) {
      attempts++
      if (failTimes > 0) { failTimes--; throw IOException("flaky") }
      uploaded += entries
    }
  }

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
    ).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() { db.close() }

  private fun seed(n: Int) = runBlocking {
    repeat(n) { i ->
      db.analyticsOutboxDao().insert(
        AnalyticsOutboxEntry(
          eventName = "screen_view", screen = "home", propertiesJson = "{}",
          sessionId = "s", appVersion = "2.0.0", osVersion = "34", deviceClass = "phone",
          createdAt = i.toLong()
        )
      )
    }
  }

  @Test
  fun uploadFailure_keepsRows_forRetry() = runBlocking {
    seed(3)
    val uploader = FlakyUploader(failTimes = 99)
    val result = AnalyticsOutbox(db.analyticsOutboxDao(), uploader).drain()
    assertEquals(AnalyticsOutbox.Result.Retry, result)
    assertEquals("rows must survive a failed upload", 3, db.analyticsOutboxDao().count())
    assertTrue(uploader.uploaded.isEmpty())
  }

  @Test
  fun retryAfterTransientFailure_eventuallyDrains() = runBlocking {
    seed(3)
    val uploader = FlakyUploader(failTimes = 2)
    val dao = db.analyticsOutboxDao()

    // First two drains fail (rows kept), third succeeds — WorkManager would space these out.
    assertEquals(AnalyticsOutbox.Result.Retry, AnalyticsOutbox(dao, uploader).drain())
    assertEquals(AnalyticsOutbox.Result.Retry, AnalyticsOutbox(dao, uploader).drain())
    assertEquals(AnalyticsOutbox.Result.Success, AnalyticsOutbox(dao, uploader).drain())

    assertEquals("outbox drained after success", 0, dao.count())
    assertEquals(3, uploader.uploaded.size)
  }

  @Test
  fun successfulDrain_deletesUploadedRowsOnly_inBatches() = runBlocking {
    seed(5)
    val uploader = FlakyUploader(failTimes = 0)
    val result = AnalyticsOutbox(db.analyticsOutboxDao(), uploader, batchSize = 2).drain()
    assertEquals(AnalyticsOutbox.Result.Success, result)
    assertEquals(0, db.analyticsOutboxDao().count())
    assertEquals(5, uploader.uploaded.size)
  }
}
