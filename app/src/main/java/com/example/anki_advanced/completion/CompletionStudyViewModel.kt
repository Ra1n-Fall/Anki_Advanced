package com.example.anki_advanced.completion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.anki_advanced.AppDatabase
import com.example.anki_advanced.CARD_LEARNING
import com.example.anki_advanced.CARD_NEW
import com.example.anki_advanced.CARD_REVIEW
import com.example.anki_advanced.CardEntity
import com.example.anki_advanced.ReviewLogEntity
import com.example.anki_advanced.StudyUiState
import com.example.anki_advanced.sm2Q
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 내부 타입 ─────────────────────────────────────────────────────────────────

private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * 기간 완주형 SRS 학습 ViewModel
 *
 * 기존 [com.example.anki_advanced.StudyViewModel]과 동일한 구조(StateFlow, viewModelScope)를 따르되,
 * SM-2 계산·카드 조회·시간 판정을 모두 completion mode 전용 로직으로 교체한다.
 *
 * 주요 차이:
 *   - 시간 압축(compressionRatio) 적용
 *   - 윈도우 기반 due 판정 (절대 시각, todayStart 아님)
 *   - baseInterval, lastReviewAt DB 저장
 *   - 세션 정보(sessionInfo) 노출
 */
class CompletionStudyViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sessionInfo = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    private val undoStack = ArrayDeque<UndoEntry>()
    private var deckId = -1L
    private var compressionRatio = 1.0
    private var window = AllowedWindow()
    private var lastSessionTime = 0L

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 학습 세션 시작
     *
     * 1. 모드 설정 로드
     * 2. compressionRatio 계산
     * 3. 첫 카드 로드
     */
    fun startStudy(deckId: Long) {
        this.deckId = deckId
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                db.completionModeDao().getConfig(deckId)
            }
            _modeConfig.value = config
            if (config != null) {
                window = config.toAllowedWindow()
                compressionRatio = withContext(Dispatchers.IO) {
                    val maxBase = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
                    calculateCompressionRatio(config.targetPeriodMs, maxBase, window)
                }
            }
            loadNextCard()
        }
    }

    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    /**
     * 채점 처리
     *
     * 기존 StudyViewModel.applyGrade()와 동일한 흐름:
     *   push undo → DB 업데이트 → log insert → undo에 logId 반영 → 다음 카드
     */
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
                    db.completionCardDao().updateCompletion(
                        id           = card.id,
                        state        = score,
                        status       = updatedCard.status,
                        repetition   = updatedCard.repetition,
                        baseInterval = updatedCard.baseInterval,
                        easeFactor   = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt,
                        lastReviewAt = now,
                        learningStep = updatedCard.learningStep
                    )
                    logId = db.reviewLogDao().insert(
                        ReviewLogEntity(
                            deckId       = card.deckId,
                            cardId       = card.id,
                            score        = score,
                            sm2Q         = sm2Q(score),
                            reviewedAt   = now,
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                        )
                    )
                }

                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                refreshSessionInfo()
                loadNextCard()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 마지막 채점 되돌리기 (기존 StudyViewModel.undoLast()와 동일 구조) */
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
                refreshSessionInfo()
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 모드 관리 ─────────────────────────────────────────────────────────────

    /**
     * completion mode 활성화 (최초 진입 또는 설정 변경)
     *
     * 설정 저장 후 기존 REVIEW 카드의 nextReviewAt을 새 설정으로 재계산한다.
     */
    fun activateCompletionMode(
        deckId: Long,
        targetPeriodMs: Long,
        windowStartHour: Int,
        windowEndHour: Int,
        sessionIntervalMs: Long = 86_400_000L
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newConfig = CompletionModeConfigEntity(
                deckId            = deckId,
                targetPeriodMs    = targetPeriodMs,
                windowStartHour   = windowStartHour,
                windowEndHour     = windowEndHour,
                modeStartAt       = now,
                sessionIntervalMs = sessionIntervalMs,
                isActive          = true
            )
            withContext(Dispatchers.IO) {
                val oldConfig = db.completionModeDao().getConfig(deckId)
                db.completionModeDao().upsert(newConfig)
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()
        }
    }

    /**
     * completion mode 종료
     *
     * 1. 모드 비활성화
     * 2. 각 카드에 applyModeReflection() 적용
     */
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val config = _modeConfig.value ?: return@launch
            withContext(Dispatchers.IO) {
                // 모드 중 리뷰 통계 조회 (모드 시작 시각 ~ 현재)
                val reviewCount = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                // 정답 = score >= 2 (Good, Easy)
                val scores = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
                val correctCount = scores.filter { it.score >= 2 }.sumOf { it.cnt }

                val studiedCards = db.completionCardDao().getAllStudiedCards(deckId)
                studiedCards.forEach { card ->
                    val newNextReviewAt = applyModeReflection(
                        card             = card,
                        modeReviewCount  = reviewCount,
                        modeCorrectCount = correctCount,
                        modeDurationMs   = now - config.modeStartAt,
                        now              = now
                    )
                    db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
                }
                db.completionModeDao().setActive(deckId, false)
            }
            _modeConfig.value = config.copy(isActive = false)
        }
    }

    // ── 내부 로직 ─────────────────────────────────────────────────────────────

    /**
     * 카드 로딩 우선순위 (spec §9.3 + §13.1 기준):
     *   1. LEARNING due (nextReviewAt <= now)
     *   2. REVIEW due (nextReviewAt <= now) — URGENT → HIGH → NORMAL 정렬은 다음 카드 선택 시
     *   3. NEW
     *   4. LEARNING 대기 (조기 등장)
     */
    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()

                val learning = db.completionCardDao().getNextDueLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                val review = db.completionCardDao().getNextDueReviewCard(deckId, now)
                if (review != null) return@withContext review

                val new = db.completionCardDao().getNextNewCard(deckId)
                if (new != null) return@withContext new

                db.completionCardDao().getNextPendingLearningCard(deckId)
            }

            if (next == null) {
                _uiState.value = StudyUiState.DONE
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION
            }
        }
    }

    private suspend fun refreshSessionInfo() {
        val config = _modeConfig.value ?: return
        val now = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) {
            val totalCards    = db.completionCardDao().countAll(deckId)
            val studiedCards  = db.completionCardDao().countStudied(deckId)
            val remainingCards = totalCards - studiedCards

            calculateSession(
                remainingCards    = remainingCards.coerceAtLeast(0),
                now               = now,
                modeEndMs         = config.modeEndAt,
                sessionIntervalMs = config.sessionIntervalMs,
                lastSessionTime   = lastSessionTime,
                window            = window
            )
        }
        _sessionInfo.value = info
    }

    // ── 카드 상태 결정 ────────────────────────────────────────────────────────

    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)
        return when (score) {
            0 -> // Again: step 0 초기화
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            1 -> { // Hard: 현재 step 유지
                val step = card.learningStep.coerceIn(0, steps.lastIndex)
                card.copy(status = CARD_LEARNING, state = score, learningStep = step,
                    nextReviewAt = now + steps[step], lastReviewAt = now)
            }
            2 -> { // Good: 다음 step 또는 REVIEW 졸업
                val nextStep = card.learningStep + 1
                if (nextStep >= steps.size) {
                    val sm2 = applySm2(card, score, compressionRatio, window, now)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                        repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                        easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                        lastReviewAt = now)
                } else {
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep,
                        nextReviewAt = now + steps[nextStep], lastReviewAt = now)
                }
            }
            else -> { // Easy: 즉시 REVIEW 졸업
                val sm2 = applySm2(card, score, compressionRatio, window, now)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                    repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                    easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                    lastReviewAt = now)
            }
        }
    }

    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> { // Again: LEARNING 강등
                val steps = getCompressedSteps(compressionRatio)
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            }
            else -> { // Hard/Good/Easy: SM-2 재계산
                val sm2 = applySm2(card, score, compressionRatio, window, now)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                    repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                    easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                    lastReviewAt = now)
            }
        }
    }

    // ── 모드 전환 시 전체 카드 재계산 ────────────────────────────────────────

    private suspend fun recalculateAllCardsInternal(
        deckId: Long,
        oldConfig: CompletionModeConfigEntity?,
        newConfig: CompletionModeConfigEntity,
        now: Long
    ) {
        val oldWindow = oldConfig?.toAllowedWindow() ?: AllowedWindow()
        val newWindow = newConfig.toAllowedWindow()
        val maxBase   = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
        val newRatio  = calculateCompressionRatio(newConfig.targetPeriodMs, maxBase, newWindow)

        val cards = db.completionCardDao().getAllStudiedCards(deckId)
        cards.forEach { card ->
            val newNextReviewAt = recalculateNextReviewAt(card, oldWindow, newWindow, newRatio, now)
            db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
        }
        compressionRatio = newRatio
    }
}
