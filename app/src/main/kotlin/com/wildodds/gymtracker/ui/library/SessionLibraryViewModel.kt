package com.wildodds.gymtracker.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.SessionTemplate
import com.wildodds.gymtracker.data.db.entity.SessionTemplateExercise
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Editable row used while creating/editing a library session. */
data class SessionExerciseDraft(
  val key: String = UUID.randomUUID().toString(),
  val name: String = "",
  val sets: Int = 3,
  val repsTarget: String = "8-12",
  val notes: String = ""
)

data class SessionEditorState(
  val templateId: Long? = null,   // null = creating new
  val name: String = "",
  val exercises: List<SessionExerciseDraft> = listOf(SessionExerciseDraft())
)

class SessionLibraryViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))

  val sessions: StateFlow<List<SessionTemplate>> = repo.librarySessions()
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  // Exercise counts per template, for the list subtitle.
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val exerciseCounts: StateFlow<Map<Long, Int>> = sessions
  .mapLatest { list ->
  list.associate { it.id to repo.getLibrarySessionExercisesOnce(it.id).size }
  }
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  private val _editor = MutableStateFlow<SessionEditorState?>(null)
  val editor: StateFlow<SessionEditorState?> = _editor.asStateFlow()

  private val _syncMessage = MutableStateFlow<String?>(null)
  val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()
  fun clearSyncMessage() { _syncMessage.value = null }

  fun startNewSession() { _editor.value = SessionEditorState() }

  fun startEditSession(template: SessionTemplate) {
  viewModelScope.launch {
  val exercises = withContext(Dispatchers.IO) { repo.getLibrarySessionExercisesOnce(template.id) }
  _editor.value = SessionEditorState(
  templateId = template.id,
  name = template.name,
  exercises = exercises.map {
  SessionExerciseDraft(name = it.name, sets = it.sets, repsTarget = it.repsTarget, notes = it.notes)
  }.ifEmpty { listOf(SessionExerciseDraft()) }
  )
  }
  }

  fun cancelEditor() { _editor.value = null }

  fun setEditorName(name: String) { _editor.update { it?.copy(name = name) } }

  fun addExerciseRow() {
  _editor.update { it?.copy(exercises = it.exercises + SessionExerciseDraft()) }
  }

  fun removeExerciseRow(key: String) {
  _editor.update { st -> st?.copy(exercises = st.exercises.filter { it.key != key }) }
  }

  fun updateExercise(key: String, transform: (SessionExerciseDraft) -> SessionExerciseDraft) {
  _editor.update { st -> st?.copy(exercises = st.exercises.map { if (it.key == key) transform(it) else it }) }
  }

  fun saveEditor(onSaved: () -> Unit) {
  val state = _editor.value ?: return
  val rows = state.exercises.filter { it.name.isNotBlank() }
  val muscles = rows.take(4).joinToString(" / ") { it.name.split(" ").firstOrNull() ?: it.name }
  viewModelScope.launch {
  withContext(Dispatchers.IO) {
  val exercises = rows.mapIndexed { idx, d ->
  SessionTemplateExercise(templateId = 0, name = d.name, sets = d.sets,
  repsTarget = d.repsTarget.ifBlank { "8-12" }, notes = d.notes, orderIndex = idx)
  }
  val existingId = state.templateId
  if (existingId == null) {
  repo.saveSessionTemplate(state.name, muscles, "", exercises, source = "Custom")
  } else {
  repo.updateSessionTemplate(
  SessionTemplate(id = existingId, name = state.name.ifBlank { "Untitled Session" },
  muscleGroups = muscles, source = "Custom"),
  exercises
  )
  }
  }
  _editor.value = null
  onSaved()
  }
  }

  fun deleteSession(template: SessionTemplate) {
  viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSessionTemplate(template.id) } }
  }

  fun syncFromPrograms() {
  viewModelScope.launch {
  val added = withContext(Dispatchers.IO) { repo.syncLibraryFromPrograms() }
  _syncMessage.value = if (added == 0) "Library already up to date"
  else "Added $added session${if (added != 1) "s" else ""} from your programs"
  }
  }
}
