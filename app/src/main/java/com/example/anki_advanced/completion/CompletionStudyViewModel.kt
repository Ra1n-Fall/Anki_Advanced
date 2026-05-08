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

// [기존과 동일] UndoEntry 구조 동일
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

    // [기존과 동일] DB 인스턴스 생성 방식 동일. addMigrations 추가됨
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()

    // ── State ─────────────────────────────────────────────────────────────────

    // [기존과 동일] uiState, currentCard, undoStackSize, isLoading StateFlow 구조 동일
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // [기존과 다름] progress 대신 sessionInfo + sessionDone 노출
    // 기존: StudyProgress(done, total) — 오늘 학습 진행 바용
    // 완주: SessionInfo(cardsPerSession, requiredSessions) — 마감일 기준 세션 목표용
    private val _sessionInfo = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    // 현재 세션에서 완료한 카드 수 — 세션이 시작될 때마다 0으로 초기화
    private val _sessionDone = MutableStateFlow(0)
    val sessionDone: StateFlow<Int> = _sessionDone.asStateFlow()

    // 세션 시작 시 확정된 목표 카드 수 — 채점 중 cardsPerSession이 변해도 분모는 고정
    private val _sessionTarget = MutableStateFlow(1)
    val sessionTarget: StateFlow<Int> = _sessionTarget.asStateFlow()

    // [기존과 다름] 완주 모드 설정을 StateFlow로 노출 (기존에 없음)
    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    // [기존과 동일] undoStack, deckId 동일
    private val undoStack = ArrayDeque<UndoEntry>()
    private var deckId = -1L

    // [기존과 다름] 아래 네 필드 없음
    private var compressionRatio = 1.0   // targetPeriod / maxBaseInterval; SM-2 간격을 이 비율로 압축
    private var sessionIntervalMs = 86_400_000L  // compressionRatio에서 자동 산출; 1일 * ratio (최소 30분)
    private var window = AllowedWindow() // 학습 허용 시간대; 윈도우 밖 시간은 간격 계산에서 제외
    private var lastSessionTime = 0L     // 마지막 세션 완료 시각; 누락 세션 수 계산에 사용

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 학습 세션 시작
     *
     * 1. 모드 설정 로드
     * 2. compressionRatio 계산
     * 3. 첫 카드 로드
     */
    // [기존과 다름] 기존은 loadNextCard() 바로 호출
    // 완주: 먼저 모드 설정·compressionRatio 로드 후 loadNextCard() 호출
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
                sessionIntervalMs = deriveSessionInterval(compressionRatio)
            }
            _sessionDone.value = 0  // 세션 시작 시 초기화
            refreshSessionInfo()    // 초기 cardsPerSession 확정
            _sessionTarget.value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val dueLearning  = db.completionCardDao().countDueLearning(deckId, now)
                val dueReview    = db.completionCardDao().countDueReview(deckId, now)
                val newThisSession = _sessionInfo.value.cardsPerSession
                // 세션에서 실제로 볼 카드 수 = 이미 due인 카드 + 이번에 새로 볼 NEW 카드
                (dueLearning + dueReview + newThisSession).coerceAtLeast(1)
            }
            loadNextCard()
        }
    }

    // [기존과 동일] showAnswer()
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
                    // [기존과 다름] 기존: updateSm2() + updateLearningStep() 두 쿼리 분리
                    // 완주: updateCompletion() 단일 쿼리로 baseInterval·lastReviewAt 포함 저장
                    db.completionCardDao().updateCompletion(
                        id           = card.id,
                        state        = score,
                        status       = updatedCard.status,
                        repetition   = updatedCard.repetition,
                        baseInterval = updatedCard.baseInterval,   // ms 단위 간격 (기존에 없음)
                        easeFactor   = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt,
                        lastReviewAt = now,                        // 마지막 복습 시각 (기존에 없음)
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

                // [기존과 동일] logId 확정 후 undo 엔트리 교체
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                // [기존과 다름] 기존: loadNextCard()만 호출
                // 완주: refreshSessionInfo() → loadNextCard() 순서로 세션 정보도 갱신
                _sessionDone.value += 1  // 세션 내 완료 카드 수 증가
                refreshSessionInfo()
                loadNextCard()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 마지막 채점 되돌리기 (기존 StudyViewModel.undoLast()와 동일 구조) */
    // [기존과 다름] 기존: refreshProgress() 호출
    // 완주: refreshSessionInfo() 호출
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
                _sessionDone.value = (_sessionDone.value - 1).coerceAtLeast(0)  // undo 시 세션 완료 수 감소
                refreshSessionInfo()
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 모드 관리 ─────────────────────────────────────────────────────────────

    // [기존에 없음] completion mode 전용 함수들

    /**
     * completion mode 활성화 (최초 진입 또는 설정 변경)
     *
     * 설정 저장 후 기존 REVIEW 카드의 nextReviewAt을 새 설정으로 재계산한다.
     */
    fun activateCompletionMode(
        deckId: Long,
        targetPeriodMs: Long,
        windowStartHour: Int,
        windowEndHour: Int
        // sessionIntervalMs는 compressionRatio에서 자동 산출하므로 파라미터 제거
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newWindow = AllowedWindow(windowStartHour, windowEndHour)
            val maxBase = withContext(Dispatchers.IO) {
                db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
            }
            val newRatio = calculateCompressionRatio(targetPeriodMs, maxBase, newWindow)
            val newSessionIntervalMs = deriveSessionInterval(newRatio)

            val newConfig = CompletionModeConfigEntity(
                deckId            = deckId,
                targetPeriodMs    = targetPeriodMs,
                windowStartHour   = windowStartHour,
                windowEndHour     = windowEndHour,
                modeStartAt       = now,
                sessionIntervalMs = newSessionIntervalMs,  // ratio 기반 자동 계산값 저장
                isActive          = true
            )
            withContext(Dispatchers.IO) {
                val oldConfig = db.completionModeDao().getConfig(deckId)
                db.completionModeDao().upsert(newConfig)
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()
            sessionIntervalMs = newSessionIntervalMs
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
                val reviewCount = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
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
    // [기존과 다름] 우선순위 1·4는 동일
    // 기존 2: nextReviewAt <= todayStart + dailyReviewLimit 한도 체크
    // 완주  2: nextReviewAt <= now (ms 단위 절대 시각, 한도 없음)
    // 기존 3: dailyNewLimit 한도 체크
    // 완주  3: 한도 없이 NEW 카드 등장
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

    // [기존과 다름] 기존: refreshProgress() — 오늘 done/total 카운트
    // 완주: refreshSessionInfo() — 마감일 기준 세션당 목표 카드 수 계산
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
                sessionIntervalMs = sessionIntervalMs,  // config 값 대신 ratio 기반 인메모리 값 사용
                lastSessionTime   = lastSessionTime,
                window            = window
            )
        }
        _sessionInfo.value = info
    }

    // ── 카드 상태 결정 ────────────────────────────────────────────────────────

    // [기존과 동일] resolveUpdatedCard() 분기 구조 동일
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    // [기존과 다름] 스텝 목록이 고정 LEARNING_STEPS_MS 대신 getCompressedSteps(compressionRatio)
    // Good에서 REVIEW 졸업 시 applySm2()에 compressionRatio·window 추가 전달
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)  // compressionRatio 반영된 압축 스텝
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

    // [기존과 다름] Again 강등 시 스텝이 고정값 대신 getCompressedSteps(compressionRatio)
    // Hard/Good/Easy SM-2 호출 시 compressionRatio·window 추가 전달
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

    // [기존에 없음] 설정 변경 시 기존 카드의 nextReviewAt을 새 ratio·window 기준으로 일괄 갱신
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
        sessionIntervalMs = deriveSessionInterval(newRatio)
    }

    // compressionRatio → sessionIntervalMs 자동 산출
    // 1일 * ratio: ratio=1.0 → 1일 1세션, ratio=0.5 → 12시간 2세션, ratio=0.1 → ~2.4시간 ~10세션
    // 최소 30분 하한: 너무 잦은 세션 알림 방지
    private fun deriveSessionInterval(ratio: Double): Long =
        (86_400_000L * ratio).toLong().coerceIn(30 * 60 * 1000L, 86_400_000L)
}
