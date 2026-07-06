package com.wildodds.gymtracker.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wildodds.gymtracker.data.db.entity.Program
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal on-device smoke test for the Room stack. Lives in androidTest and therefore needs
 * a connected device/emulator (run via `:app:connectedDebugAndroidTest`). The bulk of the
 * Room/repository coverage runs as fast local Robolectric tests under src/test.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseInstrumentedTest {

  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
  db = Room.inMemoryDatabaseBuilder(
  ApplicationProvider.getApplicationContext(),
  AppDatabase::class.java
  ).build()
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun insertsAndReadsBackAProgram() = runBlocking {
  val id = db.programDao().insert(Program(name = "Smoke", totalWeeks = 3, isActive = true))
  val current = db.programDao().getCurrentProgramOnce()
  assertEquals(id, current?.id)
  assertEquals("Smoke", current?.name)
  }
}
