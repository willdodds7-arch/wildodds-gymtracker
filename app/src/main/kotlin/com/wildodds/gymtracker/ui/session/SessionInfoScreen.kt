@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.session

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.intelligence.ExerciseGraph
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.settings.FeatureFlags
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ── ViewModel ──────────────────────────────────────────────────────────────────

data class SessionInfoUiState(
  val session: Session? = null,
  val exercises: List<Exercise> = emptyList(),
  val programName: String = "",
  val isLoading: Boolean = true
)

class SessionInfoViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {

  private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: 0L
  val weekNumber: Int = savedStateHandle.get<Int>("weekNumber") ?: 1

  private val repo = GymRepository(AppDatabase.getInstance(app))

  private val _state = MutableStateFlow(SessionInfoUiState())
  val state: StateFlow<SessionInfoUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val session = repo.getSessionById(sessionId)
      val exercises = if (session != null) repo.getExercisesForSession(sessionId) else emptyList()
      val programName = repo.getCurrentProgram()?.name ?: ""
      _state.value = SessionInfoUiState(session, exercises, programName, isLoading = false)
    }
  }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun SessionInfoScreen(
  navController: NavController,
  vm: SessionInfoViewModel = viewModel()
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val accent = LocalAccentColor.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  if (state.isLoading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = accent)
    }
    return
  }
  val session = state.session ?: run {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Session not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .windowInsetsPadding(WindowInsets.statusBars)
  ) {
    // Top bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = { navController.popBackStack() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(session.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        val days = "Week ${vm.weekNumber} · Day ${session.dayNumber} · ${state.exercises.size} exercises"
        Text(days, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      }
    }

    // Body — read-only session plan
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
    ) {
      if (session.muscleGroups.isNotBlank()) {
        Text(session.muscleGroups, color = accent, fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
      }

      if (state.exercises.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
          Text("No exercises in this session yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        val swapsEnabled = FeatureFlags.isEnabled(SettingsRegistry.EXERCISE_SWAPS)
        val graphEngine = remember { ExerciseGraph.engine(context) }
        state.exercises.forEachIndexed { i, ex ->
          GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                  Text("${i + 1}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(ex.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                  color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
              }
              Spacer(Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoPill("${ex.sets} × ${ex.repsTarget}", accent)
                if (ex.rpeTarget.isNotBlank()) InfoPill("RPE ${ex.rpeTarget}", accent)
                if (ex.pct1rmTarget.isNotBlank()) InfoPill(ex.pct1rmTarget, accent)
              }
              if (ex.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(ex.notes, fontStyle = FontStyle.Italic, fontSize = 13.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              if (swapsEnabled) {
                val similar = remember(ex.name) { graphEngine.findSimilar(ex.name).take(3) }
                if (similar.isNotEmpty()) {
                  Spacer(Modifier.height(6.dp))
                  Text("Similar: " + similar.joinToString(", ") { it.node.name },
                    style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.85f))
                }
              }
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }

    // Share bar
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 8.dp,
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedButton(
          onClick = {
            scope.launch {
              val bmp = withContext(Dispatchers.Default) {
                renderSessionImage(state.programName, session, state.exercises, vm.weekNumber)
              }
              shareSessionImage(context, bmp, session.name)
            }
          },
          modifier = Modifier.weight(1f).height(50.dp),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, accent),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
        ) {
          Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Share image", fontWeight = FontWeight.SemiBold)
        }
        Button(
          onClick = {
            shareSessionText(context, session.name,
              buildShareText(state.programName, session, state.exercises, vm.weekNumber))
          },
          modifier = Modifier.weight(1f).height(50.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) {
          Icon(Icons.Default.Notes, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Share text", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
private fun InfoPill(text: String, accent: androidx.compose.ui.graphics.Color) {
  Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
  }
}

// ── Share: plain text ──────────────────────────────────────────────────────────

internal fun buildShareText(programName: String, session: Session, exercises: List<Exercise>, weekNumber: Int): String {
  val sb = StringBuilder()
  val header = if (programName.isNotBlank()) "$programName — ${session.name}" else session.name
  sb.appendLine(header)
  sb.appendLine("Week $weekNumber · Day ${session.dayNumber}")
  if (session.muscleGroups.isNotBlank()) sb.appendLine(session.muscleGroups)
  sb.appendLine()
  exercises.forEachIndexed { i, ex ->
    val extras = buildList {
      if (ex.rpeTarget.isNotBlank()) add("RPE ${ex.rpeTarget}")
      if (ex.pct1rmTarget.isNotBlank()) add(ex.pct1rmTarget)
    }
    val tail = if (extras.isEmpty()) "" else "  (${extras.joinToString(", ")})"
    sb.appendLine("${i + 1}. ${ex.name} — ${ex.sets} × ${ex.repsTarget}$tail")
    if (ex.notes.isNotBlank()) sb.appendLine("   ${ex.notes}")
  }
  sb.appendLine()
  sb.append("Shared from Wild Odds Gym Tracker")
  return sb.toString()
}

private fun shareSessionText(context: Context, subject: String, text: String) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, text)
  }
  context.startActivity(Intent.createChooser(intent, "Share session"))
}

// ── Share: image ───────────────────────────────────────────────────────────────

private fun shareSessionImage(context: Context, bmp: Bitmap, subject: String) {
  val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
  val file = File(dir, "session_share.png")
  FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/png"
    putExtra(Intent.EXTRA_STREAM, uri)
    putExtra(Intent.EXTRA_SUBJECT, subject)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  context.startActivity(Intent.createChooser(intent, "Share session"))
}

/** Renders the session plan to a branded PNG (brand dark-blue header + accent strip). */
private fun renderSessionImage(programName: String, session: Session, exercises: List<Exercise>, weekNumber: Int): Bitmap {
  val w = 1080
  val pad = 56f
  val contentW = w - pad * 2

  val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  val italic = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
  val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 54f; typeface = bold }
  val subPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8DDF5.toInt(); textSize = 32f }
  val numPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF60B4E8.toInt(); textSize = 42f; typeface = bold }
  val namePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A2E.toInt(); textSize = 42f; typeface = bold }
  val metaPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E6DA4.toInt(); textSize = 32f; typeface = bold }
  val notePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt(); textSize = 30f; typeface = italic }
  val footPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA4B2.toInt(); textSize = 26f }

  fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
    if (text.isBlank()) return emptyList()
    val out = mutableListOf<String>()
    var cur = StringBuilder()
    for (word in text.split(" ")) {
      val cand = if (cur.isEmpty()) word else "$cur $word"
      if (paint.measureText(cand) <= maxW) cur = StringBuilder(cand)
      else { if (cur.isNotEmpty()) out.add(cur.toString()); cur = StringBuilder(word) }
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
  }

  data class Line(val text: String, val x: Float, val y: Float, val paint: Paint)
  val lines = mutableListOf<Line>()

  // Header
  var hy = pad + 56f
  wrap(session.name, titlePaint, contentW).forEach { lines.add(Line(it, pad, hy, titlePaint)); hy += 62f }
  val subtitle = buildList {
    if (programName.isNotBlank()) add(programName)
    add("Week $weekNumber"); add("Day ${session.dayNumber}")
  }.joinToString("   ·   ")
  lines.add(Line(subtitle, pad, hy + 4f, subPaint)); hy += 44f
  if (session.muscleGroups.isNotBlank()) { lines.add(Line(session.muscleGroups, pad, hy + 2f, subPaint)); hy += 40f }
  val headerHeight = hy + 28f

  // Body
  var y = headerHeight + 56f
  exercises.forEachIndexed { i, ex ->
    lines.add(Line("${i + 1}.", pad, y, numPaint))
    lines.add(Line(ex.name, pad + 70f, y, namePaint)); y += 50f
    val meta = buildList {
      add("${ex.sets} × ${ex.repsTarget}")
      if (ex.rpeTarget.isNotBlank()) add("RPE ${ex.rpeTarget}")
      if (ex.pct1rmTarget.isNotBlank()) add(ex.pct1rmTarget)
    }.joinToString("      ")
    lines.add(Line(meta, pad + 70f, y, metaPaint)); y += 40f
    if (ex.notes.isNotBlank()) {
      wrap(ex.notes, notePaint, contentW - 70f).forEach { lines.add(Line(it, pad + 70f, y, notePaint)); y += 36f }
    }
    y += 24f
  }
  val footerY = y + 18f
  lines.add(Line("Wild Odds Gym Tracker", pad, footerY, footPaint))
  val totalH = (footerY + 44f).toInt().coerceAtLeast(headerHeight.toInt() + 80)

  val bmp = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  canvas.drawColor(0xFFF0F8FF.toInt())
  canvas.drawRect(0f, 0f, w.toFloat(), headerHeight, Paint().apply { color = 0xFF1E3A5F.toInt() })
  canvas.drawRect(0f, headerHeight, w.toFloat(), headerHeight + 8f, Paint().apply { color = 0xFF60B4E8.toInt() })
  lines.forEach { canvas.drawText(it.text, it.x, it.y, it.paint) }
  return bmp
}
