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

// [유지] UndoEntry 구조 동일 — prevCard + insertedReviewLogId
// 기존: StudyActivity 내부에 선언 (큐 스냅샷 제거 후 이 두 필드만 필요)
// 변경: ViewModel 파일 수준으로 이동
private data class UndoEntry(
    val prevCard: CardEntity,        // 채점 전 카드 상태 (status, learningStep, nextReviewAt 등)
    val insertedReviewLogId: Long?   // 실행 취소할 카드의 로그 DB 속 아이디
)

// [유지] LEARNING 단계 시간 동일
// step 0 → 1분 후, step 1 → 10분 후
private val LEARNING_STEPS_MS = listOf(
    1 * 60 * 1000L,
    10 * 60 * 1000L
)

// [변경] StudyActivity → StudyViewModel
// 기존: AppCompatActivity 상속, lifecycleScope + binding으로 UI 직접 제어
// 변경: AndroidViewModel 상속, StateFlow로 상태 노출 → StudyScreen이 구독
// 이유: UI 로직(Compose)과 비즈니스 로직 분리, Activity 재생성 시 상태 유지
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    // [유지] DB 인스턴스 생성 방식 동일
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    // [유지] undoStack 구조 동일
    private val undoStack = ArrayDeque<UndoEntry>()

    // [변경] Activity 멤버 변수 → StateFlow
    // 기존: StudyActivity의 currentCard, undoStack 등 일반 멤버 변수
    // 변경: StateFlow로 선언 → StudyScreen이 collectAsState()로 구독
    //       값이 바뀌면 Compose가 자동으로 화면을 다시 그림 (notify 불필요)
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    // undoStack 크기를 StateFlow로 노출 → Screen이 언두 버튼 표시 여부를 결정
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    // [변경] setButtonsEnabled() 제거 → _isLoading StateFlow로 대체
    // 기존: setButtonsEnabled(false/true)로 버튼 6개의 isEnabled 직접 제어
    // 변경: _isLoading 값을 Screen의 각 버튼 enabled 파라미터에 연결
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var deckId: Long = -1L

    // [변경] onCreate 직접 호출 → startStudy()로 분리
    // 기존: Activity onCreate에서 바로 showNextCardOrDone() 호출
    // 변경: StudyScreen의 LaunchedEffect(deckId)에서 호출
    fun startStudy(deckId: Long) {
        this.deckId = deckId
        loadNextCard()
    }

    // [변경] binding.tvBack.visibility = VISIBLE → _uiState 값 변경
    // 기존: applyUiState(StudyUiState.ANSWER) 호출로 View visibility 직접 제어
    // 변경: _uiState 값만 변경, UI 처리는 StudyScreen의 when(uiState) 분기에 위임
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    // [변경] applyGrade 구조 동일, setButtonsEnabled 제거
    // 기존: setButtonsEnabled(false)로 버튼 잠금 후 lifecycleScope.launch
    // 변경: _isLoading = true로 Screen에 로딩 상태 전달
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

    // [유지] undoLast 로직 동일
    // 기존: StudyActivity.undoLast() — DB 복구 + currentCard 직접 복원 (pollNextCard 거치지 않음)
    // 변경: currentCard 복원을 _currentCard.value 대입으로, UI 상태를 _uiState.value 대입으로 처리
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

    // [변경] showNextCardOrDone() → loadNextCard()
    // 기존: Activity 내 lifecycleScope.launch, 결과를 currentCard 멤버 변수에 직접 대입
    //       applyUiState()로 View visibility 직접 제어
    // 변경: viewModelScope.launch, 결과를 _currentCard / _uiState StateFlow로 노출
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

    // [유지] resolveUpdatedCard 로직 동일
    // 기존: StudyActivity.resolveUpdatedCard() 와 동일
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW -> resolveUpdatedReviewCard(card, score, now)
            else -> card
        }
    }

    // [유지] NEW/LEARNING 카드 채점 처리 동일
    // Again → step 0 초기화, 1분 후 재등장
    // Hard  → 현재 step 유지, 같은 시간 후 재등장
    // Good  → 다음 step으로 진행, 마지막 step이면 REVIEW 졸업
    // Easy  → 즉시 REVIEW 졸업
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

    // [유지] REVIEW 카드 채점 처리 동일
    // Again → LEARNING 강등, step 0, 1분 후
    // Hard/Good/Easy → SM2 계산 후 REVIEW 유지
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            else -> {
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    // [유지] applySm2 난이도별 계산 로직 동일
    // Again → ef-0.20, interval=1
    // Hard  → ef-0.15, interval*1.2
    // Good  → ef 유지,  interval*ef
    // Easy  → ef+0.15, interval*ef*1.3 (easyBonus)
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

    // [유지] toSm2Q 매핑 동일 — Again=0, Hard=3, Good=4, Easy=5
    private fun toSm2Q(score: Int) = when (score) { 0 -> 0; 1 -> 3; 2 -> 4; else -> 5 }

    // [유지] startOfTodayMillis() 동일 — 오늘 00:00:00(ms) 반환
    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // [유지] addDaysAtStartOfDay() 동일 — 오늘 자정 + days일 후를 반환
    private fun addDaysAtStartOfDay(nowMillis: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfTodayMillis(nowMillis)
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }
}
