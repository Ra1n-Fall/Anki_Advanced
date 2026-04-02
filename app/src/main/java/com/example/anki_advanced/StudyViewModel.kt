package com.example.anki_advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class StudyUiState { QUESTION, ANSWER, DONE }

private data class Sm2Result(
    val repetition: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val nextReviewAt: Long
)

private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

private val LEARNING_STEPS_MS = listOf(
    1 * 60 * 1000L,
    10 * 60 * 1000L
)

class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    private val undoStack = ArrayDeque<UndoEntry>()

    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var deckId: Long = -1L

    fun startStudy(deckId: Long) {
        this.deckId = deckId
        loadNextCard()
    }

    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    fun applyGrade(score: Int) {
        val card = _currentCard.value ?: return
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val updatedCard = resolveUpdatedCard(card, score, now)

                var logId = -1L
                withContext(Dispatchers.IO) {
                    db.cardDao().updateSm2(
                        id = card.id,
                        state = score,
                        status = updatedCard.status,
                        repetition = updatedCard.repetition,
                        intervalDays = updatedCard.intervalDays,
                        easeFactor = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt
                    )
                    db.cardDao().updateLearningStep(card.id, updatedCard.learningStep)
                    logId = db.reviewLogDao().insert(
                        ReviewLogEntity(
                            deckId = card.deckId,
                            cardId = card.id,
                            score = score,
                            sm2Q = toSm2Q(score),
                            reviewedAt = now,
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                        )
                    )
                }
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                loadNextCard()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun undoLast() {
        if (undoStack.isEmpty()) return
        val undo = undoStack.removeLast()
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)
                    }
                    db.cardDao().update(undo.prevCard)
                }
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val todayStart = startOfTodayMillis(now)

                val learning = db.cardDao().getNextLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                val limits = db.deckDao().getStudyLimits(deckId)
                val newDoneToday = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
                val reviewDoneToday = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

                if (reviewDoneToday < limits.dailyReviewLimit) {
                    val review = db.cardDao().getNextReviewCard(deckId, todayStart)
                    if (review != null) return@withContext review
                }

                if (newDoneToday < limits.dailyNewLimit) {
                    val new = db.cardDao().getNextNewCard(deckId)
                    if (new != null) return@withContext new
                }

                db.cardDao().getNextPendingLearningCard(deckId)
            }

            if (next == null) {
                _uiState.value = StudyUiState.DONE
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION
            }
        }
    }

    // ── SM2 알고리즘 ──

    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW -> resolveUpdatedReviewCard(card, score, now)
            else -> card
        }
    }

    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            1 -> {
                val step = card.learningStep.coerceIn(0, LEARNING_STEPS_MS.lastIndex)
                card.copy(status = CARD_LEARNING, state = score, learningStep = step, nextReviewAt = now + LEARNING_STEPS_MS[step])
            }
            2 -> {
                val nextStep = card.learningStep + 1
                if (nextStep >= LEARNING_STEPS_MS.size) {
                    val sm2 = applySm2(card, score)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
                } else {
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep, nextReviewAt = now + LEARNING_STEPS_MS[nextStep])
                }
            }
            else -> {
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            else -> {
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    private fun applySm2(card: CardEntity, score: Int): Sm2Result {
        val q = toSm2Q(score)
        var ef = card.easeFactor
        var rep = card.repetition
        var interval = card.intervalDays

        when (q) {
            0 -> { rep = 0; interval = 1; ef = (ef - 0.20).coerceAtLeast(1.3) }
            3 -> { rep += 1; interval = when (rep) { 1 -> 1; 2 -> 6; else -> (interval * 1.2).toInt().coerceAtLeast(1) }; ef = (ef - 0.15).coerceAtLeast(1.3) }
            4 -> { rep += 1; interval = when (rep) { 1 -> 1; 2 -> 6; else -> kotlin.math.round(interval * ef).toInt().coerceAtLeast(1) } }
            else -> { rep += 1; interval = when (rep) { 1 -> 1; 2 -> 6; else -> kotlin.math.round(interval * ef * 1.3).toInt().coerceAtLeast(1) }; ef = (ef + 0.15).coerceAtLeast(1.3) }
        }

        val nextAt = addDaysAtStartOfDay(System.currentTimeMillis(), interval)
        return Sm2Result(rep, interval, ef, nextAt)
    }

    private fun toSm2Q(score: Int) = when (score) { 0 -> 0; 1 -> 3; 2 -> 4; else -> 5 }

    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun addDaysAtStartOfDay(nowMillis: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfTodayMillis(nowMillis)
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }
}
