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

/**
 * 되돌리기(undo) 1회분 스냅샷.
 *
 * [prevCard]          : 채점 직전의 카드 상태. undo 시 이 값으로 DB를 덮어쓴다.
 * [insertedReviewLogId]: 채점 시 삽입된 ReviewLog의 PK.
 *                        null이면 아직 로그가 삽입되지 않은 상태(코루틴 진행 중)이며,
 *                        undo 시 삭제할 로그가 없음을 의미한다.
 *
 * 왜 두 값을 묶는가:
 *   채점은 카드 업데이트와 로그 삽입이 순차적으로 일어나는 비동기 작업이다.
 *   undo가 그 사이에 호출되더라도 카드·로그를 동시에 롤백할 수 있어야 하므로
 *   하나의 엔트리로 묶어 원자적 복원을 보장한다.
 */
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * 기간 완주형 SRS 학습 ViewModel.
 *
 * ## 표준 StudyViewModel과의 핵심 차이
 *
 * | 항목              | 표준 모드                    | 완주 모드                              |
 * |-------------------|------------------------------|----------------------------------------|
 * | due 판정 기준     | nextReviewAt <= todayStart   | nextReviewAt <= now (ms 단위)          |
 * | 간격 계산         | 표준 SM-2 (일 단위)          | compressionRatio·window 반영 SM-2     |
 * | 저장 필드         | intervalDays 위주            | baseInterval(ms) + lastReviewAt 추가  |
 * | 세션 개념         | 없음                         | sessionInfo (목표 카드 수/세션 수)     |
 *
 * ## 주요 흐름
 * ```
 * startStudy()
 *   └─ 모드 설정 로드 → compressionRatio 계산 → loadNextCard()
 *
 * applyGrade(score)
 *   └─ undo 스냅샷 저장 → 카드 상태 재계산 → DB 저장 + 로그 삽입
 *      → undo 엔트리에 logId 반영 → sessionInfo 갱신 → loadNextCard()
 *
 * undoLast()
 *   └─ 로그 삭제 → 카드 DB 복원 → sessionInfo 갱신 → UI 복원
 *
 * activateCompletionMode()
 *   └─ 설정 저장 → 기존 REVIEW 카드 nextReviewAt 일괄 재계산
 *
 * deactivateCompletionMode()
 *   └─ 학습 밀집도·정확도 집계 → 각 카드 baseInterval 보정 → isActive=false
 * ```
 */
class CompletionStudyViewModel(application: Application) : AndroidViewModel(application) {

    // ViewModel이 살아있는 동안 단일 DB 인스턴스를 유지한다.
    // fallbackToDestructiveMigration: 마이그레이션 누락 시 데이터 손실보다 앱 정상 동작을 우선.
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).addMigrations(
        com.example.anki_advanced.MIGRATION_1_2
    ).fallbackToDestructiveMigration().build()

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * 현재 화면 상태.
     * - QUESTION : 앞면만 보이는 상태. 사용자가 "정답 보기"를 누르기 전.
     * - ANSWER   : 뒷면이 펼쳐진 상태. Again/Hard/Good/Easy 버튼 활성.
     * - DONE     : 덱에 남은 카드가 없어 세션이 종료된 상태.
     */
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    /**
     * 현재 화면에 표시 중인 카드.
     * null → loadNextCard()가 카드를 찾지 못한 경우이며, DONE 상태로 전환된다.
     */
    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    /**
     * 현재 undo 가능한 횟수 (= undoStack.size).
     * UI는 이 값이 0이면 되돌리기 버튼을 비활성화한다.
     * 스택 크기 자체를 노출하지 않고 Int를 노출하는 이유는
     * 스택 내부 구조(UndoEntry)를 UI 레이어에 숨기기 위함이다.
     */
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    /**
     * DB 비동기 작업 진행 여부.
     * true인 동안은 채점 버튼·undo 버튼을 비활성화해 중복 입력을 방지한다.
     * try/finally 블록으로 예외 발생 시에도 반드시 false로 복귀한다.
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 현재 세션의 목표 정보.
     * - cardsPerSession : 이번 세션에 학습해야 할 목표 카드 수.
     * - requiredSessions: 마감일까지 남은 세션 수.
     * 채점할 때마다 refreshSessionInfo()가 재계산해 갱신한다.
     */
    private val _sessionInfo = MutableStateFlow(SessionInfo(0, 0))
    val sessionInfo: StateFlow<SessionInfo> = _sessionInfo.asStateFlow()

    /**
     * 현재 덱의 완주 모드 설정.
     * null이면 완주 모드가 활성화되지 않은 상태.
     * activateCompletionMode() 호출 시 업데이트, deactivateCompletionMode() 시 isActive=false로 변경.
     */
    private val _modeConfig = MutableStateFlow<CompletionModeConfigEntity?>(null)
    val modeConfig: StateFlow<CompletionModeConfigEntity?> = _modeConfig.asStateFlow()

    // ── 내부 상태 ─────────────────────────────────────────────────────────────

    /**
     * 되돌리기 스택. 채점할 때마다 UndoEntry를 addLast하고, undo 시 removeLast한다.
     * 스택 깊이 제한은 없지만 실질적으로 앱 생명주기 안에서만 유지된다.
     * (프로세스 종료 후 재진입 시 스택은 초기화됨)
     */
    private val undoStack = ArrayDeque<UndoEntry>()

    // 현재 학습 중인 덱의 ID. startStudy() 호출 전까지는 -1L.
    private var deckId = -1L

    /**
     * 간격 압축 비율. calculateCompressionRatio()가 반환한 값.
     *
     * 계산식: targetPeriodWindowTime / maxBaseIntervalWindowTime
     *
     * 예)
     *   목표 기간 30일(윈도우 9~22), 덱 최대 복습간격 60일 →
     *   ratio ≈ 0.5 → SM-2 간격이 절반으로 압축되어 30일 안에 완주 가능해짐.
     *
     *   목표 기간 90일, 최대 복습간격 60일 →
     *   ratio ≈ 1.5 → 간격이 늘어나 복습 빈도가 줄어듦.
     */
    private var compressionRatio = 1.0

    /**
     * 학습이 허용된 시간대. modeConfig.toAllowedWindow()로 초기화된다.
     * 기본값 AllowedWindow(0, 24)는 하루 전체를 허용한다.
     *
     * 윈도우 밖 시간(예: 새벽 3시, 윈도우가 9~22시인 경우)에는
     * 시간이 흐르지 않는 것으로 간주하므로,
     * nextReviewAt 계산 시 실제 시계 시간과 달라질 수 있다.
     */
    private var window = AllowedWindow()

    /**
     * 마지막 세션 완료 시각(ms).
     * 0이면 아직 세션을 한 번도 완료하지 않은 상태.
     * calculateSession()에서 세션 누락 횟수를 계산할 때 사용된다.
     * (누락된 세션만큼 requiredSessions를 줄여 카드 수를 늘림)
     */
    private var lastSessionTime = 0L

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 학습 화면 진입 시 단 한 번 호출. LaunchedEffect(deckId)로 트리거된다.
     *
     * 1. DB에서 이 덱의 완주 모드 설정(CompletionModeConfigEntity)을 읽어온다.
     *    설정이 없으면 compressionRatio=1.0, window=전일 기본값으로 동작한다.
     *
     * 2. getMaxBaseInterval()로 현재 덱에서 가장 긴 복습 간격을 구한 뒤
     *    calculateCompressionRatio()로 압축 비율을 결정한다.
     *    (이 비율은 모드 종료 전까지 고정값으로 사용됨)
     *
     * 3. loadNextCard()로 첫 번째 카드를 화면에 올린다.
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

    /**
     * 앞면 → 뒷면 전환.
     * DB 작업 없이 _uiState만 ANSWER로 바꾼다.
     * UI에서 카드를 flip하는 애니메이션은 Composable이 isFlipped 상태를 따로 관리한다.
     */
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    /**
     * 사용자가 채점 버튼(Again/Hard/Good/Easy)을 눌렀을 때 호출.
     *
     * @param score 0=Again, 1=Hard, 2=Good, 3=Easy
     *
     * ### 처리 순서
     * 1. **undo 스냅샷 push** : 채점 전 카드 상태를 undoStack에 저장한다.
     *    이 시점에서 insertedReviewLogId는 null이다(아직 삽입 전).
     *
     * 2. **카드 상태 재계산** : resolveUpdatedCard()로 새 status/nextReviewAt 등을 결정.
     *    DB 호출 없이 순수 계산만 수행한다.
     *
     * 3. **DB 저장** : updateCompletion()으로 카드를 갱신하고
     *    ReviewLog를 삽입한다. 두 작업은 같은 withContext(IO) 블록에서 순차 실행된다.
     *    (트랜잭션 처리는 Room이 각 쿼리 단위로 보장)
     *
     * 4. **undo 엔트리 갱신** : 삽입된 logId를 undo 엔트리에 복사한다.
     *    removeLast → copy → addLast 패턴을 쓰는 이유:
     *    data class는 불변이므로 in-place 수정이 불가하고,
     *    insert 전에 logId를 알 수 없으므로 insert 후에 덮어써야 한다.
     *
     * 5. **세션 갱신 & 다음 카드** : sessionInfo를 재계산하고 다음 카드를 로드한다.
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
                            // 이번 채점이 이 카드의 첫 학습인지 기록 (통계용)
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                        )
                    )
                }

                // logId 확정 후 undo 엔트리를 교체한다.
                // insert 성공 전에는 null이므로 반드시 insert 이후에 반영해야 한다.
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                refreshSessionInfo()
                loadNextCard()
            } finally {
                // 예외 발생 여부와 무관하게 로딩 상태를 해제한다.
                _isLoading.value = false
            }
        }
    }

    /**
     * 직전 채점을 취소하고 이전 상태로 완전히 복원한다.
     *
     * ### 처리 순서
     * 1. undoStack에서 마지막 엔트리를 꺼낸다.
     *    스택이 비어있으면 아무 것도 하지 않는다.
     *
     * 2. 삽입된 ReviewLog를 삭제한다.
     *    insertedReviewLogId가 null이면 로그 삽입 전에 undo가 호출된 것으로,
     *    로그 삭제를 건너뛰고 카드 복원만 수행한다.
     *
     * 3. prevCard 값으로 DB의 카드 행을 덮어쓴다.
     *    표준 CardDao.update()를 사용하므로 모든 필드가 채점 전 값으로 돌아간다.
     *
     * 4. _currentCard를 되돌린 카드로 교체하고 QUESTION 상태로 전환한다.
     *    사용자는 카드 앞면을 다시 보게 된다.
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
     * 완주 모드를 활성화하거나 기간/윈도우 설정을 변경할 때 호출한다.
     *
     * @param targetPeriodMs    학습 목표 기간 (예: 30일 = 30 × 86_400_000L ms)
     * @param windowStartHour   하루 중 학습 시작 시각 (0~23)
     * @param windowEndHour     하루 중 학습 종료 시각 (1~24)
     * @param sessionIntervalMs 세션 간격. 기본 86_400_000L = 1일
     *
     * ### 기존 카드 재배치가 필요한 이유
     * 설정이 바뀌면 compressionRatio와 window가 달라진다.
     * 이미 학습된 REVIEW 카드의 nextReviewAt이 구 기준으로 계산된 채로 남아있으면
     * 카드들이 예상치 못한 시점에 몰려 나오거나 아예 마감 이후로 밀릴 수 있다.
     * recalculateAllCardsInternal()은 구 윈도우 기준 progress를 보존하면서
     * 새 설정 기준으로 nextReviewAt을 재배치한다.
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
                // 설정 변경 전 구 윈도우를 미리 읽어두어야 progress 재계산에 사용할 수 있다.
                val oldConfig = db.completionModeDao().getConfig(deckId)
                db.completionModeDao().upsert(newConfig)
                recalculateAllCardsInternal(deckId, oldConfig, newConfig, now)
            }
            _modeConfig.value = newConfig
            window = newConfig.toAllowedWindow()
        }
    }

    /**
     * 완주 모드를 종료할 때 호출한다.
     * 모드 기간 동안의 학습 이력을 바탕으로 각 카드의 baseInterval을 보정하고,
     * isActive=false로 설정해 이후 진입 시 표준 모드로 동작하게 한다.
     *
     * ### 보정 로직 (applyModeReflection)
     * 완주 모드에서는 간격이 압축되어 있으므로, 모드 종료 후 그대로 두면
     * 복습이 너무 잦아진다. applyModeReflection()은 아래 두 지표로 보정한다.
     *
     * - **density** (밀집도): 실제 리뷰 수 / 예상 리뷰 수
     *   → 너무 집중해서 학습했으면 간격을 늘린다.
     * - **accuracy** (정확도): 정답 수 / 전체 리뷰 수
     *   → 정답률이 높을수록 보너스 간격을 추가한다.
     */
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val config = _modeConfig.value ?: return@launch
            withContext(Dispatchers.IO) {
                // 모드 시작(modeStartAt) ~ 현재(now) 구간의 리뷰 통계 집계
                val reviewCount  = db.reviewLogDao().countToday(deckId, config.modeStartAt, now)
                val scores       = db.reviewLogDao().countByScoreToday(deckId, config.modeStartAt, now)
                // score >= 2(Good, Easy)를 정답으로 간주
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
     * 다음에 표시할 카드를 결정해 _currentCard에 설정한다.
     *
     * ### 카드 선택 우선순위
     *
     * 1. **시간 된 LEARNING** (`status=1, nextReviewAt <= now`)
     *    단기 기억은 시간이 지나면 빠르게 망각되므로 최우선으로 처리한다.
     *    예) 10분 뒤 다시 보기로 예약된 카드가 시간이 됐다면 즉시 등장.
     *
     * 2. **시간 된 REVIEW** (`status=2, nextReviewAt <= now`)
     *    장기 기억 복습 카드. 표준 모드와 달리 ms 단위 절대 시각 기준이다.
     *
     * 3. **NEW** (`status=0`)
     *    1·2번이 없을 때 새 카드를 학습한다. id 순으로 순차 등장.
     *
     * 4. **대기 중인 LEARNING** (nextReviewAt > now인 LEARNING)
     *    1~3번이 전부 없는 경우에만 아직 시간이 안 된 LEARNING 카드를 조기 등장시킨다.
     *    빈 화면 대신 학습을 이어갈 수 있도록 하는 폴백(fallback)이다.
     *
     * 4번까지 없으면 _uiState를 DONE으로 전환한다.
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

    /**
     * 세션 정보를 재계산해 _sessionInfo를 갱신한다.
     * 채점(applyGrade)과 되돌리기(undoLast) 직후에 호출된다.
     *
     * ### 계산 흐름
     * 1. 전체 카드 수에서 학습 완료(status != 0) 카드를 빼 남은 카드 수를 구한다.
     * 2. calculateSession()에 남은 카드 수·모드 마감 시각·세션 간격을 넘겨
     *    세션당 목표 카드 수(cardsPerSession)와 남은 세션 수(requiredSessions)를 얻는다.
     * 3. lastSessionTime이 설정돼 있으면 누락된 세션만큼 requiredSessions를 줄이고
     *    그만큼 cardsPerSession이 늘어난다 (밀린 분량을 지금 더 소화).
     */
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

    /**
     * 현재 카드의 status에 따라 채점 처리를 분기한다.
     *
     * - NEW / LEARNING → resolveUpdatedLearningCard() : 스텝 기반 단기 반복 처리
     * - REVIEW         → resolveUpdatedReviewCard()   : SM-2 기반 장기 간격 재계산
     */
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW             -> resolveUpdatedReviewCard(card, score, now)
            else                    -> card
        }
    }

    /**
     * NEW / LEARNING 카드의 채점을 처리한다.
     *
     * compressionRatio가 반영된 스텝 목록(getCompressedSteps)을 사용한다.
     * 예) ratio=0.5, 기본 스텝=[1분, 10분] → 압축 스텝=[30초, 5분]
     *
     * ### 채점별 동작
     *
     * **Again (0)** — 처음부터 다시
     *   learningStep을 0으로 초기화하고 steps[0] 후에 다시 등장시킨다.
     *
     * **Hard (1)** — 현재 스텝 재시도
     *   learningStep을 유지한 채로 동일한 간격 후에 다시 등장시킨다.
     *   (이미 알고 있지만 자신 없는 카드)
     *
     * **Good (2)** — 다음 스텝 진행 or REVIEW 졸업
     *   learningStep + 1이 스텝 목록 범위를 벗어나면 REVIEW로 승격한다.
     *   승격 시 SM-2(applySm2)로 첫 복습 간격을 계산한다.
     *
     * **Easy (3)** — 즉시 REVIEW 졸업
     *   남은 스텝을 건너뛰고 바로 REVIEW로 승격한다. SM-2 적용.
     *   (이미 완전히 알고 있는 카드)
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
                    // 마지막 스텝을 통과 → REVIEW 승격
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
     * REVIEW 카드의 채점을 처리한다.
     *
     * ### 채점별 동작
     *
     * **Again (0)** — LEARNING 강등
     *   기억에서 완전히 사라진 카드. LEARNING으로 내리고 스텝 0부터 재학습한다.
     *   baseInterval은 초기화되지 않고 카드에 남아있어 이후 모드 보정 계산에 활용된다.
     *
     * **Hard / Good / Easy (1~3)** — SM-2 재계산
     *   applySm2()에 compressionRatio와 window를 전달해
     *   목표 기간에 맞게 압축된 nextReviewAt을 계산한다.
     *   결과: baseInterval(jitter 포함 ms), easeFactor, nextReviewAt 갱신.
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
     * 완주 모드 설정이 변경될 때 기존 LEARNING/REVIEW 카드의 nextReviewAt을 일괄 갱신한다.
     * activateCompletionMode() 내부에서만 호출된다.
     *
     * ### 재계산 전략 (progress 보존)
     * 단순히 새 ratio를 곱해 nextReviewAt을 덮어쓰면
     * 일부 카드는 과거로 밀려 즉시 due 상태가 되고 일부는 마감 후로 밀릴 수 있다.
     *
     * recalculateNextReviewAt()은 아래 방식으로 이를 방지한다:
     *   1. 구 윈도우 기준으로 이 카드의 현재 진행도(progress = elapsed / total)를 계산.
     *   2. 새 effectiveInterval = baseInterval × newRatio.
     *   3. 새 윈도우 기준 totalWindowTime × progress → elapsedNewWindowTime.
     *   4. nextReviewAt = addWindowTime(lastReviewAt, elapsedNewWindowTime, newWindow).
     *
     * 결과적으로 "전체 구간 중 얼마나 왔는가"라는 상대적 위치는 유지된다.
     *
     * @param oldConfig null이면 최초 활성화로 간주, 구 윈도우를 AllowedWindow() (전일)로 처리.
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
        // 인메모리 비율도 새 값으로 교체해 이후 채점에 즉시 반영한다.
        compressionRatio = newRatio
    }
}
