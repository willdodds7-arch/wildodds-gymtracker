package com.wildodds.gymtracker.ui.account

import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.account.AccountExport
import com.wildodds.gymtracker.data.account.AccountOps
import com.wildodds.gymtracker.data.backend.RemoteError
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.sync.TrainingSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Re-auth is enforced": the delete sequence must re-authenticate BEFORE the irreversible Edge
 * Function call, and must not proceed if re-auth fails. Driven with a fake AccountOps that records
 * call order, so this is deterministic and offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountDeleteFlowTest {

  // Unconfined so viewModelScope coroutines run eagerly on the calling thread — the fake ops never
  // truly suspend, so the whole delete sequence completes before the assertions.
  @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun resetMain() { Dispatchers.resetMain() }

  private class FakeOps(
    val reauthResult: RemoteResult<Unit> = RemoteResult.Success(Unit),
    val deleteResult: RemoteResult<Unit> = RemoteResult.Success(Unit)
  ) : AccountOps {
    val calls = mutableListOf<String>()
    override suspend fun buildExport(exportedAt: Long): AccountExport =
      AccountExport(
        exportedAt = exportedAt,
        account = AccountExport.Account(null, null, null),
        training = TrainingSnapshot(),
        analyticsNote = AccountExport.AnalyticsNote("unset")
      )
    override suspend fun reauthenticate(password: String): RemoteResult<Unit> { calls += "reauth:$password"; return reauthResult }
    override suspend fun deleteAccount(): RemoteResult<Unit> { calls += "delete"; return deleteResult }
    override suspend fun wipeLocalData() { calls += "wipe" }
    override suspend fun signOut(): RemoteResult<Unit> { calls += "signout"; return RemoteResult.Success(Unit) }
  }

  private fun vmWith(ops: FakeOps) =
    AccountViewModel(ApplicationProvider.getApplicationContext(), ops)

  @Test
  fun deleteReauthsBeforeDeleting_thenSignsOut() = runTest {
    val ops = FakeOps()
    val vm = vmWith(ops)
    vm.deleteAccount(password = "pw", alsoWipeLocal = false)
    advanceUntilIdle()
    // reauth strictly precedes delete; no wipe (unchecked); signout after delete.
    assertEquals(listOf("reauth:pw", "delete", "signout"), ops.calls)
    assertEquals(AccountViewModel.DeleteState.Step.DONE, vm.delete.value.step)
  }

  @Test
  fun wipeLocalRequested_runsBetweenDeleteAndSignOut() = runTest {
    val ops = FakeOps()
    vmWith(ops).deleteAccount(password = "pw", alsoWipeLocal = true)
    advanceUntilIdle()
    assertEquals(listOf("reauth:pw", "delete", "wipe", "signout"), ops.calls)
  }

  @Test
  fun failedReauth_neverCallsDelete() = runTest {
    val ops = FakeOps(reauthResult = RemoteResult.Failure(RemoteError.Unauthorized("bad password")))
    val vm = vmWith(ops)
    vm.deleteAccount(password = "wrong", alsoWipeLocal = true)
    advanceUntilIdle()
    assertEquals("must stop at reauth", listOf("reauth:wrong"), ops.calls)
    assertFalse("delete must not have run", ops.calls.contains("delete"))
    assertNull("still on the screen (not DONE)", vm.delete.value.step.takeIf { it == AccountViewModel.DeleteState.Step.DONE })
    assertTrue(vm.delete.value.error!!.contains("verify it's you"))
  }

  @Test
  fun failedDelete_afterReauth_doesNotSignOutOrWipe() = runTest {
    val ops = FakeOps(deleteResult = RemoteResult.Failure(RemoteError.ServerError(500, "boom")))
    val vm = vmWith(ops)
    vm.deleteAccount(password = "pw", alsoWipeLocal = true)
    advanceUntilIdle()
    assertEquals(listOf("reauth:pw", "delete"), ops.calls) // no wipe, no signout
    assertTrue(vm.delete.value.error!!.contains("Deletion failed"))
  }
}
