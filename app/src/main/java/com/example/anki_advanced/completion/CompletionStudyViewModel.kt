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

// undo 1회분: 채점 직전 카드 스냅샷 + 삽입된 ReviewLog PK (삭제 복원용)
private data class UndoEntry(
    val prevCard: CardEntity,           // 채점 전 카드 상태 — undo 시 DB에 덮어씀
    val insertedReviewLogId: Long?      // 채점 시 삽입된 로그 ID — null이면 아직 삽입 전
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

    // ViewModel 생명주기와 동일한 단일 DB 인스턴스
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()   // 마이그레이션 누락 시 파괴적 재생성 허용

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)  // 초기 상태: 앞면 표시
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()   // UI가 구독하는 읽기 전용 노출

    private val _currentCard = MutableStateFlow<CardEntity?>(null)  // null → 카드 없음 → DONE
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    private val _undoStackSize = MutableStateFlow(0)                // 0이면 undo 버튼 비활성화
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    private val _isLoading = MutableStateFlow(false)                // true인 동안 버튼 입력 차단
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sessionInfo = MutableStateFlow(SessionInfo(0, 0))  // 세션당 목표 카드 수 / 남은 세션 수
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)  // null이면 완주 모드 미활성
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    private val undoStack = ArrayDeque<UndoEntry>()  // 채점마다 push, undo마다 pop
    private var deckId = -1L                         // startStudy() 호출 전까지 미초기화
    private var compressionRatio = 1.0               // targetPeriod / maxBaseInterval 비율; <1이면 간격 압축
    private var window = AllowedWindow()             // 학습 허용 시간대; 기본값 0~24(전일)
    private var lastSessionTime = 0L                 // 마지막 세션 완료 시각; 누락 세션 계산에 사용

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
                db.completionModeDao().getConfig(deckId)  // 완주 모드 설정 조회
            }
            _modeConfig.value = config
            if (config != null) {
                window = config.toAllowedWindow()          // 설정에서 허용 시간대 객체 생성
                compressionRatio = withContext(Dispatchers.IO) {
                    val maxBase = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L  // 덱 내 최대 복습 간격
                    calculateCompressionRatio(config.targetPeriodMs, maxBase, window)       // 압축 비율 계산
                }
            }
            loadNextCard()  // 비율·윈도우 확정 후 첫 카드 로드
        }
    }

    // 앞면 → 뒷면 전환: DB 작업 없이 상태값만 변경
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
        val card = _currentCard.value ?: return                               // 카드 없으면 무시
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))  // ① undo 스냅샷 저장 (logId는 아직 모름)
        _undoStackSize.value = undoStack.size
        _isLoading.value = true                                               // ② 로딩 시작 → 버튼 비활성화

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val updatedCard = resolveUpdatedCard(card, score, now)        // ③ 새 카드 상태 계산 (DB 접근 없음)

                var logId = -1L
                withContext(Dispatchers.IO) {
                    db.completionCardDao().updateCompletion(                  // ④ 카드 갱신 (baseInterval·lastReviewAt 포함)
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
                    logId = db.reviewLogDao().insert(                         // ⑤ 리뷰 로그 삽입 → 반환값이 PK
                        ReviewLogEntity(
                            deckId       = card.deckId,
                            cardId       = card.id,
                            score        = score,
                            sm2Q         = sm2Q(score),                      // 0~5 스케일로 변환된 SM-2 품질 점수
                            reviewedAt   = now,
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0  // 첫 학습 여부 기록
                        )
                    )
                }

                // ⑥ insert 완료 후에야 logId 확정 → undo 엔트리 교체 (data class라 불변, copy 필요)
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                refreshSessionInfo()  // ⑦ 남은 카드 수 변화 반영
                loadNextCard()        // ⑧ 다음 카드 화면에 올리기
            } finally {
                _isLoading.value = false  // 예외 발생해도 반드시 로딩 해제
            }
        }
    }

    /** 마지막 채점 되돌리기 (기존 StudyViewModel.undoLast()와 동일 구조) */
    fun undoLast() {
        if (undoStack.isEmpty()) return          // 되돌릴 항목 없으면 무시
        val undo = undoStack.removeLast()
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)  // 삽입됐던 로그 삭제
                    }
                    db.cardDao().update(undo.prevCard)  // 채점 전 카드 상태로 DB 복원
                }
                refreshSessionInfo()
                _currentCard.value = undo.prevCard      // 되돌린 카드를 다시 화면에 표시
                _uiState.value = StudyUiState.QUESTION  // 앞면부터 다시 보여줌
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
        targetPeriodMs: Long,         // 목표 완주 기간 (ms)
        windowStartHour: Int,         // 학습 허용 시작 시각 (0~23)
        windowEndHour: Int,           // 학습 허용 종료 시각 (1~24)
        sessionIntervalMs: Long = 86_400_000L  // 세션 간격; 기본값 1일
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newConfig = CompletionModeConfigEntity(
                deckId            = deckId,
                targetPeriodMs    = targetPeriodMs,
                windowStartHour   = windowStartHour,
                windowEndHour     = windowEndHour,
                modeStartAt       = now,              // 모드 시작 시각을 현재로 기록
                sessionIntervalMs = sessionIntervalMs,
                isActive          = true
            )
            withContext(Dispatchers.IO) {
                val oldConfig = db.completionModeDao().getConfig(deckId)  // 구 설정 보존 (progress 재계산용)
                db.completionModeDao().upsert(newConfig)                   // 없으면 insert, 있으면 replace
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)  // 기존 카드 nextReviewAt 일괄 갱신
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()  // 인메모리 윈도우도 즉시 교체
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
            val config = _modeConfig.value ?: return@launch  // 활성 설정이 없으면 무시
            withContext(Dispatchers.IO) {
                // 모드 시작 ~ 현재 구간의 전체 리뷰 수 집계
                val reviewCount = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                // score별 카운트 조회 후 Good(2)·Easy(3)만 정답으로 집계
                val scores = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
                val correctCount = scores.filter { it.score >= 2 }.sumOf { it.cnt }

                val studiedCards = db.completionCardDao().getAllStudiedCards(deckId)
                studiedCards.forEach { card ->
                    // 밀집도·정확도 기반으로 baseInterval 보정 후 새 nextReviewAt 반환
                    val newNextReviewAt = applyModeReflection(
                        card             = card,
                        modeReviewCount  = reviewCount,
                        modeCorrectCount = correctCount,
                        modeDurationMs   = now - config.modeStartAt,  // 모드 실제 지속 시간
                        now              = now
                    )
                    db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
                }
                db.completionModeDao().setActive(deckId, false)  // 모드 비활성화 저장
            }
            _modeConfig.value = config.copy(isActive = false)  // 인메모리 상태도 동기화
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
                if (learning != null) return@withContext learning  // 1순위: 시간 된 LEARNING 즉시 반환

                val review = db.completionCardDao().getNextDueReviewCard(deckId, now)
                if (review != null) return@withContext review      // 2순위: 시간 된 REVIEW

                val new = db.completionCardDao().getNextNewCard(deckId)
                if (new != null) return@withContext new            // 3순위: 신규 카드

                db.completionCardDao().getNextPendingLearningCard(deckId)  // 4순위: 시간 안 된 LEARNING 조기 등장
            }

            if (next == null) {
                _uiState.value = StudyUiState.DONE   // 4순위까지 없으면 세션 종료
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION
            }
        }
    }

    // 채점·undo 후 호출: 남은 카드 수와 모드 마감 시각으로 세션당 목표 카드 수 재산출
    private suspend fun refreshSessionInfo() {
        val config = _modeConfig.value ?: return  // 완주 모드 미활성이면 갱신 불필요
        val now = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) {
            val totalCards    = db.completionCardDao().countAll(deckId)
            val studiedCards  = db.completionCardDao().countStudied(deckId)  // status != 0인 카드 수
            val remainingCards = totalCards - studiedCards                   // 아직 NEW인 카드 수

            calculateSession(
                remainingCards    = remainingCards.coerceAtLeast(0),  // 음수 방지
                now               = now,
                modeEndMs         = config.modeEndAt,                 // modeStartAt + targetPeriodMs
                sessionIntervalMs = config.sessionIntervalMs,
                lastSessionTime   = lastSessionTime,                  // 누락 세션 계산용
                window            = window
            )
        }
        _sessionInfo.value = info
    }

    // ── 카드 상태 결정 ────────────────────────────────────────────────────────

    // NEW·LEARNING이면 스텝 기반, REVIEW이면 SM-2 기반으로 분기
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card  // 예외 상태면 변경 없이 반환
        }
    }

    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)  // compressionRatio 반영된 압축 스텝 목록
        return when (score) {
            0 -> // Again: learningStep 0으로 초기화 → steps[0] 후 재등장
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            1 -> { // Hard: 현재 스텝 유지 → 동일 간격 재시도
                val step = card.learningStep.coerceIn(0, steps.lastIndex)  // 범위 초과 방어
                card.copy(status = CARD_LEARNING, state = score, learningStep = step,
                    nextReviewAt = now + steps[step], lastReviewAt = now)
            }
            2 -> { // Good: 다음 스텝으로 진행, 마지막 스텝 초과 시 REVIEW 졸업
                val nextStep = card.learningStep + 1
                if (nextStep >= steps.size) {  // 모든 스텝 통과 → REVIEW 승격
                    val sm2 = applySm2(card, score, compressionRatio, window, now)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                        repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                        easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                        lastReviewAt = now)
                } else {                        // 아직 스텝이 남아있으면 다음 스텝으로
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep,
                        nextReviewAt = now + steps[nextStep], lastReviewAt = now)
                }
            }
            else -> { // Easy: 스텝 전부 건너뛰고 즉시 REVIEW 졸업
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
            0 -> { // Again: LEARNING으로 강등 → 스텝 0부터 재학습
                val steps = getCompressedSteps(compressionRatio)
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            }
            else -> { // Hard·Good·Easy: compressionRatio·window 반영 SM-2로 다음 복습 시각 계산
                val sm2 = applySm2(card, score, compressionRatio, window, now)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                    repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                    easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                    lastReviewAt = now)
            }
        }
    }

    // ── 모드 전환 시 전체 카드 재계산 ────────────────────────────────────────

    // 설정 변경 시 기존 카드들의 nextReviewAt을 새 ratio·window 기준으로 일괄 재배치
    // 구 윈도우 기준 progress를 보존하므로 카드가 갑자기 몰리거나 마감 후로 밀리지 않는다
    private suspend fun recalculateAllCardsInternal(
        deckId: Long,
        oldConfig: CompletionModeConfigEntity?,  // null이면 최초 활성화 → 구 윈도우를 전일(0~24)로 간주
        newConfig: CompletionModeConfigEntity,
        now: Long
    ) {
        val oldWindow = oldConfig?.toAllowedWindow() ?: AllowedWindow()  // 구 윈도우 (없으면 전일 기본값)
        val newWindow = newConfig.toAllowedWindow()                       // 새 윈도우
        val maxBase   = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
        val newRatio  = calculateCompressionRatio(newConfig.targetPeriodMs, maxBase, newWindow)

        val cards = db.completionCardDao().getAllStudiedCards(deckId)  // LEARNING + REVIEW 전체
        cards.forEach { card ->
            // 구 progress를 유지하면서 새 윈도우·비율 기준으로 nextReviewAt 재계산
            val newNextReviewAt = recalculateNextReviewAt(card, oldWindow, newWindow, newRatio, now)
            db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
        }
        compressionRatio = newRatio  // 인메모리 비율을 새 값으로 교체 → 이후 채점에 즉시 반영
    }
}
