package com.wildodds.gymtracker.data.friends

import android.content.Context
import com.google.gson.Gson
import com.wildodds.gymtracker.data.backend.RemoteResult
import com.wildodds.gymtracker.data.backend.SupabaseModule
import com.wildodds.gymtracker.data.backend.runRemote
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.data.repository.GymRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Friends: list/redeem/unfriend, the event stream (motivation / flex / session-start / program
 * shares), and publishing the profile snapshot friends are allowed to see. Requires a signed-in
 * account; every call degrades to a RemoteResult.Failure without one.
 */
class FriendsRepository(
  private val appContext: Context,
  clientProvider: () -> SupabaseClient = { SupabaseModule.client }
) {
  private val client by lazy(clientProvider)
  private val db get() = AppDatabase.getInstance(appContext)
  private val gson = Gson()

  val myUserId: String? get() = runCatching { client.auth.currentUserOrNull()?.id }.getOrNull()

  // ── Reading ──────────────────────────────────────────────────────────────────

  suspend fun myProfile(): RemoteResult<FriendProfile?> = withContext(Dispatchers.IO) {
    runRemote {
      val id = myUserId ?: return@runRemote null
      client.postgrest.from("profiles").select { filter { eq("id", id) } }
        .decodeList<FriendProfile>().firstOrNull()
    }
  }

  suspend fun friends(): RemoteResult<List<FriendProfile>> = withContext(Dispatchers.IO) {
    runRemote {
      val id = myUserId ?: return@runRemote emptyList()
      val ids = client.postgrest.from("friends")
        .select(Columns.list("user_id,friend_id")) { filter { eq("user_id", id) } }
        .decodeList<FriendRow>().map { it.friendId }
      if (ids.isEmpty()) emptyList()
      else client.postgrest.from("profiles")
        .select { filter { isIn("id", ids) } }
        .decodeList<FriendProfile>()
    }
  }

  suspend fun friendProfile(friendId: String): RemoteResult<FriendProfile?> = withContext(Dispatchers.IO) {
    runRemote {
      client.postgrest.from("profiles").select { filter { eq("id", friendId) } }
        .decodeList<FriendProfile>().firstOrNull()
    }
  }

  @Serializable private data class FriendsOfParams(val other: String)

  suspend fun friendsOf(friendId: String): RemoteResult<List<FriendOfFriend>> = withContext(Dispatchers.IO) {
    runRemote {
      client.postgrest.rpc("friends_of", FriendsOfParams(friendId)).decodeList<FriendOfFriend>()
    }
  }

  // ── Friendship management ────────────────────────────────────────────────────

  @Serializable private data class RedeemParams(val code: String)

  suspend fun redeemCode(code: String): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote { client.postgrest.rpc("redeem_friend_code", RedeemParams(code.trim())); Unit }
  }

  @Serializable private data class UnfriendParams(val other: String)

  suspend fun unfriend(friendId: String): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote { client.postgrest.rpc("unfriend", UnfriendParams(friendId)); Unit }
  }

  // ── Events ───────────────────────────────────────────────────────────────────

  // Insert DTO with ONLY client-supplied columns (same pattern as AnalyticsUploader.Row):
  // id / created_at / notified / seen come from the server's defaults.
  @Serializable private data class FriendEventInsert(
    val sender: String, val recipient: String, val type: String, val body: String
  )

  suspend fun sendEvent(recipient: String, type: String, body: String = ""): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        val me = checkNotNull(myUserId) { "not signed in" }
        client.postgrest.from("friend_events")
          .insert(FriendEventInsert(sender = me, recipient = recipient, type = type, body = body))
        Unit
      }
    }

  /** Events for me that haven't produced a device notification yet (the poll worker's feed). */
  suspend fun unnotifiedEvents(): RemoteResult<List<FriendEvent>> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote emptyList()
      client.postgrest.from("friend_events")
        .select { filter { eq("recipient", me); eq("notified", false) } }
        .decodeList<FriendEvent>()
    }
  }

  /** Unaccepted shared programs — the Friends screen's inbox. */
  suspend fun unseenProgramShares(): RemoteResult<List<FriendEvent>> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote emptyList()
      client.postgrest.from("friend_events")
        .select { filter { eq("recipient", me); eq("seen", false); eq("type", FriendEvent.TYPE_PROGRAM) } }
        .decodeList<FriendEvent>()
    }
  }

  /** Unacknowledged motivation messages — surfaced on the session screen for the 💪 reply. */
  suspend fun unseenMotivations(): RemoteResult<List<FriendEvent>> = withContext(Dispatchers.IO) {
    runRemote {
      val me = myUserId ?: return@runRemote emptyList()
      client.postgrest.from("friend_events")
        .select { filter { eq("recipient", me); eq("seen", false); eq("type", FriendEvent.TYPE_MOTIVATION) } }
        .decodeList<FriendEvent>()
    }
  }

  suspend fun markNotified(ids: List<Long>): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      if (ids.isNotEmpty()) {
        client.postgrest.from("friend_events").update({ set("notified", true) }) {
          filter { isIn("id", ids) }
        }
      }
      Unit
    }
  }

  suspend fun markSeen(id: Long): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      client.postgrest.from("friend_events").update({ set("seen", true) }) { filter { eq("id", id) } }
      Unit
    }
  }

  /** 💪 acknowledge a motivation message: mark it seen and flex back at the sender. */
  suspend fun flexBack(event: FriendEvent): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    markSeen(event.id)
    sendEvent(event.sender, FriendEvent.TYPE_FLEX)
  }

  @Serializable private data class NotifyParams(@kotlinx.serialization.SerialName("session_name") val sessionName: String)

  /** Fan out "X just started a session" to friends who opted in. Fire-and-forget. */
  suspend fun notifySessionStart(sessionName: String): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote { client.postgrest.rpc("notify_session_start", NotifyParams(sessionName)); Unit }
  }

  suspend fun setNotifyOnFriendSession(enabled: Boolean): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      val me = checkNotNull(myUserId) { "not signed in" }
      client.postgrest.from("profiles").update({ set("notify_on_friend_session", enabled) }) {
        filter { eq("id", me) }
      }
      Unit
    }
  }

  // ── Profile snapshot (what friends see) ──────────────────────────────────────

  /**
   * Publish the friend-visible snapshot from local data: last session, active program name, the
   * four main-lift PRs, unlocked achievement ids, and user-created program metadata. Called after
   * completing a session and by the poll worker; cheap and idempotent.
   */
  suspend fun publishSnapshot(): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      val me = checkNotNull(myUserId) { "not signed in" }
      val dao = db.backupDao()
      val completions = dao.completions()
      val latest = completions.maxByOrNull { it.completedAt }
      val latestName = latest?.let { db.sessionDao().getSessionById(it.sessionId)?.name }
      val programs = db.programDao().getAllProgramsOnce()
      val active = programs.firstOrNull { it.isActive }
      val prefs = ThemePreferences(appContext)
      val prs = buildJsonObject {
        put(MainLift.SQUAT.key, prefs.oneRmSquat.first())
        put(MainLift.BENCH.key, prefs.oneRmBench.first())
        put(MainLift.DEADLIFT.key, prefs.oneRmDeadlift.first())
        put(MainLift.OHP.key, prefs.oneRmOhp.first())
      }
      val achievements = buildJsonArray {
        db.achievementDao().getUnlockedIds().forEach { add(JsonPrimitive(it)) }
      }
      val publicPrograms = buildJsonArray {
        programs.filter { it.isUserCreated && it.name != GymRepository.ON_DEMAND_PROGRAM_NAME }
          .forEach { p ->
            add(buildJsonObject {
              put("name", p.name); put("active", p.isActive); put("daysPerWeek", p.daysPerWeek)
            })
          }
      }
      client.postgrest.from("profiles").update({
        latest?.let { set("last_session_at", it.completedAt) }
        set("last_session_name", latestName ?: "")
        set("current_program", active?.name ?: "")
        set("prs", prs)
        set("achievements", achievements)
        set("public_programs", publicPrograms)
      }) { filter { eq("id", me) } }
      Unit
    }
  }

  // ── Program sharing ──────────────────────────────────────────────────────────

  /** Serialise a local program (all weeks) and send it to [recipient] as a 'program' event. */
  suspend fun shareProgram(recipient: String, programId: Long): RemoteResult<Unit> =
    withContext(Dispatchers.IO) {
      runRemote {
        val parsed = checkNotNull(buildParsedProgram(programId)) { "program not found" }
        sendEvent(recipient, FriendEvent.TYPE_PROGRAM, gson.toJson(parsed)).let {
          if (it is RemoteResult.Failure) throw IllegalStateException("send failed")
        }
        Unit
      }
    }

  /** Import a shared program from an event body (added to the library, not activated). */
  suspend fun importSharedProgram(event: FriendEvent): RemoteResult<Unit> = withContext(Dispatchers.IO) {
    runRemote {
      val parsed = gson.fromJson(event.body, ParsedProgram::class.java)
      GymRepository(db).importProgram(parsed.copy(isUserCreated = true), activate = false)
      markSeen(event.id)
      Unit
    }
  }

  internal suspend fun buildParsedProgram(programId: Long): ParsedProgram? {
    val program = db.programDao().getAllProgramsOnce().firstOrNull { it.id == programId } ?: return null
    val sessions = mutableListOf<ParsedSession>()
    for (week in 1..program.totalWeeks) {
      db.sessionDao().getSessionsForWeek(week, programId).forEach { s ->
        val exs = db.exerciseDao().getExercisesForSession(s.id).sortedBy { it.orderIndex }
        sessions += ParsedSession(
          weekNumber = week, dayNumber = s.dayNumber, name = s.name, muscleGroups = s.muscleGroups,
          exercises = exs.mapIndexed { i, e ->
            ParsedExercise(
              name = e.name, sets = e.sets, repsTarget = e.repsTarget, notes = e.notes,
              orderIndex = i, rpeTarget = e.rpeTarget, pct1rmTarget = e.pct1rmTarget
            )
          }
        )
      }
    }
    if (sessions.isEmpty()) return null
    return ParsedProgram(
      name = program.name, totalWeeks = program.totalWeeks, sessions = sessions,
      category = program.category, isUserCreated = true, trackRpe = program.trackRpe,
      trackOneRm = program.trackOneRm, description = program.description,
      daysPerWeek = program.daysPerWeek, split = program.split, style = program.style,
      repeatWeeks = 1
    )
  }
}
