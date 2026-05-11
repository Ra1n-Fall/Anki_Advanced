package com.example.anki_advanced.completion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.anki_advanced.AppDatabase
import com.example.anki_advanced.MIGRATION_1_2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CompletionModeSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build()

    private val _totalCards = MutableStateFlow(0)
    val totalCards: StateFlow<Int> = _totalCards.asStateFlow()

    // 기존 설정의 종료 시각 (수정 모드일 때 null이 아님)
    private val _existingEndAt = MutableStateFlow<Long?>(null)
    val existingEndAt: StateFlow<Long?> = _existingEndAt.asStateFlow()

    fun load(deckId: Long) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                db.cardDao().countAllCards(deckId)
            }
            _totalCards.value = count

            val config = withContext(Dispatchers.IO) {
                db.completionModeDao().getConfig(deckId)
            }
            _existingEndAt.value = config?.takeIf { it.isActive }?.modeEndAt
        }
    }

    fun save(deckId: Long, endDateMs: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val targetPeriodMs = (endDateMs - now).coerceAtLeast(86_400_000L)
            withContext(Dispatchers.IO) {
                db.completionModeDao().upsert(
                    CompletionModeConfigEntity(
                        deckId = deckId,
                        targetPeriodMs = targetPeriodMs,
                        modeStartAt = now,
                        isActive = true
                    )
                )
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
