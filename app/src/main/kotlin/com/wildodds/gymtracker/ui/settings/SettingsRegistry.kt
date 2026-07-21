package com.wildodds.gymtracker.ui.settings

/**
 * The widget a settings entry renders as. Most features are plain on/off [TOGGLE]s; the
 * handful of richer controls (theme colour, timer sound, API key, data actions) are modelled
 * explicitly so they can still be registered, grouped, and — crucially — searched.
 */
enum class SettingControl {
  TOGGLE, ACCENT, TIMER_SOUND, API_KEY, CLEAR_DATA, DOWNLOAD_TEMPLATE, WEARABLE_CONNECT,
  EXPORT_DATA, REMINDER_SETTINGS, HABITS, ACCOUNT, SIGN_OUT, SHARE_USAGE_STATS,
  SYNC_NOW, EXPORT_ACCOUNT, DELETE_ACCOUNT, LEGAL_DOC
}

/**
 * One searchable, (usually) toggleable Settings item.
 *
 * @param key      For [SettingControl.TOGGLE], the ThemePreferences flag key (e.g.
 *                 "feat_session_timer"). For controls, a stable synthetic id.
 * @param default  The toggle's default value (ignored for non-toggle controls).
 */
data class SettingsEntry(
  val key: String,
  val title: String,
  val summary: String,
  val group: String,
  val keywords: List<String>,
  val default: Boolean = false,
  val control: SettingControl = SettingControl.TOGGLE
)

/**
 * Single source of truth for everything that appears in Settings. Future features register a
 * row here once ([register]) and get search + grouping + a toggle row for free — no Settings
 * screen edits required.
 */
object SettingsRegistry {

  /** Preferred display order; groups not listed here are appended in first-seen order. */
  val groupOrder: List<String> =
  listOf("Account", "Appearance", "Session", "Wearables", "Tools", "Display", "AI Import", "Data", "Legal & privacy")

  // Phase 2 (online-first) — account controls.
  const val ACCOUNT = "action_account"
  const val SIGN_OUT = "action_sign_out"
  const val SHARE_USAGE_STATS = "action_share_usage_stats"

  // Phase 3 (online-first) — sync controls.
  const val SYNC_NOW = "action_sync_now"
  const val SYNC_WIFI_ONLY = "feat_sync_wifi_only"

  // Phase 5 (online-first) — account lifecycle.
  const val EXPORT_ACCOUNT = "action_export_account"
  const val DELETE_ACCOUNT = "action_delete_account"

  /** Flag key for the session exit-confirmation guard (a safety feature, default ON). */
  const val SESSION_EXIT_GUARD = "feat_session_exit_guard"

  // Phase 2A feature keys.
  const val SESSION_SUMMARY = "feat_session_summary"
  const val SUMMARY_HEART_RATE = "feat_summary_heart_rate"
  const val PLATE_CALCULATOR = "feat_plate_calculator"

  // Phase 4 — wearable connection. A synthetic action id (not a toggle); the real on/off is the
  // Health Connect permission grant + [SUMMARY_HEART_RATE].
  const val CONNECT_WEARABLE = "action_connect_wearable"

  // Phase 4B — recovery-driven features. Both default OFF (advanced): no unsolicited prompts.
  const val REST_DAY_RECS = "feat_rest_day_recs"
  const val ADAPTIVE_PLAN = "feat_adaptive_plan"

  // Local data export + health write-back settings keys.
  const val WRITE_TO_HEALTH_CONNECT = "feat_write_to_health_connect"
  const val EXPORT_DATA = "action_export_data"

  // Phase 6A — gamification. Achievements default ON (purely local, calm).
  const val ACHIEVEMENTS = "feat_achievements"

  // Habit tracker entry point (the Habits screen, reached from Settings).
  const val HABITS = "action_habit_tracker"

  // Phase 7A — retention. Notifications are opt-in (default OFF). Local activity insights default ON.
  const val REENGAGEMENT = "feat_reengagement"
  const val SHOW_INSIGHTS = "feat_activity_insights"
  const val REMINDER_SETTINGS = "action_reminder_settings"

  // Phase 3B feature keys.
  const val SMART_PROGRESSION = "feat_smart_progression"
  const val GOAL_RECALIBRATION = "feat_goal_recalibration"

  // Phase 3C feature keys.
  const val EXERCISE_SWAPS = "feat_exercise_swaps"
  const val EASIER_HARDER = "feat_easier_harder"

  // Phase 3D feature key.
  const val TRAVEL_MODE = "feat_travel_mode"

  // Phase 3E feature key.
  const val INJURY_TRIAGE = "feat_injury_triage"

  // Phase 3F feature key.
  const val REALTIME_FATIGUE = "feat_realtime_fatigue"

  // Phase 3G feature key.
  const val ADVANCED_ANALYTICS = "feat_advanced_analytics"

  // Phase 3H feature key.
  const val ON_DEMAND_LIBRARY = "feat_ondemand_library"

  // Friends (social layer). ON shows the Friends area + enables session-start fan-out to friends
  // who opted in; OFF hides every entry point (nothing is ever sent).
  const val FRIENDS = "feat_friends"

  // Creator marketplace (Verified Creator tier). OFF hides the Creator hub + Marketplace entry
  // points and all badges. Purchasing happens on the website only (Google Play policy) — the
  // app never links to checkout.
  const val CREATOR_MARKETPLACE = "feat_creator_marketplace"

  private val _entries: MutableList<SettingsEntry> = mutableListOf(
  SettingsEntry(
  key = ACCOUNT, title = "Account",
  summary = "The account this app is signed in to", group = "Account",
  keywords = listOf("account", "email", "profile", "login", "signed in", "user"),
  control = SettingControl.ACCOUNT
  ),
  SettingsEntry(
  key = SHARE_USAGE_STATS, title = "Share usage statistics",
  summary = "Send anonymous feature-usage events (which screens get used — never workout data, names or location). Off keeps everything local.",
  group = "Account",
  keywords = listOf("analytics", "usage", "statistics", "diagnostics", "telemetry", "consent", "privacy", "tracking", "opt out"),
  control = SettingControl.SHARE_USAGE_STATS
  ),
  SettingsEntry(
  key = SYNC_NOW, title = "Sync now",
  summary = "Back up and reconcile your training data with your account",
  group = "Account",
  keywords = listOf("sync", "backup", "upload", "cloud", "reconcile", "refresh", "last synced"),
  control = SettingControl.SYNC_NOW
  ),
  SettingsEntry(
  key = SYNC_WIFI_ONLY, title = "Sync over Wi-Fi only",
  summary = "Skip background sync on mobile data",
  group = "Account",
  keywords = listOf("wifi", "wi-fi", "sync", "data", "mobile", "metered", "cellular"),
  default = false
  ),
  SettingsEntry(
  key = EXPORT_ACCOUNT, title = "Export my data",
  summary = "Download a copy of your account and training data as a JSON file",
  group = "Account",
  keywords = listOf("export", "download", "my data", "gdpr", "ccpa", "portability", "copy", "json", "backup"),
  control = SettingControl.EXPORT_ACCOUNT
  ),
  SettingsEntry(
  key = SIGN_OUT, title = "Sign out",
  summary = "Sign out of your account on this device — your local training data stays",
  group = "Account",
  keywords = listOf("sign out", "log out", "logout", "signout", "account", "switch"),
  control = SettingControl.SIGN_OUT
  ),
  SettingsEntry(
  key = DELETE_ACCOUNT, title = "Delete account",
  summary = "Permanently delete your account and all its data — immediate and irreversible",
  group = "Account",
  keywords = listOf("delete", "account", "remove", "erase", "close", "gdpr", "right to be forgotten", "deletion"),
  control = SettingControl.DELETE_ACCOUNT
  ),
  SettingsEntry(
  key = "dark_mode", title = "Dark Mode",
  summary = "Use a dark colour theme", group = "Appearance",
  keywords = listOf("dark", "theme", "night", "black", "mode", "appearance"),
  default = false
  ),
  SettingsEntry(
  key = "feat_session_timer", title = "Session Timer",
  summary = "Rest countdown & stopwatch during workouts", group = "Session",
  keywords = listOf("timer", "rest", "stopwatch", "countdown", "clock"),
  default = true
  ),
  SettingsEntry(
  key = "timer_sound", title = "Rest Timer Sound",
  summary = "Sound played when the rest timer ends", group = "Session",
  keywords = listOf("sound", "timer", "rest", "ding", "ronnie", "audio", "beep", "alert"),
  control = SettingControl.TIMER_SOUND
  ),
  SettingsEntry(
  key = "feat_weight_autofill", title = "Weight Auto-fill",
  summary = "Carry set 1 weight to remaining sets automatically", group = "Session",
  keywords = listOf("weight", "autofill", "auto", "fill", "carry", "sets", "prefill"),
  default = true
  ),
  SettingsEntry(
  key = "feat_add_exercise_mid_session", title = "Add Exercise Mid-Session",
  summary = "Add new exercises during a workout", group = "Session",
  keywords = listOf("add", "exercise", "session", "workout", "mid", "insert"),
  default = true
  ),
  SettingsEntry(
  key = "feat_progression_picker", title = "Progression Picker",
  summary = "Ask how to progress each exercise after the last set", group = "Session",
  keywords = listOf("progression", "progress", "picker", "overload", "next", "increase"),
  default = true
  ),
  SettingsEntry(
  key = SESSION_EXIT_GUARD, title = "Confirm before leaving a session",
  summary = "Ask before exiting a session with unsaved progress", group = "Session",
  keywords = listOf("exit", "leave", "back", "confirm", "session", "guard", "discard", "unsaved", "quit"),
  default = true
  ),
  SettingsEntry(
  key = SESSION_SUMMARY, title = "Session summary & strain rating",
  summary = "Show a summary with duration, volume and a 1–5 strain rating when you finish a session",
  group = "Session",
  keywords = listOf("summary", "strain", "rating", "rpe", "duration", "volume", "finish", "complete", "recap"),
  default = true
  ),
  SettingsEntry(
  key = SMART_PROGRESSION, title = "Smart progression suggestions",
  summary = "Pre-fill the progression picker with a recommended next step and adjust carry-forward",
  group = "Session",
  keywords = listOf("progression", "smart", "suggestion", "auto", "load", "deload", "double progression", "rpe", "recommend"),
  default = true
  ),
  SettingsEntry(
  key = GOAL_RECALIBRATION, title = "Goal re-calibration prompts",
  summary = "Offer options to repeat, deload or switch programs when a plan completes or stalls",
  group = "Session",
  keywords = listOf("goal", "recalibration", "recalibrate", "stall", "complete", "restart", "deload", "plateau"),
  default = true
  ),
  SettingsEntry(
  key = EXERCISE_SWAPS, title = "Exercise swaps & alternatives",
  summary = "Swap an exercise for an equipment- or pain-aware alternative, with similar-movement browsing",
  group = "Session",
  keywords = listOf("swap", "alternative", "substitute", "equipment", "pain", "injury", "busy", "similar", "replace"),
  default = true
  ),
  SettingsEntry(
  key = EASIER_HARDER, title = "Make-easier / make-harder controls",
  summary = "One-tap regression and progression to an easier or harder variation",
  group = "Session",
  keywords = listOf("easier", "harder", "regress", "progress", "regression", "progression", "variation", "difficulty"),
  default = true
  ),
  SettingsEntry(
  key = TRAVEL_MODE, title = "Travel mode",
  summary = "Translate a session into a hotel-room / minimal-equipment version you log against",
  group = "Session",
  keywords = listOf("travel", "hotel", "bodyweight", "bands", "dumbbell", "minimal", "equipment", "trip", "vacation"),
  default = false
  ),
  SettingsEntry(
  key = INJURY_TRIAGE, title = "Injury prevention & triage",
  summary = "A guided check that swaps aggravating lifts and adds prehab — never medical advice",
  group = "Session",
  keywords = listOf("injury", "pain", "triage", "prehab", "rehab", "knee", "shoulder", "back", "hurt", "ache", "accommodation"),
  default = false
  ),
  SettingsEntry(
  key = REALTIME_FATIGUE, title = "Real-time fatigue scoring",
  summary = "Show a calm Fresh / Working / Fatigued indicator during a session (advanced)",
  group = "Session",
  keywords = listOf("fatigue", "realtime", "real-time", "fresh", "working", "tired", "readiness", "decay", "advanced"),
  default = false
  ),
  SettingsEntry(
  key = CONNECT_WEARABLE, title = "Connect wearable (Health Connect)",
  summary = "Read heart rate from Health Connect — Wear OS, Fitbit, Garmin, Samsung Health and others sync into it. Tap to connect or grant access.",
  group = "Wearables",
  keywords = listOf("wearable", "health", "connect", "watch", "wear os", "fitbit", "garmin",
  "samsung", "google fit", "sync", "permission", "heart", "rate", "hr", "bpm", "band", "tracker", "device"),
  control = SettingControl.WEARABLE_CONNECT
  ),
  SettingsEntry(
  key = SUMMARY_HEART_RATE, title = "Show heart rate in summary",
  summary = "Display average/peak heart rate in the session summary (needs a connected wearable)",
  group = "Wearables",
  keywords = listOf("heart", "rate", "hr", "bpm", "wearable", "watch", "pulse", "summary", "cardio"),
  default = true
  ),
  SettingsEntry(
  key = PLATE_CALCULATOR, title = "Plate Calculator",
  summary = "Per-side barbell plate breakdown for a target weight", group = "Tools",
  keywords = listOf("plate", "calculator", "barbell", "bar", "loading", "kg", "weight", "math"),
  default = true
  ),
  SettingsEntry(
  key = ON_DEMAND_LIBRARY, title = "On-demand workout library",
  summary = "Browse extra sessions recommended for your current block, run them without touching your program",
  group = "Display",
  keywords = listOf("on-demand", "library", "session", "extra", "accessory", "rest day", "missed", "quick", "workout"),
  default = true
  ),
  SettingsEntry(
  key = ACHIEVEMENTS, title = "Achievements & streaks",
  summary = "Earn quiet badges and track training streaks. Local — no account needed.",
  group = "Display",
  keywords = listOf("achievement", "achievements", "streak", "streaks", "badge", "badges", "milestone",
  "gamification", "progress", "trophy", "profile", "rewards"),
  default = true
  ),
  SettingsEntry(
  key = CREATOR_MARKETPLACE, title = "Creator marketplace",
  summary = "Browse programs by Verified Creators and, as a creator, manage your listings and earnings",
  group = "Display",
  keywords = listOf("creator", "marketplace", "verified", "badge", "sell", "programs", "earnings",
  "store", "buy", "purchases"),
  default = true
  ),
  SettingsEntry(
  key = FRIENDS, title = "Friends",
  summary = "Add friends, see their training, share programs and send motivation. Needs an account.",
  group = "Display",
  keywords = listOf("friend", "friends", "social", "invite", "share", "motivation", "flex",
  "code", "add friend", "programs", "notification"),
  default = true
  ),
  SettingsEntry(
  key = HABITS, title = "Habit tracker",
  summary = "Open your daily habits — build streaks alongside your training",
  group = "Display",
  keywords = listOf("habit", "habits", "tracker", "streak", "daily", "routine", "check", "widget"),
  control = SettingControl.HABITS
  ),
  SettingsEntry(
  key = SHOW_INSIGHTS, title = "Show my activity insights",
  summary = "A quiet card if your training tails off — your data only, never shared. Local.",
  group = "Display",
  keywords = listOf("insights", "activity", "dropout", "risk", "inactive", "reminder", "nudge", "retention", "trend"),
  default = true
  ),
  SettingsEntry(
  key = REENGAGEMENT, title = "Re-engagement reminders",
  summary = "Occasional, gentle notifications when you've been away. Opt-in; capped and quiet-hours aware.",
  group = "Session",
  keywords = listOf("reminder", "reminders", "notification", "notifications", "re-engagement", "nudge", "push", "alert", "comeback"),
  default = false
  ),
  SettingsEntry(
  key = REMINDER_SETTINGS, title = "Reminder frequency & quiet hours",
  summary = "How often reminders can appear, and the hours to never disturb you",
  group = "Session",
  keywords = listOf("frequency", "quiet hours", "quiet", "cap", "reminder", "snooze", "notification", "schedule"),
  control = SettingControl.REMINDER_SETTINGS
  ),
  SettingsEntry(
  key = ADVANCED_ANALYTICS, title = "Advanced analytics & trends",
  summary = "Trend charts: PRs over time, volume, consistency and balance (off → basic dashboard)",
  group = "Display",
  keywords = listOf("analytics", "trends", "charts", "graphs", "pr", "1rm", "volume", "consistency", "streak", "adherence", "balance"),
  default = true
  ),
  SettingsEntry(
  key = "feat_1rm_calculator", title = "1RM Calculator",
  summary = "Show the % button in session view to open the 1RM calculator", group = "Display",
  keywords = listOf("1rm", "calculator", "max", "epley", "percentage", "one rep max", "onerm"),
  default = true
  ),
  SettingsEntry(
  key = "claude_api_key", title = "Anthropic API Key",
  summary = "Key for AI-assisted program import", group = "AI Import",
  keywords = listOf("ai", "claude", "anthropic", "api", "key", "import", "gpt"),
  control = SettingControl.API_KEY
  ),
  SettingsEntry(
  key = EXPORT_DATA, title = "Export data",
  summary = "Export your training log to CSV or JSON, or share a summary",
  group = "Data",
  keywords = listOf("export", "csv", "json", "download", "share", "data", "backup", "spreadsheet"),
  control = SettingControl.EXPORT_DATA
  ),
  SettingsEntry(
  key = WRITE_TO_HEALTH_CONNECT, title = "Write workouts to Health Connect",
  summary = "Save completed sessions to Health Connect so other health apps see your training (needs write access)",
  group = "Wearables",
  keywords = listOf("write", "health connect", "workout", "session", "export", "exercise", "calories",
  "share", "ecosystem", "wearable"),
  default = false
  ),
  // Phase 6 — bundled legal docs (rendered offline). Each row's key is the LegalDoc.key so the
  // Settings screen navigates to "legal/<key>".
  SettingsEntry(
  key = "privacy", title = "Privacy Policy",
  summary = "What we collect, why, and your rights over it",
  group = "Legal & privacy",
  keywords = listOf("privacy", "policy", "data", "gdpr", "ccpa", "consent", "collection", "processor", "supabase", "rights"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "terms", title = "Terms of Service",
  summary = "The rules for using the app",
  group = "Legal & privacy",
  keywords = listOf("terms", "service", "tos", "agreement", "acceptable use", "liability", "medical", "gplv3", "governing law"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "health", title = "Health & fitness data notice",
  summary = "How your training and heart-rate data is handled",
  group = "Legal & privacy",
  keywords = listOf("health", "fitness", "data", "heart rate", "workout", "training", "sold", "shared", "notice"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "licenses", title = "Open-source licenses",
  summary = "The open-source components this app is built on",
  group = "Legal & privacy",
  keywords = listOf("open source", "licenses", "licences", "oss", "attribution", "apache", "mit", "gplv3", "credits"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "support", title = "Support",
  summary = "Get help and find answers to common questions",
  group = "Legal & privacy",
  keywords = listOf("support", "help", "contact", "email", "faq", "questions", "problem", "issue"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "creator_agreement", title = "Creator Agreement",
  summary = "Terms for selling programs: the 10% platform fee, payouts, content ownership, tax",
  group = "Legal & privacy",
  keywords = listOf("creator", "agreement", "sell", "marketplace", "fee", "commission", "payout",
  "stripe", "tax", "gst", "licence", "content"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "billing", title = "Subscription & Billing Terms",
  summary = "Verified Creator pricing, auto-renewal, cancellation and price-change notice",
  group = "Legal & privacy",
  keywords = listOf("subscription", "billing", "price", "renewal", "auto-renew", "cancel",
  "verified", "creator", "monthly", "stripe"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "refunds", title = "Refunds Policy",
  summary = "Refunds for subscriptions and program purchases, and your consumer-law rights",
  group = "Legal & privacy",
  keywords = listOf("refund", "refunds", "money back", "consumer", "guarantee", "acl", "return",
  "purchase", "cancel"),
  control = SettingControl.LEGAL_DOC
  ),
  SettingsEntry(
  key = "action_download_template", title = "Download Template",
  summary = "Save a blank Excel program template", group = "Data",
  keywords = listOf("download", "template", "excel", "blank", "xlsx", "export"),
  control = SettingControl.DOWNLOAD_TEMPLATE
  ),
  SettingsEntry(
  key = "action_clear_data", title = "Clear All Data",
  summary = "Permanently delete all programs, sessions and logs", group = "Data",
  keywords = listOf("clear", "delete", "reset", "wipe", "erase", "data"),
  control = SettingControl.CLEAR_DATA
  )
  )

  /** All registered entries, in registration order. */
  val entries: List<SettingsEntry> get() = _entries

  /** Just the boolean toggles — used to build the combined flag state. */
  val toggleEntries: List<SettingsEntry> get() = _entries.filter { it.control == SettingControl.TOGGLE }

  /**
   * Append (or replace, by key) a feature's entry. Idempotent: calling twice with the same key
   * keeps a single row, so a feature can safely register on every startup.
   */
  fun register(entry: SettingsEntry) {
  _entries.removeAll { it.key == entry.key }
  _entries.add(entry)
  }

  /** The default for a toggle key, or false if unknown. */
  fun defaultFor(key: String): Boolean = _entries.firstOrNull { it.key == key }?.default ?: false

  private fun haystack(e: SettingsEntry): String =
  (e.title + " " + e.summary + " " + e.group + " " + e.keywords.joinToString(" ")).lowercase()

  /**
   * Case-insensitive token-AND substring search. A blank query returns every entry in order;
   * otherwise an entry matches only if EVERY whitespace-separated token appears somewhere in
   * its title/summary/group/keywords.
   */
  fun search(query: String): List<SettingsEntry> {
  val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
  if (tokens.isEmpty()) return entries
  return _entries.filter { e -> val h = haystack(e); tokens.all { it in h } }
  }

  /**
   * Search results bucketed by group, preserving [groupOrder] first and then any extra groups
   * in first-seen order. Empty groups are omitted.
   */
  fun grouped(query: String): Map<String, List<SettingsEntry>> {
  val matched = search(query)
  val orderedGroups = (groupOrder + matched.map { it.group }).distinct()
  val result = LinkedHashMap<String, List<SettingsEntry>>()
  for (g in orderedGroups) {
  val items = matched.filter { it.group == g }
  if (items.isNotEmpty()) result[g] = items
  }
  return result
  }
}
