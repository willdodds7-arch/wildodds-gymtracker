package com.wildodds.gymtracker.data.creator

import android.content.Context
import com.google.gson.Gson
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.backend.SupabaseModule
import com.wildodds.gymtracker.data.backend.runRemote
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.friends.FriendsRepository
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.repository.GymRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Verified Creator tier + marketplace, app side. READ-mostly by design: all money movement and
 * all marketplace writes happen on the server (Stripe Checkout on the website; publish-program /
 * stripe-webhook Edge Functions). The app reads entitlements and — for creators — manages
 * listings through the server-gated publish function. Google Play policy: nothing here may link
 * the user to the web checkout.
 */
class CreatorRepository(
  private val appContext: Context,
  clientProvider: () -> SupabaseClient = { SupabaseModule.client }
) {
  private val client by lazy(clientProvider)
  private val db get() = AppDatabase.getInstance(appContext)
  private val gson = Gson()

  val myUserId: String? get() = runCatching { client.auth.currentUserOrNull()?.id }.getOrNull()

  // ── Entitlement / status ─────────────────────────────────────────────────────

  suspend fun myStatus(): RemoteResult<CreatorStatus> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote CreatorStatus(signedIn = false)
      val sub = client.postgrest.from("creator_subscriptions")
        .select { filter { eq("user_id", me) } }
        .decodeList<CreatorSubscriptionRow>().firstOrNull()
      val profile = client.postgrest.from("profiles")
        .select { filter { eq("id", me) } }
        .decodeList<CreatorProfileRow>().firstOrNull()
      CreatorStatus(
        signedIn = true,
        subStatus = sub?.status,
        cancelAtPeriodEnd = sub?.cancelAtPeriodEnd ?: false,
        currentPeriodEnd = sub?.currentPeriodEnd,
        isVerified = profile?.isVerifiedCreator ?: CreatorGate.isVerified(sub?.status),
        connectComplete = profile?.connectOnboardingComplete ?: false,
        agreementAccepted = profile?.creatorAgreementAcceptedAt != null
      )
    }
  }

  // ── Creator dashboard ────────────────────────────────────────────────────────

  suspend fun myListings(): RemoteResult<List<MarketplaceListing>> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote emptyList()
      client.postgrest.from("marketplace_programs")
        .select { filter { eq("creator_id", me) } }
        .decodeList<MarketplaceListing>()
        .sortedByDescending { it.id }
    }
  }

  suspend fun myEarnings(): RemoteResult<Pair<EarningsSummary, List<PurchaseRow>>> =
    withContext(Dispatchers.IO) {
      runRemote {
        val me = myUserId ?: return@runRemote EarningsSummary.from(emptyList()) to emptyList()
        val rows = client.postgrest.from("purchases")
          .select { filter { eq("creator_id", me) } }
          .decodeList<PurchaseRow>()
          .sortedByDescending { it.id }
        EarningsSummary.from(rows) to rows
      }
    }

  /**
   * Create/update a draft listing from a local program (serialised in full, all weeks), then
   * publish it. Returns the server listing id. Gates enforced server-side; failures carry the
   * server's reason ("accept the Creator Agreement first", …).
   */
  suspend fun publishLocalProgram(
    localProgramId: Long,
    priceCents: Int,
    description: String,
    existingListingId: Long? = null
  ): RemoteResult<Long> = withContext(Dispatchers.IO) {
    runRemote {
      val parsed = checkNotNull(FriendsRepository(appContext).buildParsedProgram(localProgramId)) {
        "program not found"
      }
      val upserted = invokeFn("publish-program", buildJsonObject {
        put("action", "upsert")
        put("program", buildJsonObject {
          existingListingId?.let { put("id", it) }
          put("title", parsed.name)
          put("description", description)
          put("price_cents", priceCents)
          put("days_per_week", parsed.daysPerWeek)
          put("total_weeks", parsed.totalWeeks)
          put("program_json", gson.toJson(parsed.copy(isUserCreated = true)))
        })
      })
      val listingId = upserted["id"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: throw IllegalStateException("no listing id returned")
      invokeFn("publish-program", buildJsonObject {
        put("action", "publish")
        put("program", buildJsonObject { put("id", listingId) })
      })
      listingId
    }
  }

  /** Re-publish an existing (unpublished/draft) listing whose content is already uploaded. */
  suspend fun publishExisting(listingId: Long): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      invokeFn("publish-program", buildJsonObject {
        put("action", "publish")
        put("program", buildJsonObject { put("id", listingId) })
      })
      Unit
    }
  }

  suspend fun unpublish(listingId: Long): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      invokeFn("publish-program", buildJsonObject {
        put("action", "unpublish")
        put("program", buildJsonObject { put("id", listingId) })
      })
      Unit
    }
  }

  // ── Marketplace (buyer side) ─────────────────────────────────────────────────

  suspend fun browse(): RemoteResult<Pair<List<MarketplaceListing>, Map<String, CreatorPublicRow>>> =
    withContext(Dispatchers.IO) {
      runRemote {
        val listings = client.postgrest.from("marketplace_programs")
          .select { filter { eq("status", "published") } }
          .decodeList<MarketplaceListing>()
          .sortedByDescending { it.id }
        val creators = client.postgrest.from("creator_public").select()
          .decodeList<CreatorPublicRow>().associateBy { it.id }
        listings to creators
      }
    }

  suspend fun myPurchases(): RemoteResult<List<PurchaseRow>> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote emptyList()
      client.postgrest.from("purchases")
        .select { filter { eq("buyer_id", me); eq("status", "paid") } }
        .decodeList<PurchaseRow>()
    }
  }

  /** Import a purchased program into the local library (RLS only serves content to buyers). */
  suspend fun importPurchased(programId: Long): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      val content = client.postgrest.from("marketplace_program_content")
        .select { filter { eq("program_id", programId) } }
        .decodeList<MarketplaceContentRow>().firstOrNull()
        ?: throw IllegalStateException("purchase not found for this account")
      val parsed = gson.fromJson(content.programJson, ParsedProgram::class.java)
      GymRepository(db).importProgram(parsed.copy(isUserCreated = true), activate = false)
      Unit
    }
  }

  // ── Edge-function plumbing ───────────────────────────────────────────────────

  private val lenientJson = Json { ignoreUnknownKeys = true }

  private suspend fun invokeFn(name: String, body: JsonObject): JsonObject {
    val response: HttpResponse = client.functions.invoke(name, body)
    val text = response.bodyAsText()
    val parsed = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
    if (!response.status.isSuccess()) {
      val reason = parsed?.get("error")?.jsonPrimitive?.content ?: "request failed (${response.status.value})"
      throw IllegalStateException(reason)
    }
    return parsed ?: JsonObject(emptyMap())
  }
}
