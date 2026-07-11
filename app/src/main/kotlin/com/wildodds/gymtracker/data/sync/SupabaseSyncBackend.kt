package com.wildodds.gymtracker.data.sync

import com.wildodds.gymtracker.data.backend.SupabaseModule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

/** The real transport: sync_push RPC (server-side conditional LWW upsert) + a seq-cursored
 *  select. RLS scopes both to the signed-in user automatically. */
class SupabaseSyncBackend(
  private val client: SupabaseClient = SupabaseModule.client
) : SyncBackend {

  @Serializable
  private data class PushParams(val rows: List<SyncRow>)

  override suspend fun push(rows: List<SyncRow>) {
    if (rows.isEmpty()) return
    client.postgrest.rpc("sync_push", PushParams(rows))
  }

  override suspend fun pull(afterSeq: Long, limit: Int): List<RemoteSyncRow> =
    client.postgrest.from("sync_rows")
      .select {
        filter { gt("seq", afterSeq) }
        order("seq", Order.ASCENDING)
        limit(limit.toLong())
      }
      .decodeList<RemoteSyncRow>()
}
