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

// 되돌리기(undo)를 위해 채점 직전 카드 상태와 삽입된 리뷰 로그 ID를 함께 보관
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class CompletionStudyViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()

    // ── State ─────────────────────────────────────────────────────────────────

    // 현재 화면 상태: QUESTION(앞면) / ANSWER(뒷면) / DONE(완료)
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    // 현재 표시 중인 카드. null이면 DONE 처리
    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    // 되돌리기 가능 횟수 (UI에서 undo 버튼 활성화 여부에 사용)
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    // DB 작업 중일 때 true → UI에서 버튼 비활성화 등에 활용
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 세션당 목표 카드 수 / 남은 세션 수 (완주 모드 진행 배지에 표시)
    private val _sessionInfo = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    // 현재 덱의 완주 모드 설정 (목표 기간, 허용 윈도우 등)
    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    // 최대 3회 되돌리기를 지원하는 스택 (앱이 살아있는 동안만 유지)
    private val undoStack = ArrayDeque<UndoEntry>()

    private var deckId = -1L

    // targetPeriod / 덱 내 최대 baseInterval 비율.
    // 1.0보다 작으면 복습 간격이 압축되고, 크면 늘어난다.
    private var compressionRatio = 1.0

    // 학습이 허용된 시간대. 기본값은 0~24(하루 전체)
    private var window = AllowedWindow()

    // 마지막 세션 완료 시각. 세션 누락 계산에 사용
    private var lastSessionTime = 0L

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 학습 화면 진입 시 호출.
     *
     * 순서:
     *   1. DB에서 완주 모드 설정 로드
     *   2. 현재 덱의 최대 baseInterval로 compressionRatio 계산
     *   3. 첫 카드 로드
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

    // 앞면 → 뒷면 전환 (UI 상태만 변경, DB 작업 없음)
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    /**
     * 채점 처리 (score: 0=Again, 1=Hard, 2=Good, 3=Easy)
     *
     * 순서:
     *   1. 현재 카드를 undo 스택에 push (되돌리기 대비)
     *   2. 카드 상태(status, nextReviewAt 등) 재계산
     *   3. DB에 저장 + 리뷰 로그 삽입
     *   4. 삽입된 로그 ID를 undo 엔트리에 반영 (삭제 가능하도록)
     *   5. 세션 정보 갱신 → 다음 카드 로드
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
                            deckId        = card.deckId,
                            cardId        = card.id,
                            score         = score,
                            sm2Q          = sm2Q(score),
                            reviewedAt    = now,
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                        )
                    )
                }

                // 로그가 삽입된 후에야 undo 엔트리에 logId를 기록할 수 있다.
                // insert 전에 저장하면 null이므로, removeLast → copy → addLast 패턴 사용
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

    /**
     * 마지막 채점을 취소하고 이전 카드 상태로 복원.
     *
     * 순서:
     *   1. undo 스택에서 마지막 엔트리 꺼냄
     *   2. 삽입된 리뷰 로그 삭제
     *   3. 카드를 채점 전 상태로 DB 복원
     *   4. currentCard를 되돌린 카드로 교체 → QUESTION 상태로 전환
     */
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
     * 완주 모드 활성화 (최초 설정 or 기간/윈도우 변경).
     *
     * 새 설정을 DB에 저장한 뒤, 기존 REVIEW 카드의 nextReviewAt을
     * 새 compressionRatio와 윈도우로 일괄 재계산한다.
     * (이미 학습된 카드들이 새 기간 기준에 맞게 재배치됨)
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
     * 완주 모드 종료.
     *
     * 모드 기간 동안의 리뷰 횟수·정답률을 집계해 각 카드의 baseInterval을 보정한다.
     * (집중 학습 후 간격이 너무 짧게 남지 않도록 밀집도·정확도 기반으로 조정)
     * 마지막으로 isActive=false를 저장해 다음 진입 시 표준 모드로 동작하게 한다.
     */
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val config = _modeConfig.value ?: return@launch
            withContext(Dispatchers.IO) {
                // 모드 시작~현재 구간의 전체 리뷰 수와 정답(score>=2) 수 집계
                val reviewCount  = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                val scores       = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
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
     * 다음 카드 로딩. 우선순위:
     *   1. 시간 된 LEARNING (nextReviewAt <= now) — 단기 기억 강화 최우선
     *   2. 시간 된 REVIEW   (nextReviewAt <= now) — 장기 기억 복습
     *   3. NEW              — 신규 카드 학습
     *   4. 대기 LEARNING    — 1~3이 없을 때 아직 시간 안 된 LEARNING을 조기 등장
     *
     * 4번까지 없으면 DONE 상태로 전환.
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

    // 세션 정보 갱신: 남은 카드 수와 모드 마감 시각으로 세션당 목표 카드 수 재계산
    private suspend fun refreshSessionInfo() {
        val config = _modeConfig.value ?: return
        val now = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) {
            val totalCards     = db.completionCardDao().countAll(deckId)
            val studiedCards   = db.completionCardDao().countStudied(deckId)
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

    // NEW/LEARNING이면 스텝 기반 처리, REVIEW이면 SM-2 재계산으로 분기
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    /**
     * LEARNING 카드 채점 처리.
     *
     * compressionRatio가 반영된 스텝 목록을 기준으로 진행한다.
     * - Again(0): 스텝 0으로 초기화 (처음부터 다시)
     * - Hard(1):  현재 스텝 유지 (같은 간격 재시도)
     * - Good(2):  다음 스텝으로 이동. 마지막 스텝 이후엔 REVIEW 졸업 → SM-2 적용
     * - Easy(3):  스텝 무시하고 즉시 REVIEW 졸업 → SM-2 적용
     */
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)
        return when (score) {
            0 -> // Again: 스텝 0 초기화
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            1 -> { // Hard: 현재 스텝 유지
                val step = card.learningStep.coerceIn(0, steps.lastIndex)
                card.copy(status = CARD_LEARNING, state = score, learningStep = step,
                    nextReviewAt = now + steps[step], lastReviewAt = now)
            }
            2 -> { // Good: 다음 스텝 진행 or REVIEW 졸업
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

    /**
     * REVIEW 카드 채점 처리.
     *
     * - Again(0): LEARNING으로 강등. 스텝 0부터 재학습
     * - Hard/Good/Easy: compressionRatio·window를 반영한 SM-2로 다음 복습 시각 계산
     */
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

    /**
     * 완주 모드 설정이 변경될 때 기존 LEARNING/REVIEW 카드의 nextReviewAt을 일괄 갱신.
     *
     * 구 윈도우 기준 진행도(progress)를 보존하면서 새 윈도우·비율로 재배치하므로
     * 이미 학습한 카드들이 갑자기 몰리거나 사라지지 않는다.
     */
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
