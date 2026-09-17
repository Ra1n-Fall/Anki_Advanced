package com.example.anki_advanced.completion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

// 완주 모드(목표 기간 안에 덱을 끝내기) 학습 화면의 상태와 로직을 담당하는 ViewModel.
// 일반 모드의 StudyViewModel과 뼈대는 비슷하지만, "목표 기간에 맞춰 간격을 압축/확장한다"는
// 완주 모드 고유의 계산(compressionRatio, 세션 목표 등)이 추가되어 있다.

// ── 내부 타입 ─────────────────────────────────────────────────────────────────

// "되돌리기(Undo)" 한 건을 표현. 채점 전 카드 전체 상태 + 이번 채점으로 생긴 리뷰 로그 id를 담는다.
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class CompletionStudyViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체가 공유하는 AppDatabase 싱글턴 인스턴스.
    private val db = AppDatabase.getInstance(application)

    // ── State: 화면이 구독하는 값들 ──────────────────────────────────────────

    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 완주 모드는 "오늘 done/total" 대신 "이번 세션 done/target" 개념으로 진행률을 표시한다.
    // 세 값의 역할:
    //
    // _sessionInfo  : 마감일까지 남은 기간을 세션 간격(sessionIntervalMs)으로 나눠 계산한 세션 목표.
    //                 cardsPerSession = ceil(남은 카드 수 / 남은 세션 수).
    //                 채점할 때마다 다시 계산되므로, 프로그레스 바의 "분모"로 직접 쓰면
    //                 진행 중에 분모가 흔들려 보인다 — 그래서 분모는 아래 _sessionTarget을 따로 둔다.
    //
    // _sessionDone  : 이번 세션에서 지금까지 채점한 횟수. applyGrade()마다 +1, undoLast()마다 -1,
    //                 startStudy()가 호출될 때(새 세션 시작) 0으로 초기화.
    //
    // _sessionTarget: 세션이 시작될 때 딱 한 번 확정되는 "고정된 목표치".
    //                 = 이미 밀린 LEARNING 수 + 이미 밀린 REVIEW 수 + 이번 세션 NEW 목표.
    //                 프로그레스 바의 분모로 쓰이고, 세션 도중에는 값이 바뀌지 않는다.
    private val _sessionInfo   = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    private val _sessionDone   = MutableStateFlow(0)
    val sessionDone: StateFlow<Int> = _sessionDone.asStateFlow()

    private val _sessionTarget = MutableStateFlow(1)
    val sessionTarget: StateFlow<Int> = _sessionTarget.asStateFlow()

    // 완주 모드 설정(목표 기간, 허용 윈도우, 마감 시각 등)을 화면에 노출.
    // CompletionStudyScreen이 D-Day 배지를 그릴 때 config.modeEndAt을 참조한다.
    // null이면 이 덱에 완주 모드가 켜져 있지 않다는 뜻.
    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 (화면에 노출하지 않는, ViewModel 안에서만 쓰는 값들) ─────────

    private val undoStack = ArrayDeque<UndoEntry>()
    private var deckId = -1L

    // 목표 기간 대비 "자연스러운 학습 속도"의 비율. applySm2()와 getCompressedSteps()에 전달되어
    // 계산된 복습 간격 전체에 곱해진다.
    // ratio < 1.0 → 간격을 압축(더 자주 복습), ratio > 1.0 → 간격을 확장(덜 자주 복습).
    private var compressionRatio = 1.0

    // compressionRatio로부터 자동으로 정해지는 "세션 하나의 길이".
    // = 86,400,000ms(하루) × ratio, 다만 최소 30분 ~ 최대 1일 범위로 고정.
    // ratio=1.0 → 하루에 세션 1번, ratio=0.5 → 12시간마다 세션(하루 2번), ratio=0.1 → 약 2.4시간마다.
    private var sessionIntervalMs = 86_400_000L

    // 학습이 허용된 시간대. 완주 모드 설정을 불러오면 config.toAllowedWindow()로 채워진다.
    // 이 시간대 밖(예: 새벽 3시)은 "시간이 흐르지 않은 것"으로 쳐서 간격 계산에서 제외한다.
    // 기본값 AllowedWindow(0, 24) = 제한 없이 하루 전체 허용.
    private var window = AllowedWindow()

    // 마지막으로 세션을 완료한 시각(ms). 0이면 아직 세션을 한 번도 안 끝냈다는 뜻.
    // calculateSession()이 (지금 - 마지막 세션 시각) / 세션 간격 으로 "건너뛴 세션 수"를 계산해서,
    // 그만큼 남은 세션 수를 줄이고 세션당 카드 수를 늘려(밀린 분량을 보충) 준다.
    private var lastSessionTime = 0L

    // ── 공개 API (화면에서 호출하는 함수들) ────────────────────────────────────

    // 완주 모드 학습 세션을 시작한다. 일반 모드와 달리, 카드를 바로 불러오지 않고
    // 먼저 아래 준비 단계를 거친 뒤에 첫 카드를 로딩한다:
    //   1) DB에서 완주 모드 설정을 읽어와 window / compressionRatio / sessionIntervalMs를 확정
    //   2) refreshSessionInfo()로 이번 세션의 cardsPerSession을 계산
    //   3) 지금 당장 밀린(due) 카드 수를 집계해서 sessionTarget을 고정 (세션 중 분모가 안 흔들리게)
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
                    // maxBase가 0이면(REVIEW 카드가 아직 없음) 비교 기준이 없으므로 ratio=1.0(압축 없음)
                    calculateCompressionRatio(config.targetPeriodMs, maxBase, window)
                }
                sessionIntervalMs = deriveSessionInterval(compressionRatio)  // ratio로 세션 간격 자동 계산
            }
            _sessionDone.value = 0      // 새 세션이므로 인터랙션 카운터 초기화
            refreshSessionInfo()        // cardsPerSession 첫 계산
            _sessionTarget.value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val dueLearning    = db.completionCardDao().countDueLearning(deckId, now)   // 이미 due인 LEARNING
                val dueReview      = db.completionCardDao().countDueReview(deckId, now)     // 이미 due인 REVIEW
                val newThisSession = _sessionInfo.value.cardsPerSession                     // 이번 세션 NEW 목표
                // 세션 목표 = 지금 당장 봐야 할 카드 + 이번 세션에 새로 볼 NEW 카드.
                // LEARNING 카드가 세션 도중 반복해서 다시 나오더라도, 분모는 이 값으로 고정된다.
                (dueLearning + dueReview + newThisSession).coerceAtLeast(1)
            }
            loadNextCard()
        }
    }

    // "정답 보기" 버튼 콜백.
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    // 채점 버튼(다시/어려움/좋음/쉬움) 콜백.
    fun applyGrade(score: Int) {
        val card = _currentCard.value ?: return
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))  // 되돌리기용 스냅샷 먼저 push
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val updatedCard = resolveUpdatedCard(card, score, now)  // 새 카드 상태를 순수 계산으로 먼저 만듦

                var logId = -1L
                withContext(Dispatchers.IO) {
                    // 완주 모드 전용 쿼리로 한 번에 저장. baseInterval(ms 단위 원본 간격)과
                    // lastReviewAt(진행도 계산용)까지 같이 갱신한다는 점이 일반 모드와 다르다.
                    db.completionCardDao().updateCompletion(
                        id           = card.id,
                        state        = score,
                        status       = updatedCard.status,
                        repetition   = updatedCard.repetition,
                        baseInterval = updatedCard.baseInterval,  // ms 단위 간격; compressionRatio가 반영된 값
                        easeFactor   = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt,
                        lastReviewAt = now,                       // calculateProgress() 계산에 쓰임
                        learningStep = updatedCard.learningStep
                    )
                    // 채점 이력을 리뷰 로그 한 줄로 기록. 반환값은 새로 생긴 행의 id.
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

                // insert가 끝나 진짜 logId를 알게 됐으니, 스택 맨 위 항목을 꺼내 값을 채워 다시 넣는다.
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                // 완주 모드 전용: 세션 인터랙션 수를 늘리고, 세션 정보를 다시 계산한 뒤 다음 카드로.
                _sessionDone.value += 1
                refreshSessionInfo()
                loadNextCard()
            } finally {
                _isLoading.value = false  // 성공/실패 상관없이 로딩 상태는 반드시 해제
            }
        }
    }

    // "↩ 되돌리기" 버튼 콜백.
    fun undoLast() {
        if (undoStack.isEmpty()) return
        val undo = undoStack.removeLast()
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)  // 리뷰 로그 삭제
                    }
                    db.cardDao().update(undo.prevCard)  // 카드를 채점 전 상태로 복원
                }
                _sessionDone.value = (_sessionDone.value - 1).coerceAtLeast(0)  // 세션 인터랙션 수 되돌리기
                refreshSessionInfo()                    // 남은 카드 수 변화를 반영
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 완주 모드 자체를 켜고 끄는 함수들 ────────────────────────────────────

    // 완주 모드를 새로 활성화하거나, 이미 켜진 상태에서 목표 기간·허용 윈도우를 변경할 때 호출한다.
    // sessionIntervalMs는 compressionRatio로부터 자동 계산되므로 파라미터로 따로 받지 않는다.
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
                sessionIntervalMs = newSessionIntervalMs,   // 자동 산출값을 DB에도 저장
                isActive          = true
            )
            withContext(Dispatchers.IO) {
                // 옛 설정을 먼저 읽어둬야, 옛 윈도우 기준의 진행도를 보존하며 재계산할 수 있다.
                val oldConfig = db.completionModeDao().getConfig(deckId)
                db.completionModeDao().upsert(newConfig)                        // 없으면 insert, 있으면 덮어쓰기
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)  // 기존 카드들의 nextReviewAt 일괄 갱신
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()            // 메모리 상의 윈도우 값도 즉시 교체
            sessionIntervalMs = newSessionIntervalMs        // 메모리 상의 세션 간격도 즉시 교체
        }
    }

    // 완주 모드를 종료한다.
    // 모드가 켜져 있던 동안의 학습 이력(얼마나 밀도 있게, 얼마나 정확하게 공부했는지)을 반영해
    // 각 카드의 baseInterval을 보정하고, isActive=false로 저장해서 다음부터는 표준 모드처럼 동작하게 한다.
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // [문법] val config = _modeConfig.value ?: return@launch
            //   활성 설정이 없으면(이미 꺼져 있으면) 할 일이 없으니 이 코루틴만 조용히 끝낸다.
            //   return@launch는 "이 launch 블록만 종료"라는 뜻으로, 바깥 함수 전체를 끝내는 게 아니다.
            val config = _modeConfig.value ?: return@launch
            withContext(Dispatchers.IO) {
                // 모드 시작(modeStartAt)부터 지금(now)까지 전체 채점 횟수
                val reviewCount  = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                // 점수별 채점 횟수를 조회해서, Good(2)·Easy(3) 이상만 "정답"으로 집계
                val scores       = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
                val correctCount = scores.filter { it.score >= 2 }.sumOf { it.cnt }

                val studiedCards = db.completionCardDao().getAllStudiedCards(deckId)
                studiedCards.forEach { card ->
                    // 학습 밀도(실제 채점 수 / 예상 채점 수)와 정답률을 반영해 baseInterval을 보정.
                    // 너무 집중적으로(단기간에 몰아서) 공부했으면 간격을 늘리고, 정답률이 높으면 보너스를 준다.
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

    // 다음에 보여줄 카드를 우선순위대로 고른다.
    // 일반 모드와 다른 점: REVIEW의 "복습할 때 됐는지" 판정이 "오늘 자정 기준"이 아니라
    // "지금 이 순간(now)" 기준이고, NEW/REVIEW 모두 하루 한도 같은 제한이 없다
    // (완주 모드는 "오늘 몇 장까지만" 대신 "목표 기간까지 얼마나 남았는지"로 페이스를 조절하기 때문).
    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()

                // 1순위: 재노출 시각이 된 LEARNING — 망각되기 전에 빠르게 다시 보여줘야 단기 기억이 굳는다.
                val learning = db.completionCardDao().getNextDueLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                // 2순위: 재노출 시각이 된 REVIEW — ms 단위로 판정하므로, 오늘 것뿐 아니라
                // 과거에 밀려서 못 본 카드도 바로 등장한다.
                val review = db.completionCardDao().getNextDueReviewCard(deckId, now)
                if (review != null) return@withContext review

                // 3순위: NEW — 한도 없이 계속 등장한다. 실제로 "몇 장이나 볼지"는 sessionTarget으로
                // 화면에 안내만 할 뿐, 여기서 로딩 자체를 막지는 않는다.
                val new = db.completionCardDao().getNextNewCard(deckId)
                if (new != null) return@withContext new

                // 4순위: 아직 시간은 안 됐지만 대기 중인 LEARNING을 조기 등장시킨다.
                // 1~3순위가 전부 바닥났을 때 화면이 텅 비어버리는 걸 막기 위한 안전장치.
                db.completionCardDao().getNextPendingLearningCard(deckId)
            }

            if (next == null) {
                _uiState.value = StudyUiState.DONE   // 4순위까지 없으면 이번 세션은 여기서 끝
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION
            }
        }
    }

    // 마감일까지 남은 기간을 세션 간격으로 나눠 "이번 세션에 몇 장을 봐야 하는지" 다시 계산한다.
    // config가 null이면(완주 모드가 꺼져 있으면) 계산할 게 없으니 그냥 아무 것도 안 하고 끝낸다.
    private suspend fun refreshSessionInfo() {
        val config = _modeConfig.value ?: return
        val now = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) {
            val totalCards     = db.completionCardDao().countAll(deckId)
            val studiedCards   = db.completionCardDao().countStudied(deckId)  // status != 0(한 번이라도 본) 카드 수
            val remainingCards = totalCards - studiedCards                     // 아직 NEW로 남은 카드 수

            calculateSession(
                remainingCards    = remainingCards.coerceAtLeast(0),
                now               = now,
                modeEndMs         = config.modeEndAt,           // modeStartAt + targetPeriodMs
                sessionIntervalMs = sessionIntervalMs,          // DB 저장값이 아니라 ratio로 계산한 메모리 상의 값을 사용
                lastSessionTime   = lastSessionTime,            // 건너뛴 세션 계산용
                window            = window
            )
        }
        _sessionInfo.value = info
    }

    // ── 카드 상태 결정 (채점 후 카드가 어떻게 바뀌는지 계산) ────────────────────

    // 카드의 현재 상태(status)에 따라 LEARNING 처리와 REVIEW 처리로 나눠 위임.
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    // LEARNING(또는 NEW) 카드를 채점했을 때 다음 상태를 계산.
    // 일반 모드와 다른 점: 대기 시간이 고정된 [1분, 10분]이 아니라, compressionRatio에 비례해
    // 압축된 스텝(getCompressedSteps)을 쓴다. REVIEW로 졸업할 때도 applySm2()에
    // compressionRatio와 window를 추가로 넘겨서 완주 모드 기준으로 간격을 계산한다.
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        val steps = getCompressedSteps(compressionRatio)  // ratio가 반영된 [압축된 1분, 압축된 10분] 스텝
        return when (score) {
            0 -> // Again: learningStep을 0으로 리셋, 첫 스텝 간격 후 재등장
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            1 -> { // Hard: 현재 스텝 유지, 같은 간격으로 재등장
                val step = card.learningStep.coerceIn(0, steps.lastIndex)  // 범위를 벗어나지 않게 방어
                card.copy(status = CARD_LEARNING, state = score, learningStep = step,
                    nextReviewAt = now + steps[step], lastReviewAt = now)
            }
            2 -> { // Good: 다음 스텝으로 진행. 마지막 스텝을 넘어서면 REVIEW로 졸업
                val nextStep = card.learningStep + 1
                if (nextStep >= steps.size) {           // 모든 스텝을 통과했으므로 REVIEW로 승격
                    val sm2 = applySm2(card, score, compressionRatio, window, now)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                        repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                        easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                        lastReviewAt = now)
                } else {                                // 아직 스텝이 남아있으므로 다음 스텝으로 이동
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep,
                        nextReviewAt = now + steps[nextStep], lastReviewAt = now)
                }
            }
            else -> { // Easy: 스텝을 전부 건너뛰고 곧바로 REVIEW로 졸업
                val sm2 = applySm2(card, score, compressionRatio, window, now)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                    repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                    easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                    lastReviewAt = now)
            }
        }
    }

    // REVIEW 카드를 채점했을 때 다음 상태를 계산.
    // 일반 모드와 다른 점: Again으로 강등될 때도 고정 스텝이 아니라 압축된 첫 스텝을 쓰고,
    // Hard/Good/Easy의 SM-2 계산에도 compressionRatio와 window를 추가로 넘긴다.
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> { // Again: LEARNING으로 강등, 압축된 첫 스텝 간격 후 재등장
                val steps = getCompressedSteps(compressionRatio)
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0,
                    nextReviewAt = now + steps[0], lastReviewAt = now)
            }
            else -> { // Hard·Good·Easy: compressionRatio·window를 반영한 SM-2로 다음 복습 시각 계산
                val sm2 = applySm2(card, score, compressionRatio, window, now)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0,
                    repetition = sm2.repetition, baseInterval = sm2.baseInterval,
                    easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt,
                    lastReviewAt = now)
            }
        }
    }

    // ── 모드 설정이 바뀔 때 기존 카드 전체를 재계산 ─────────────────────────────

    // 완주 모드 설정이 바뀔 때(목표 기간이나 윈도우를 수정) 기존 LEARNING/REVIEW 카드들의
    // nextReviewAt을 새 비율·새 윈도우 기준으로 한꺼번에 다시 배치한다.
    //
    // 단순히 새 ratio를 곱해버리면, 어떤 카드는 갑자기 과거 시각이 되어 즉시 복습 대상이 되거나
    // 반대로 마감 이후로 밀려버릴 수 있다. recalculateNextReviewAt()은 "옛 윈도우 기준으로
    // 몇 % 진행됐는지(progress)"는 그대로 보존하면서, 시간축만 새 기준으로 바꿔치기한다.
    private suspend fun recalculateAllCardsInternal(
        deckId: Long,
        oldConfig: CompletionModeConfigEntity?,  // null이면 이번이 최초 활성화 → 옛 윈도우는 하루 전체 허용으로 간주
        newConfig: CompletionModeConfigEntity,
        now: Long
    ) {
        val oldWindow = oldConfig?.toAllowedWindow() ?: AllowedWindow()
        val newWindow = newConfig.toAllowedWindow()
        val maxBase   = db.completionCardDao().getMaxBaseInterval(deckId) ?: 0L
        val newRatio  = calculateCompressionRatio(newConfig.targetPeriodMs, maxBase, newWindow)

        val cards = db.completionCardDao().getAllStudiedCards(deckId)  // LEARNING + REVIEW 카드 전체
        cards.forEach { card ->
            // 옛 진행도를 보존하면서 새 윈도우·새 비율 기준으로 nextReviewAt을 재계산
            val newNextReviewAt = recalculateNextReviewAt(card, oldWindow, newWindow, newRatio, now)
            db.completionCardDao().updateNextReviewAt(card.id, newNextReviewAt)
        }
        compressionRatio  = newRatio                        // 메모리 상의 비율을 교체 → 이후 채점에 바로 반영
        sessionIntervalMs = deriveSessionInterval(newRatio) // 비율이 바뀌었으니 세션 간격도 다시 산출
    }

    // compressionRatio로부터 세션 하나의 길이(ms)를 자동으로 산출한다.
    // 공식: 86,400,000ms(하루) × ratio
    //   ratio=1.0 → 86,400,000ms (하루에 세션 1번)
    //   ratio=0.5 → 43,200,000ms (12시간마다, 하루 2번)
    //   ratio=0.1 →  8,640,000ms (약 2.4시간마다, 하루 약 10번)
    // 하한을 30분으로 둔 이유: 세션 알림이 너무 잦아지지 않도록.
    // 상한을 1일로 둔 이유: ratio가 1.0보다 커도(기간에 여유가 있어도) 세션이 하루에 한 번을 넘지 않게.
    private fun deriveSessionInterval(ratio: Double): Long =
        (86_400_000L * ratio).toLong().coerceIn(30 * 60 * 1000L, 86_400_000L)
}
