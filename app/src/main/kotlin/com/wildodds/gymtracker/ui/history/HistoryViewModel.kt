package com.wildodds.gymtracker.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryItem(
  val completion: SessionCompletion,
  val session: Session,
  val totalVolumeKg: Float
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
  private val db = AppDatabase.getInstance(app)
  private val repo = GymRepository(db)

  private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
  val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

  init {
  viewModelScope.launch {
  repo.allCompletions().collect { completions ->
  val result = withContext(Dispatchers.IO) {
  completions.mapNotNull { completion ->
  val session = repo.getSessionById(completion.sessionId) ?: return@mapNotNull null
  val setLogs = repo.getSessionSetLogs(completion.sessionId, completion.weekNumber)
  val volume = setLogs.values.flatten().sumOf {
  ((it.weightKg ?: 0f) * (it.reps ?: 0)).toDouble()
  }.toFloat()
  HistoryItem(completion, session, volume)
  }
  }
  _items.value = result.sortedByDescending { it.completion.completedAt }
  }
  }
  }
}
