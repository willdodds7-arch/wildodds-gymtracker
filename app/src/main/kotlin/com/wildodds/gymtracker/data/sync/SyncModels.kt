package com.wildodds.gymtracker.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One row as pushed to the server (matches the jsonb shape sync_push() expects). */
@Serializable
data class SyncRow(
  @SerialName("entity_type") val entityType: String,
  @SerialName("sync_id") val syncId: String,
  @SerialName("updated_at") val updatedAt: Long,
  @SerialName("deleted_at") val deletedAt: Long? = null,
  val payload: JsonObject
)

/** One row as pulled back, with the server's monotonic cursor attached. */
@Serializable
data class RemoteSyncRow(
  @SerialName("entity_type") val entityType: String,
  @SerialName("sync_id") val syncId: String,
  @SerialName("updated_at") val updatedAt: Long,
  @SerialName("deleted_at") val deletedAt: Long? = null,
  val payload: JsonObject,
  val seq: Long
)

/**
 * The transport, as an interface so the engine's convergence/offline/LWW behaviour is fully
 * testable against an in-memory fake — the real one talks to Supabase (sync_push RPC + select).
 */
interface SyncBackend {
  suspend fun push(rows: List<SyncRow>)
  suspend fun pull(afterSeq: Long, limit: Int): List<RemoteSyncRow>
}

/** Per-device sync cursors. SharedPreferences in the app; in-memory in tests. */
interface SyncCursorStore {
  /** Rows with local updatedAt/deletedAt beyond this have not been pushed yet. */
  var lastPushedAt: Long
  /** Server seq up to which remote rows have been applied locally. */
  var lastPullSeq: Long
  var lastSyncAt: Long
}

class PrefsSyncCursorStore(context: Context) : SyncCursorStore {
  private val prefs = context.applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
  override var lastPushedAt: Long
    get() = prefs.getLong("last_pushed_at", 0L)
    set(v) { prefs.edit().putLong("last_pushed_at", v).apply() }
  override var lastPullSeq: Long
    get() = prefs.getLong("last_pull_seq", 0L)
    set(v) { prefs.edit().putLong("last_pull_seq", v).apply() }
  override var lastSyncAt: Long
    get() = prefs.getLong("last_sync_at", 0L)
    set(v) { prefs.edit().putLong("last_sync_at", v).apply() }
}

enum class SyncPhase { IDLE, RUNNING, OK, FAILED }

data class SyncState(
  val phase: SyncPhase = SyncPhase.IDLE,
  val lastSyncAt: Long = 0L,
  val message: String = ""
)

/** Process-wide observable sync status — Settings' "Last synced …" row and the first-login
 *  backup screen both watch this. The engine is the only writer. */
object SyncStatus {
  private val _state = MutableStateFlow(SyncState())
  val state: StateFlow<SyncState> = _state.asStateFlow()
  internal fun update(transform: (SyncState) -> SyncState) { _state.value = transform(_state.value) }
}
