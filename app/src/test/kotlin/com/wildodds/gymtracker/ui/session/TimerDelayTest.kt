@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.wildodds.gymtracker.ui.session

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 10-second delayed start: counts -10, -9 … -1 on a 1 s tick, then the timer proper begins.
 * Driven on a StandardTestDispatcher so the countdown runs on virtual time.
 */
@RunWith(RobolectricTestRunner::class)
class TimerDelayTest {

  private val dispatcher = StandardTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(): SessionViewModel =
    SessionViewModel(ApplicationProvider.getApplicationContext<Application>(), SavedStateHandle())

  private fun tick(ms: Long) { dispatcher.scheduler.advanceTimeBy(ms); dispatcher.scheduler.runCurrent() }

  @Test
  fun delayedStart_countsDown_thenStartsTheTimer() {
    val vm = vm()
    vm.setTimerMode(SessionViewModel.TimerMode.STOPWATCH)
    vm.startTimerWithDelay(10)
    assertEquals(10, vm.timerState.value.delayRemaining)
    assertFalse(vm.timerState.value.isRunning)

    tick(3000) // -10 → -7
    assertEquals(7, vm.timerState.value.delayRemaining)
    assertFalse("still in the lead-in", vm.timerState.value.isRunning)

    tick(7000) // -7 → 0 ⇒ timer starts
    assertEquals(0, vm.timerState.value.delayRemaining)
    assertTrue("timer must start when the lead-in hits zero", vm.timerState.value.isRunning)

    tick(5000) // stopwatch now counts up
    assertEquals(5, vm.timerState.value.currentSeconds)
  }

  @Test
  fun pausingDuringTheLeadIn_cancelsIt() {
    val vm = vm()
    vm.startTimerWithDelay(10)
    tick(2000)
    assertEquals(8, vm.timerState.value.delayRemaining)
    vm.pauseTimer()
    assertEquals(0, vm.timerState.value.delayRemaining)
    assertFalse(vm.timerState.value.isRunning)
    tick(10_000) // nothing revives it
    assertFalse(vm.timerState.value.isRunning)
  }

  @Test
  fun delayedStart_ignoredWhileAlreadyRunningOrCounting() {
    val vm = vm()
    vm.startTimerWithDelay(10)
    tick(1000)
    val before = vm.timerState.value.delayRemaining
    vm.startTimerWithDelay(10) // no-op: already counting
    assertEquals(before, vm.timerState.value.delayRemaining)
  }

  @Test
  fun fullscreenTogglesIndependentlyOfTheClock() {
    val vm = vm()
    assertFalse(vm.timerState.value.isFullscreen)
    vm.toggleTimerFullscreen()
    assertTrue(vm.timerState.value.isFullscreen)
    vm.toggleTimerFullscreen()
    assertFalse(vm.timerState.value.isFullscreen)
  }
}
