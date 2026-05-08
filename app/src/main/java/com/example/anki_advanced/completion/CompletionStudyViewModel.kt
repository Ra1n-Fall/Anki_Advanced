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

// [StudyViewModel 동일]
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class CompletionStudyViewModel(application: Application) : AndroidViewModel(application) {

    // [StudyViewModel 동일] addMigrations만 추가
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()

    // ── State ─────────────────────────────────────────────────────────────────

    // [StudyViewModel 동일]
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    // [StudyViewModel 동일]
    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    // [StudyViewModel 동일]
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    // [StudyViewModel 동일]
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // StudyViewModel의 _progress(오늘 done/total)를 아래 세 Flow로 대체한다.
    //
    // _sessionInfo  : 마감일까지 남은 기간을 sessionIntervalMs로 나눠 계산한 세션 목표.
    //                 cardsPerSession = ceil(remainingCards / requiredSessions)
    //                 채점할 때마다 갱신되므로 분모로 직접 쓰면 값이 흔들린다.
    //
    // _sessionDone  : 현재 세션에서 완료한 총 인터랙션 수.
    //                 applyGrade()마다 +1, undoLast()마다 -1, startStudy()에서 0으로 초기화.
    //
    // _sessionTarget: 세션 시작 시 확정된 고정 목표.
    //                 = countDueLearning + countDueReview + cardsPerSession
    //                 프로그레스 바 분모로 사용. 세션 중 변하지 않는다.
    private val _sessionInfo   = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    private val _sessionDone   = MutableStateFlow(0)
    val sessionDone: StateFlow<Int> = _sessionDone.asStateFlow()

    private val _sessionTarget = MutableStateFlow(1)
    val sessionTarget: StateFlow<Int> = _sessionTarget.asStateFlow()

    // 완주 모드 설정(목표 기간, 허용 윈도우, 마감 시각 등)을 UI에 노출.
    // CompletionStudyScreen에서 D-Day 배지를 그릴 때 config.modeEndAt을 참조한다.
    // null이면 완주 모드 미활성 상태.
    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    // [StudyViewModel 동일]
    private val undoStack = ArrayDeque<UndoEntry>()
    private var deckId = -1L

    // 목표 기간 / 덱 최대 baseInterval 비율.
    // applySm2()와 getCompressedSteps()에 전달되어 복습 간격 전체를 이 비율로 압축/확장한다.
    // ratio < 1.0 → 간격 압축(더 자주 복습), ratio > 1.0 → 간격 확장(덜 자주 복습).
    private var compressionRatio = 1.0

    // compressionRatio에서 자동 산출되는 세션 간격.
    // = 86,400,000ms × ratio, 최소 30분 ~ 최대 1일로 고정.
    // ratio=1.0 → 1일 1세션, ratio=0.5 → 12시간 2세션, ratio=0.1 → 약 2.4시간 10세션.
    private var sessionIntervalMs = 86_400_000L

    // 학습이 허용된 시간대. modeConfig.toAllowedWindow()로 초기화된다.
    // 이 윈도우 밖 시각(예: 새벽 3시)은 간격 계산에서 제외된다.
    // 기본값 AllowedWindow(0, 24) = 하루 전체 허용.
    private var window = AllowedWindow()

    // 마지막 세션 완료 시각(ms). 0이면 아직 세션 이력 없음.
    // calculateSession()에서 (now - lastSessionTime) / sessionIntervalMs 로 누락 세션 수를 계산하고,
    // 그만큼 requiredSessions를 줄여 cardsPerSession을 늘린다(밀린 분량 보충).
    private var lastSessionTime = 0L

    // ── 공개 API ──────────────────────────────────────────────────────────────

    // [StudyViewModel 차이] 기존은 loadNextCard() 직접 호출.
    // 완주 모드는 아래 준비 단계를 먼저 수행한 뒤 loadNextCard()를 호출한다:
    //   1) DB에서 모드 설정 로드 → window, compressionRatio, sessionIntervalMs 확정
    //   2) refreshSessionInfo() → cardsPerSession 초기화
    //   3) due 카드 수 집계 → sessionTarget 고정 (세션 중 분모가 흔들리지 않도록)
    fun startStudy(deckId: Long) {
        this.deckId = deckId
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                db.completionModeDao().getConfig(deckId)   // 완주 모드 설정 조회
            }
            _modeConfig.value = config
            if (config != null) {
                window = config.toAllowedWindow()           // 허용 시간대 객체 생성
                compressionRatio = withContext(Dispatchers.IO) {
                    val maxBase = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
                    // maxBase=0이면 REVIEW 카드 없음 → ratio=1.0(압축 없음)
                    calculateCompressionRatio(config.targetPeriodMs, maxBase, window)
                }
                sessionIntervalMs = deriveSessionInterval(compressionRatio)  // ratio로 세션 간격 자동 계산
            }
            _sessionDone.value = 0      // 세션 인터랙션 카운터 초기화
            refreshSessionInfo()        // cardsPerSession 첫 계산
            _sessionTarget.value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val dueLearning    = db.completionCardDao().countDueLearning(deckId, now)   // 이미 due인 LEARNING
                val dueReview      = db.completionCardDao().countDueReview(deckId, now)     // 이미 due인 REVIEW
                val newThisSession = _sessionInfo.value.cardsPerSession                     // 이번 세션 NEW 목표
                // 세션 목표 = 지금 당장 봐야 할 카드 + 이번 세션에 새로 볼 NEW 카드
                // LEARNING이 세션 중 반복 등장하더라도 분모는 이 값으로 고정된다.
                (dueLearning + dueReview + newThisSession).coerceAtLeast(1)
            }
            loadNextCard()
        }
    }

    // [StudyViewModel 동일]
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    // [StudyViewModel 차이] DB 저장 쿼리와 세션 카운터 갱신 부분이 다르다.
    fun applyGrade(score: Int) {
        val card = _currentCard.value ?: return
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))  // [동일] undo 스냅샷 push
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val updatedCard = resolveUpdatedCard(card, score, now)  // [동일] 새 카드 상태 순수 계산

                var logId = -1L
                withContext(Dispatchers.IO) {
                    // [차이] 기존: updateSm2() + updateLearningStep() 두 쿼리
                    // 완주: updateCompletion() 단일 쿼리 — baseInterval(ms)·lastReviewAt 필드 추가 저장
                    db.completionCardDao().updateCompletion(
                        id           = card.id,
                        state        = score,
                        status       = updatedCard.status,
                        repetition   = updatedCard.repetition,
                        baseInterval = updatedCard.baseInterval,  // ms 단위 간격; compressionRatio 반영됨
                        easeFactor   = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt,
                        lastReviewAt = now,                       // 진행도 계산(calculateProgress)에 사용
                        learningStep = updatedCard.learningStep
                    )
                    // [동일] 리뷰 로그 삽입, 반환값이 PK
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

                // [동일] insert 후 logId 확정 → undo 엔트리 교체
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                // [차이] 기존: loadNextCard()만 호출
                // 완주: 세션 인터랙션 수 증가 → sessionInfo 갱신 → 다음 카드
                _sessionDone.value += 1
                refreshSessionInfo()
                loadNextCard()
            } finally {
                _isLoading.value = false  // [동일] 예외 여부 무관하게 로딩 해제
            }
        }
    }

    // [StudyViewModel 차이] refreshProgress() 대신 refreshSessionInfo() + _sessionDone 감소
    fun undoLast() {
        if (undoStack.isEmpty()) return         // [동일]
        val undo = undoStack.removeLast()       // [동일]
        _undoStackSize.value = undoStack.size   // [동일]
        _isLoading.value = true                 // [동일]

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)  // [동일] 로그 삭제
                    }
                    db.cardDao().update(undo.prevCard)  // [동일] 카드 복원
                }
                _sessionDone.value = (_sessionDone.value - 1).coerceAtLeast(0)  // 세션 인터랙션 수 되돌리기
                refreshSessionInfo()                    // 남은 카드 수 변화 반영
                _currentCard.value = undo.prevCard      // [동일]
                _uiState.value = StudyUiState.QUESTION  // [동일]
            } finally {
                _isLoading.value = false  // [동일]
            }
        }
    }

    // ── 모드 관리 (StudyViewModel에 없는 완주 모드 전용) ─────────────────────────

    // 완주 모드를 활성화하거나 목표 기간·허용 윈도우를 변경할 때 호출한다.
    // sessionIntervalMs는 compressionRatio에서 자동 산출하므로 파라미터로 받지 않는다.
    fun activateCompletionMode(
        deckId: Long,
        targetPeriodMs: Long,   // 완주 목표 기간 (ms)
        windowStartHour: Int,   // 학습 허용 시작 시각 (0~23)
        windowEndHour: Int      // 학습 허용 종료 시각 (1~24)
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newWindow = AllowedWindow(windowStartHour, windowEndHour)
            val maxBase = withContext(Dispatchers.IO) {
                db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
            }
            val newRatio = calculateCompressionRatio(targetPeriodMs, maxBase, newWindow)
            val newSessionIntervalMs = deriveSessionInterval(newRatio)  // ratio로 세션 간격 자동 결정

            val newConfig = CompletionModeConfigEntity(
                deckId            = deckId,
                targetPeriodMs    = targetPeriodMs,
                windowStartHour   = windowStartHour,
                windowEndHour     = windowEndHour,
                modeStartAt       = now,                    // 모드 시작 시각 기록
                sessionIntervalMs = newSessionIntervalMs,   // 자동 산출값 DB에 저장
                isActive          = true
            )
            withContext(Dispatchers.IO) {
                // 구 설정을 먼저 읽어야 progress 재계산 시 구 윈도우 기준 진행도를 보존할 수 있다
                val oldConfig = db.completionModeDao().getConfig(deckId)
                db.completionModeDao().upsert(newConfig)                                    // 없으면 insert, 있으면 replace
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)  // 기존 카드 nextReviewAt 일괄 갱신
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()            // 인메모리 윈도우 즉시 교체
            sessionIntervalMs = newSessionIntervalMs        // 인메모리 세션 간격 즉시 교체
        }
    }

    // 완주 모드를 종료한다.
    // 모드 기간 동안의 학습 이력(밀집도·정확도)을 반영해 각 카드의 baseInterval을 보정하고
    // isActive=false로 저장해 다음 진입 시 표준 모드로 동작하게 한다.
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val config = _modeConfig.value ?: return@launch  // 활성 설정 없으면 무시
            withContext(Dispatchers.IO) {
                // 모드 시작(modeStartAt) ~ 지금(now) 구간의 전체 리뷰 수
                val reviewCount  = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                // score별 카운트 조회 후 Good(2)·Easy(3)만 정답으로 집계
                val scores       = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
                val correctCount = scores.filter { it.score >= 2 }.sumOf { it.cnt }

                val studiedCards = db.completionCardDao().getAllStudiedCards(deckId)
                studiedCards.forEach { card ->
                    // 학습 밀집도(실제 리뷰 수 / 예상 리뷰 수)와 정확도로 baseInterval 보정
                    // 너무 집중적으로 학습했으면 간격을 늘리고, 정답률이 높으면 보너스 추가
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

    // [StudyViewModel 차이] due 판정 기준과 한도 체크가 다르다.
    //   기존 REVIEW: nextReviewAt <= todayStart, dailyReviewLimit 한도 있음
    //   완주 REVIEW: nextReviewAt <= now (ms 단위 절대 시각), 한도 없음
    //   기존 NEW   : dailyNewLimit 한도 있음
    //   완주 NEW   : 한도 없음 (cardsPerSession은 UI 표시용이지 로딩 제한이 아님)
    //   우선순위 1(LEARNING due)·4(LEARNING 대기 조기 등장)는 기존과 동일
    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()

                // 1순위: 시간이 된 LEARNING — 망각 전에 빠르게 재등장시켜야 단기 기억 강화
                val learning = db.completionCardDao().getNextDueLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                // 2순위: 시간이 된 REVIEW — ms 단위로 판정하므로 당일뿐 아니라 과거 누락분도 즉시 등장
                val review = db.completionCardDao().getNextDueReviewCard(deckId, now)
                if (review != null) return@withContext review

                // 3순위: NEW — 한도 없이 등장. 실제 노출 수는 sessionTarget으로 UI에서 안내
                val new = db.completionCardDao().getNextNewCard(deckId)
                if (new != null) return@withContext new

                // 4순위: 아직 시간이 안 된 LEARNING 조기 등장 — 1~3이 모두 없을 때 빈 화면 방지용
                db.completionCardDao().getNextPendingLearningCard(deckId)
            }

            if (next == null) {
                _uiState.value = StudyUiState.DONE   // 4순위까지 없으면 세션 완료
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION
            }
        }
    }

    // [StudyViewModel 차이] 기존 refreshProgress()는 오늘 done/total 카운트.
    // 이 함수는 마감일까지 남은 기간을 sessionIntervalMs로 나눠 세션당 목표 카드 수를 계산한다.
    // config가 null(완주 모드 미활성)이면 갱신하지 않는다.
    private suspend fun refreshSessionInfo() {
        val config = _modeConfig.value ?: return
        val now = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) {
            val totalCards     = db.completionCardDao().countAll(deckId)
            val studiedCards   = db.completionCardDao().countStudied(deckId)  // status != 0 카드 수
            val remainingCards = totalCards - studiedCards                     // 아직 NEW인 카드 수

            calculateSession(
                remainingCards    = remainingCards.coerceAtLeast(0),
                now               = now,
                modeEndMs         = config.modeEndAt,           // modeStartAt + targetPeriodMs
                sessionIntervalMs = sessionIntervalMs,          // ratio 기반 인메모리 값 사용 (DB 값 무시)
                lastSessionTime   = lastSessionTime,            // 누락 세션 계산용
                window            = window
            )
        }
        _sessionInfo.value = info
    }

    // ── 카드 상태 결정 ────────────────────────────────────────────────────────

    // [StudyViewModel 동일] 분기 구조 동일
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    // [StudyViewModel 차이]
    //   기존: 스텝이 고정 LEARNING_STEPS_MS([1분, 10분])
    //   완주: getCompressedSteps(compressionRatio)로 ratio에 비례해 스텝을 압축
    //         REVIEW 졸업 시 applySm2()에 compressionRatio·window 추가 전달
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)  // ratio 반영된 [압축 1분, 압축 10분] 스텝
        return when (score) {
            0 -> // Again: learningStep 0 초기화 → 첫 스텝 간격 후 재등장
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            1 -> { // Hard: 현재 스텝 유지 → 동일 간격 재시도
                val step = card.learningStep.coerceIn(0, steps.lastIndex)  // 범위 초과 방어
                card.copy(status = CARD_LEARNING, state = score, learningStep = step,
                    nextReviewAt = now + steps[step], lastReviewAt = now)
            }
            2 -> { // Good: 다음 스텝으로 진행, 마지막 스텝 초과 시 REVIEW 졸업
                val nextStep = card.learningStep + 1
                if (nextStep >= steps.size) {           // 모든 스텝 통과 → REVIEW 승격
                    val sm2 = applySm2(card, score, compressionRatio, window, now)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                        repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                        easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                        lastReviewAt = now)
                } else {                                // 아직 스텝 남음 → 다음 스텝으로
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

    // [StudyViewModel 차이]
    //   기존: Again 강등 시 고정 LEARNING_STEPS_MS[0] 사용
    //   완주: getCompressedSteps(compressionRatio)[0] 사용 → ratio에 따라 첫 스텝도 압축됨
    //         Hard·Good·Easy SM-2 호출 시 compressionRatio·window 추가 전달
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> { // Again: LEARNING으로 강등 → 압축된 첫 스텝 간격 후 재등장
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

    // ── 모드 전환 시 전체 카드 재계산 (StudyViewModel에 없음) ────────────────────

    // 완주 모드 설정이 바뀔 때(목표 기간·윈도우 변경) 기존 LEARNING/REVIEW 카드의
    // nextReviewAt을 새 ratio·window 기준으로 일괄 재배치한다.
    //
    // 단순히 새 ratio를 곱하면 일부 카드가 과거로 밀려 즉시 due가 되거나
    // 마감 이후로 밀릴 수 있다. recalculateNextReviewAt()은 구 윈도우 기준
    // 진행도(progress = elapsed / total)를 보존하면서 새 기준으로 재배치한다.
    private suspend fun recalculateAllCardsInternal(
        deckId: Long,
        oldConfig: CompletionModeConfigEntity?,  // null이면 최초 활성화 → 구 윈도우를 AllowedWindow()(전일)로 처리
        newConfig: CompletionModeConfigEntity,
        now: Long
    ) {
        val oldWindow = oldConfig?.toAllowedWindow() ?: AllowedWindow()
        val newWindow = newConfig.toAllowedWindow()
        val maxBase   = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
        val newRatio  = calculateCompressionRatio(newConfig.targetPeriodMs, maxBase, newWindow)

        val cards = db.completionCardDao().getAllStudiedCards(deckId)  // LEARNING + REVIEW 전체
        cards.forEach { card ->
            // 구 progress 보존하며 새 윈도우·비율 기준 nextReviewAt 재계산
            val newNextReviewAt = recalculateNextReviewAt(card, oldWindow, newWindow, newRatio, now)
            db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
        }
        compressionRatio  = newRatio                        // 인메모리 비율 교체 → 이후 채점에 즉시 반영
        sessionIntervalMs = deriveSessionInterval(newRatio) // 비율 변경에 따라 세션 간격도 재산출
    }

    // compressionRatio로부터 세션 간격을 자동 산출한다.
    // 공식: 86,400,000ms(1일) × ratio
    // ratio=1.0 → 86,400,000ms(1일 1세션)
    // ratio=0.5 → 43,200,000ms(12시간 → 하루 2세션)
    // ratio=0.1 →  8,640,000ms(약 2.4시간 → 하루 ~10세션)
    // coerceIn 하한 30분: 너무 잦은 세션 알림을 방지
    // coerceIn 상한 1일 : ratio > 1.0이어도 세션이 하루 1개를 넘지 않도록
    private fun deriveSessionInterval(ratio: Double): Long =
        (86_400_000L * ratio).toLong().coerceIn(30 * 60 * 1000L, 86_400_000L)
}
