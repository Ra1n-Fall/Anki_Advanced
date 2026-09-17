package com.example.anki_advanced.completion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anki_advanced.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CompletionModeSetupViewModel(application: Application) : AndroidViewModel(application) {

    // DB 인스턴스는 AppDatabase 싱글턴을 공유한다 (화면마다 따로 만들지 않음)
    private val db = AppDatabase.getInstance(application)

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
