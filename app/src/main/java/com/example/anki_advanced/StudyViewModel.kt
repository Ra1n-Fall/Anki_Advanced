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

// [유지] StudyUiState 열거형 동일
// 기존: StudyActivity 내부에 선언
// 변경: 별도 파일 수준으로 이동 (StudyScreen에서도 참조하므로)
// QUESTION = 앞면만 보이는 상태, ANSWER = 뒷면 + 채점 버튼, DONE = 학습 완료
enum class StudyUiState { QUESTION, ANSWER, DONE }

private data class Sm2Result(
    val repetition: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val nextReviewAt: Long
)

// prevCard: 채점 전 카드 상태 전체를 백업 (status, learningStep, nextReviewAt 포함)
//           → 언두 시 DB를 이 상태로 되돌림
// insertedReviewLogId: 채점으로 삽입된 리뷰 로그 row의 id
//           → 언두 시 이 id로 로그를 삭제
// insertedReviewLogId가 null인 경우: applyGrade()에서 undo 스택에 먼저 push하고
//           DB insert가 끝난 뒤 logId를 채워 넣기 때문에 일시적으로 null
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// step 0 → 1분(60,000ms) 후, step 1 → 10분(600,000ms) 후
// 이유: 실제 Anki의 기본 학습 단계(1min, 10min)와 동일
private val LEARNING_STEPS_MS = listOf(
    1 * 60 * 1000L,
    10 * 60 * 1000L
)

// StudyProgressBar에 전달하는 진행 상황 데이터
// done : 오늘 완료한 리뷰 수 (review_logs 테이블 기준)
// total: done + 아직 남은 카드 수 (LEARNING 전체 + 한도 미달 REVIEW/NEW)
//        total이 고정 한도가 아닌 실제 덱 카드 수 기준이므로 학습 중에 동적으로 변함
data class StudyProgress(val done: Int, val total: Int)

// AndroidViewModel 상속, StateFlow로 상태 노출 → StudyScreen이 구독
//       ViewModel은 화면 회전에도 살아남아 상태 유지됨
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    // DB 인스턴스 생성

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    // undoStack 구조 동일
    // ArrayDeque를 스택으로 사용: addLast()로 push, removeLast()로 pop
    private val undoStack = ArrayDeque<UndoEntry>()


    // StateFlow로 선언 → StudyScreen이 collectAsState()로 구독
    //       값이 바뀌면 Compose가 자동으로 화면을 다시 그림 (notify 불필요)
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()
    // _uiState: ViewModel 내부에서만 쓰기 가능 (private)
    // uiState: Screen에는 읽기 전용으로 노출 (asStateFlow로 감쌈)

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    // undoStack 자체는 Flow가 아니므로 크기를 별도 StateFlow로 노출
    // Screen은 undoStackSize > 0 일 때만 언두 버튼을 표시
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    // [변경] setButtonsEnabled() 제거 → _isLoading StateFlow로 대체
    // 기존: setButtonsEnabled(false/true)로 버튼 6개(Again/Hard/Good/Easy/ShowAnswer/Undo)의
    //       isEnabled를 직접 설정
    //       참고: withContext(Dispatchers.IO) 블록 동안 DB를 처리할 때
    //       작업스레드 이후의 코드는 잠시 중지되지만 이외의 메인스레드 작업은 중지되지 않으므로
    //       → 사용자가 버튼을 또 탭할 수 있음.
    //       버튼 잠금이 필요
    // _isLoading 값을 Screen의 각 버튼 enabled 파라미터에 연결 → 선언적으로 처리
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 오늘 학습 진행 상황 — StudyProgressBar에 전달
    // 초기값 (0, 0): startStudy() → loadNextCard() → refreshProgress() 호출 전까지 유지
    private val _progress = MutableStateFlow(StudyProgress(0, 0))
    val progress: StateFlow<StudyProgress> = _progress.asStateFlow()

    private var deckId: Long = -1L


    //       Screen의 LaunchedEffect(deckId)에서 startStudy() 호출
    //       deckId가 바뀔 때마다 자동으로 재호출되어 다음카드를 보여줌
    fun startStudy(deckId: Long) {
        this.deckId = deckId
        loadNextCard()
    }

    // [StudyScreen 버튼 콜백 — "정답 보기" 버튼]
    //       StudyScreen의 when(uiState) 분기가 ANSWER일 때 뒷면 + 채점 버튼을 자동 표시
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    // [StudyScreen 버튼 콜백 — 채점 버튼 (다시/어려움/좋음/쉬움)]
    // _isLoading = true로 Screen에 로딩 상태 전달 → Screen이 버튼 비활성화 처리
    fun applyGrade(score: Int) {
        val card = _currentCard.value ?: return

        // 채점 전 카드 상태를 먼저 스택에 백업
        // insertedReviewLogId = null 인 상태로 일단 push
        // → DB insert가 끝난 뒤 아래에서 logId를 채워 교체함
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))
        _undoStackSize.value = undoStack.size
        _isLoading.value = true  // 채점 처리 중 버튼 비활성화

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                // 현재 카드의 상태(status, score)에 따라 업데이트될 카드 전체 상태 계산
                val updatedCard = resolveUpdatedCard(card, score, now)

                var logId = -1L
                withContext(Dispatchers.IO) {
                    // 1) SM2 관련 필드 업데이트 (status, repetition, intervalDays, easeFactor, nextReviewAt)
                    db.cardDao().updateSm2(
                        id = card.id,
                        state = score,
                        status = updatedCard.status,
                        repetition = updatedCard.repetition,
                        intervalDays = updatedCard.intervalDays,
                        easeFactor = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt
                    )
                    // 2) learningStep은 SM2 쿼리와 별도 쿼리로 업데이트
                    //    이유: learningStep은 SM2 값과 독립적으로 관리되므로
                    db.cardDao().updateLearningStep(card.id, updatedCard.learningStep)
                    // 3) 리뷰 로그 삽입 → 생성된 row id를 logId에 저장
                    logId = db.reviewLogDao().insert(
                        ReviewLogEntity(
                            deckId = card.deckId,
                            cardId = card.id,
                            score = score,
                            sm2Q = toSm2Q(score),
                            reviewedAt = now,
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                            // 채점 시점에 NEW 카드였는지 기록 (홈 화면 오늘 NEW 카운트에 사용)
                        )
                    )
                }

                // DB insert가 끝난 뒤 logId를 스택의 최상단 항목에 채워 넣기
                // removeLast() + addLast()로 교체하는 이유:
                //   UndoEntry는 data class라 copy()로 일부 필드만 변경 가능
                //   ArrayDeque는 인덱스 직접 교체가 번거로우므로 pop → 수정 → push 패턴 사용
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                loadNextCard()  // 다음 카드 로딩
            } finally {
                _isLoading.value = false  // 성공/실패 모두 버튼 다시 활성화
            }
        }
    }

    // [StudyScreen 버튼 콜백 — "↩ 되돌리기" 버튼]
    fun undoLast() {
        if (undoStack.isEmpty()) return
        val undo = undoStack.removeLast()   // 스택에서 가장 최근 채점 꺼내기
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1) 리뷰 로그 삭제 (logId가 있을 때만 — DB insert 전에 앱이 종료된 경우 null)
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)
                    }
                    // 2) 카드 전체를 채점 전 상태로 되돌림 (status, learningStep, nextReviewAt 포함)
                    db.cardDao().update(undo.prevCard)
                }
                // 3) 복구한 카드를 직접 화면에 표시 (loadNextCard() 거치지 않음)
                //    loadNextCard()를 거치면 시간 조건(nextReviewAt <= now)에 걸려
                //    방금 복구한 카드가 다시 등장하지 않을 수 있음
                refreshProgress()  // 로그 삭제 후 진행률 갱신
                //mutable 변수를 갱신해서 스크린에서 자동 렌더링
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    // DB에서 오늘 학습 완료 수(done)와 남은 카드 수를 합산해 total을 계산 후 _progress 갱신
    // total = done + remaining (loadNextCard()와 동일한 우선순위 기준)
    //   remaining_learning = 아직 LEARNING 상태인 카드 수 (한도 무관, 항상 등장)
    //   remaining_review   = 오늘 복습 대상 카드 수, reviewLimit 초과분 제외
    //   remaining_new      = NEW 카드 수, newLimit 초과분 제외
    // applyGrade() 후 loadNextCard()에서, undoLast() 후에 각각 호출
    private suspend fun refreshProgress() {
        if (deckId == -1L) return  // startStudy() 호출 전이면 계산 불가 → 스킵
        val now        = System.currentTimeMillis()
        val todayStart = startOfTodayMillis(now)
        val todayEnd   = todayStart + 24 * 60 * 60 * 1000L  // 오늘 자정 ~ 내일 자정
        val (done, total) = withContext(Dispatchers.IO) {
            // 오늘 완료한 전체 리뷰 수 (score 무관, 채점 횟수 기준)
            val done       = db.reviewLogDao().countToday(deckId, todayStart, todayEnd)
            val limits     = db.deckDao().getStudyLimits(deckId)
            // remaining 계산에서 한도 초과분을 제외하기 위해 오늘 완료한 new/review 수 별도 조회
            val doneNew    = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
            val doneReview = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

            // LEARNING 카드: 한도 무관, 시간이 됐든 안 됐든 전부 남은 카드로 포함
            val learningRemaining = db.cardDao().countLearningCards(deckId)
            // REVIEW 카드: 오늘 대상 카드 중 아직 한도 미달인 수만 포함
            // coerceAtMost: 한도를 초과하지 않도록 상한 클램프
            // coerceAtLeast(0): doneReview가 limit을 초과한 경우 음수 방지
            // 남은 카드가 한도보다 많을 때는
            // 남은 카드 = 최대 한도 - 공부한 양
            val reviewRemaining   = db.cardDao().countReviewCards(deckId, todayStart)
                .coerceAtMost(limits.dailyReviewLimit - doneReview)
                .coerceAtLeast(0)
            // NEW 카드: 전체 NEW 카드 중 아직 한도 미달인 수만 포함
            val newRemaining      = db.cardDao().countNewCards(deckId)
                .coerceAtMost(limits.dailyNewLimit - doneNew)
                .coerceAtLeast(0)

            // total = 완료 + 남은 → 학습이 진행될수록 done↑, remaining↓, total 유지/감소
            done to (done + learningRemaining + reviewRemaining + newRemaining)//to는 Pair를 만드는 infix 함수
        }
        _progress.value = StudyProgress(done, total)
    }

    // 변경: viewModelScope.launch, 결과를 _currentCard / _uiState StateFlow로 노출
    //       카드가 없으면 _uiState = DONE, 있으면 _uiState = QUESTION으로 자동 전환
    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val todayStart = startOfTodayMillis(now)

                // 우선순위 1: 시간이 된 LEARNING 카드 (한도 무관)
                // nextReviewAt <= now 인 LEARNING 카드 — 1분/10분이 경과한 카드
                // 이유: LEARNING 카드가 정해진 시간에 등장해야 망각곡선 효과 최대화
                val learning = db.cardDao().getNextLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                // 오늘 학습한 new/review 수를 조회해 한도 초과 여부 확인
                val limits = db.deckDao().getStudyLimits(deckId)
                val newDoneToday = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
                val reviewDoneToday = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

                // 우선순위 2: 오늘 REVIEW 카드 (dailyReviewLimit 미초과 시에만)
                // nextReviewAt <= todayStart(오늘 자정) 인 REVIEW 카드
                if (reviewDoneToday < limits.dailyReviewLimit) {
                    val review = db.cardDao().getNextReviewCard(deckId, todayStart)
                    if (review != null) return@withContext review
                }

                // 우선순위 3: NEW 카드 (dailyNewLimit 미초과 시에만)
                if (newDoneToday < limits.dailyNewLimit) {
                    val new = db.cardDao().getNextNewCard(deckId)
                    if (new != null) return@withContext new
                }

                // 우선순위 4: 시간이 안 됐지만 대기 중인 LEARNING 카드 (조기 등장)
                // 1~3순위 카드가 모두 소진됐을 때 아직 nextReviewAt이 미래인 LEARNING 카드를
                // 시간 조건 무시하고 꺼내옴
                // 이유: 카드가 남아있는데 "학습 완료"로 잘못 끝나는 문제 방지
                db.cardDao().getNextPendingLearningCard(deckId)
            }

            refreshProgress()  // 카드 로딩 전 진행률 갱신

            if (next == null) {
                _uiState.value = StudyUiState.DONE   // 모든 카드 소진 → 완료 화면
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION  // 새 카드 → 앞면 보기 상태
            }
        }
    }

    // ── SM2 알고리즘 ──

    // 카드 상태(status)에 따라 LEARNING 처리 / REVIEW 처리로 분기
    // NEW 카드는 LEARNING과 동일하게 처리 (첫 학습이므로)
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW -> resolveUpdatedReviewCard(card, score, now)
            else -> card
        }
    }

    // score 0(Again) → step 0으로 초기화, 1분(LEARNING_STEPS_MS[0]) 후 재등장
    // score 1(Hard)  → 현재 step 유지, 같은 단계 시간 후 재등장
    // score 2(Good)  → 다음 step으로 진행, 마지막 step 이후면 SM2 계산 후 REVIEW 졸업
    // score 3(Easy)  → 즉시 SM2 계산 후 REVIEW 졸업
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> // Again: step 0으로 리셋, 1분 후 재등장
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            1 -> { // Hard: 현재 step 유지
                val step = card.learningStep.coerceIn(0, LEARNING_STEPS_MS.lastIndex)
                // coerceIn: learningStep이 범위를 벗어났을 경우 안전하게 클램프
                card.copy(status = CARD_LEARNING, state = score, learningStep = step, nextReviewAt = now + LEARNING_STEPS_MS[step])
            }
            2 -> { // Good: 다음 step으로 진행
                val nextStep = card.learningStep + 1
                if (nextStep >= LEARNING_STEPS_MS.size) {
                    // 마지막 step(10분)을 통과했으므로 REVIEW 졸업
                    val sm2 = applySm2(card, score)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
                } else {
                    // 아직 step이 남아있으므로 다음 단계로 이동
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep, nextReviewAt = now + LEARNING_STEPS_MS[nextStep])
                }
            }
            else -> { // Easy: step 무시하고 즉시 REVIEW 졸업
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    // score 0(Again) → LEARNING으로 강등, step 0 초기화, 1분 후 재등장
    // score 1/2/3    → SM2 계산 후 REVIEW 유지 (각 난이도에 따라 ef/interval 다르게 계산)
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> // Again: REVIEW → LEARNING 강등
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            else -> { // Hard/Good/Easy: SM2 계산 후 REVIEW 유지
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    // q값(SM-2 품질 점수)에 따라 repetition / intervalDays / easeFactor 갱신
    // q=0(Again): rep 초기화, interval=1일, ef-0.20 (최솟값 1.3)
    // q=3(Hard):  rep+1, interval*1.2, ef-0.15
    // q=4(Good):  rep+1, interval*ef,  ef 변화 없음
    // q=5(Easy):  rep+1, interval*ef*1.3(easyBonus), ef+0.15
    private fun applySm2(card: CardEntity, score: Int): Sm2Result {
        val q = toSm2Q(score)
        var ef = card.easeFactor    // 현재 ease factor (기본 2.5, 최솟값 1.3)
        var rep = card.repetition   // 누적 정답 횟수
        var interval = card.intervalDays  // 현재 복습 간격 (일)

        when (q) {
            0 -> { // Again: 처음부터 다시
                rep = 0
                interval = 1
                ef = (ef - 0.20).coerceAtLeast(1.3)  // ef 최솟값 1.3 보장
            }
            3 -> { // Hard: 느리게 진행
                rep += 1
                interval = when (rep) {
                    1 -> 1   // 첫 번째 복습: 1일
                    2 -> 6   // 두 번째 복습: 6일
                    else -> (interval * 1.2).toInt().coerceAtLeast(1)  // 이후: 현재 간격 * 1.2
                }
                ef = (ef - 0.15).coerceAtLeast(1.3)
            }
            4 -> { // Good: 정상 진행
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> kotlin.math.round(interval * ef).toInt().coerceAtLeast(1)  // 현재 간격 * ef
                }
                // ef 변화 없음
            }
            else -> { // Easy: 빠르게 진행
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> kotlin.math.round(interval * ef * 1.3).toInt().coerceAtLeast(1)  // easyBonus 1.3 적용
                }
                ef = (ef + 0.15).coerceAtLeast(1.3)
            }
        }

        // 다음 복습일 = 오늘 자정 + interval일
        // 자정 기준으로 계산하는 이유: 같은 날 학습하면 항상 같은 날 재등장하도록
        val nextAt = addDaysAtStartOfDay(System.currentTimeMillis(), interval)
        return Sm2Result(rep, interval, ef, nextAt)
    }

    // UI 점수(0~3) → SM-2 q값(0,3,4,5) 변환
    // Again=0, Hard=3, Good=4, Easy=5
    // 원래 SM-2는 0~5 연속값이지만 4단계 버튼에 맞춰 4개 값으로 매핑
    private fun toSm2Q(score: Int) = when (score) { 0 -> 0; 1 -> 3; 2 -> 4; else -> 5 }

    // 오늘 00:00:00:000(ms) 반환 — 복습 카드 조회 기준점으로 사용
    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // 오늘 자정(00:00) 기준으로 days일 후 ms를 반환
    // 예: interval=1 → 내일 자정, interval=6 → 6일 후 자정
    private fun addDaysAtStartOfDay(nowMillis: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfTodayMillis(nowMillis)
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }
}
