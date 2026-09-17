package com.example.anki_advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// 일반 모드(완주 모드가 아닌 기본) 학습 화면(StudyScreen)의 상태와 로직을 담당하는 ViewModel.
// 카드 순서 결정, SM-2 계산, 되돌리기(Undo)까지 이 파일 하나에서 전부 처리한다.

// [문법] enum class StudyUiState { QUESTION, ANSWER, DONE }
//   학습 화면이 가질 수 있는 상태 3가지를 타입으로 고정.
//   QUESTION = 카드 앞면만 보이는 중, ANSWER = 뒷면 + 채점 버튼 노출, DONE = 오늘 학습 완료.
enum class StudyUiState { QUESTION, ANSWER, DONE }

// 화면의 채점 버튼 점수(0~3)를 SM-2 알고리즘이 쓰는 품질 점수(q)로 바꿔주는 변환 함수.
// Again=0→0, Hard=1→3, Good=2→4, Easy=3→5.
// [문법] fun sm2Q(score: Int) = when (score) { ... }
//   중괄호 { } 없이 "= 식"으로 쓰는 단일 표현식 함수. when도 값을 만들어내는 식으로 쓸 수 있다.
//   completion 패키지에서도 이 함수를 그대로 가져다 쓰므로 private을 안 붙여 다른 파일에 공개해둔 것.
fun sm2Q(score: Int) = when (score) { 0 -> 0; 1 -> 3; 2 -> 4; else -> 5 }

private data class Sm2Result(
    val repetition: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val nextReviewAt: Long
)

// "되돌리기(Undo)"를 위해 채점 직전 카드 상태를 통째로 백업해두는 그릇.
// prevCard             : 채점 전 카드 전체 상태(status, learningStep, nextReviewAt 포함).
//                         되돌릴 때 이 값 그대로 DB를 덮어써서 복원한다.
// insertedReviewLogId  : 이 채점으로 새로 생긴 리뷰 로그 행의 id. 되돌릴 때 이 로그도 지워야 한다.
//                         applyGrade()에서 "일단 백업부터 스택에 넣고, DB insert가 끝난 뒤에
//                         이 필드를 채워 넣는" 순서라서 아주 잠깐 null일 수 있다.
private data class UndoEntry(
    val prevCard: CardEntity,
    val insertedReviewLogId: Long?
)

// LEARNING 단계 대기 시간: step 0 → 1분(60,000ms) 후, step 1 → 10분(600,000ms) 후.
// 실제 Anki의 기본 학습 단계(1분, 10분)를 그대로 따른 값.
private val LEARNING_STEPS_MS = listOf(
    1 * 60 * 1000L,
    10 * 60 * 1000L
)

// 화면 하단 진행률 바에 전달하는 값.
// done : 오늘 완료한 채점 수 (review_logs 테이블 기준)
// total: done + 아직 남은 카드 수. 고정된 목표치가 아니라 "지금 시점 실제 덱 카드 수" 기준이라
//        학습을 진행할수록(카드를 추가/삭제해도) 동적으로 값이 변한다.
data class StudyProgress(val done: Int, val total: Int)

// AndroidViewModel을 상속하고 StateFlow로 상태를 공개 → StudyScreen이 구독.
// ViewModel은 화면 회전 같은 구성 변경에도 죽지 않고 상태를 그대로 유지해준다.
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체가 공유하는 AppDatabase 싱글턴 인스턴스.
    private val db = AppDatabase.getInstance(application)

    // [문법] ArrayDeque를 스택으로 사용
    //   ArrayDeque는 양쪽 끝에서 추가/제거가 빠른 자료구조. addLast()로 맨 뒤에 넣고(push),
    //   removeLast()로 맨 뒤에서 뺀다(pop) — "마지막에 넣은 게 먼저 나온다(LIFO)"는 스택의 동작을 흉내낸다.
    private val undoStack = ArrayDeque<UndoEntry>()

    // _uiState(쓰기 전용) / uiState(읽기 전용) 짝 패턴.
    // StudyScreen이 uiState.collectAsState()로 구독해두면 값이 바뀔 때마다 자동으로 다시 그려진다.
    private val _uiState = MutableStateFlow(StudyUiState.QUESTION)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _currentCard = MutableStateFlow<CardEntity?>(null)
    val currentCard: StateFlow<CardEntity?> = _currentCard.asStateFlow()

    // undoStack 자체는 StateFlow가 아니라서(그냥 평범한 자료구조), "크기"만 별도 StateFlow로 공개.
    // Screen은 이 값이 0보다 클 때만 되돌리기 버튼을 보여준다.
    private val _undoStackSize = MutableStateFlow(0)
    val undoStackSize: StateFlow<Int> = _undoStackSize.asStateFlow()

    // 채점 처리 중(DB 저장 진행 중)에는 true. 이 값을 각 버튼의 enabled에 연결해두면,
    // 사용자가 응답을 기다리는 동안 버튼을 또 눌러 중복 채점되는 걸 막을 수 있다.
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 오늘 학습 진행 상황. 초기값 (0, 0)은 startStudy() → loadNextCard() → refreshProgress()가
    // 처음 실행되기 전까지 잠깐 유지되는 값이다.
    private val _progress = MutableStateFlow(StudyProgress(0, 0))
    val progress: StateFlow<StudyProgress> = _progress.asStateFlow()

    private var deckId: Long = -1L

    // 화면(Screen)의 LaunchedEffect(deckId)에서 호출. deckId가 바뀔 때마다 자동으로
    // 다시 불려서 그 덱의 다음 카드를 보여준다.
    fun startStudy(deckId: Long) {
        this.deckId = deckId
        loadNextCard()
    }

    // "정답 보기" 버튼 콜백. uiState를 ANSWER로 바꾸면 화면이 알아서 뒷면 + 채점 버튼을 보여준다.
    fun showAnswer() {
        _uiState.value = StudyUiState.ANSWER
    }

    // 채점 버튼(다시/어려움/좋음/쉬움) 콜백.
    fun applyGrade(score: Int) {
        val card = _currentCard.value ?: return

        // 채점 전 카드 상태를 먼저 되돌리기 스택에 백업해둔다.
        // 이 시점엔 아직 리뷰 로그를 안 만들었으니 insertedReviewLogId는 일단 null로 넣어두고,
        // 아래에서 DB insert가 끝난 뒤 값을 채워 교체한다.
        undoStack.addLast(UndoEntry(prevCard = card, insertedReviewLogId = null))
        _undoStackSize.value = undoStack.size
        _isLoading.value = true  // 채점 처리 중에는 버튼들을 잠근다

        viewModelScope.launch {
            // [문법] try { ... } finally { ... }
            //   중간에 예외가 나든 안 나든 finally 블록은 반드시 실행된다.
            //   여기서는 "성공하든 실패하든 로딩 상태(_isLoading)는 반드시 풀어준다"를 보장하는 용도.
            try {
                val now = System.currentTimeMillis()
                // 현재 카드 상태 + 채점 점수를 기반으로, 업데이트될 카드의 전체 모습을 미리 계산.
                val updatedCard = resolveUpdatedCard(card, score, now)

                var logId = -1L
                withContext(Dispatchers.IO) {
                    // 1) SM-2 계산 결과(상태, 반복 횟수, 간격, 난이도 계수, 다음 복습 시각)를 DB에 반영
                    db.cardDao().updateSm2(
                        id = card.id,
                        state = score,
                        status = updatedCard.status,
                        repetition = updatedCard.repetition,
                        intervalDays = updatedCard.intervalDays,
                        easeFactor = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt
                    )
                    // 2) learningStep은 SM-2 값과 독립적으로 관리되므로 별도 쿼리로 갱신
                    db.cardDao().updateLearningStep(card.id, updatedCard.learningStep)
                    // 3) 이번 채점을 리뷰 로그 한 줄로 기록. 반환값은 새로 생긴 행의 id.
                    logId = db.reviewLogDao().insert(
                        ReviewLogEntity(
                            deckId = card.deckId,
                            cardId = card.id,
                            score = score,
                            sm2Q = toSm2Q(score),
                            reviewedAt = now,
                            // 채점 시점에 NEW 카드였는지 기록 (홈 화면 "오늘 NEW 카운트" 계산에 사용)
                            isNewAtReview = if (card.status == CARD_NEW) 1 else 0
                        )
                    )
                }

                // DB insert가 끝난 뒤에야 진짜 logId를 알 수 있으므로, 스택 맨 위 항목을 꺼내서
                // [문법] removeLast() 후 copy()로 값을 바꾼 새 객체를 다시 addLast()
                //   UndoEntry는 data class라 일부 필드만 바꾼 새 인스턴스를 copy()로 쉽게 만들 수 있다.
                //   ArrayDeque는 "중간 항목을 직접 수정"하는 기능이 없어서, 꺼냈다가(pop) 고쳐서
                //   다시 넣는(push) 방식으로 교체한다.
                val last = undoStack.removeLast()
                undoStack.addLast(last.copy(insertedReviewLogId = logId))
                _undoStackSize.value = undoStack.size

                loadNextCard()  // 다음 카드 로딩
            } finally {
                _isLoading.value = false  // 성공/실패 상관없이 버튼 다시 활성화
            }
        }
    }

    // "↩ 되돌리기" 버튼 콜백.
    fun undoLast() {
        if (undoStack.isEmpty()) return
        val undo = undoStack.removeLast()   // 스택에서 가장 최근 채점 기록 꺼내기
        _undoStackSize.value = undoStack.size
        _isLoading.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1) 리뷰 로그 삭제. logId가 null이면(아주 드문 타이밍 이슈) 삭제할 대상이 없으므로 건너뜀.
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)
                    }
                    // 2) 카드 전체를 채점 전 상태로 되돌림 (status, learningStep, nextReviewAt 전부 포함)
                    db.cardDao().update(undo.prevCard)
                }
                // 3) 복구한 카드를 loadNextCard()를 거치지 않고 곧바로 화면에 표시.
                //    loadNextCard()를 거치면 "재노출 시각(nextReviewAt) 조건"에 걸려서
                //    방금 되돌린 카드가 다시 안 나타날 수 있기 때문.
                refreshProgress()  // 로그 삭제로 인해 바뀐 진행률을 다시 계산
                _currentCard.value = undo.prevCard
                _uiState.value = StudyUiState.QUESTION
            } finally {
                _isLoading.value = false
            }
        }
    }

    // 오늘 학습 완료 수(done)와, 지금 시점 기준 아직 남은 카드 수를 합산해 total을 계산.
    // total = done + remaining (아래 loadNextCard()가 카드를 고르는 우선순위와 같은 기준으로 계산)
    //   remaining_learning : LEARNING 상태인 카드 전부 (한도 없이 항상 남은 카드로 침)
    //   remaining_review   : 오늘 복습 대상 중, 하루 복습 한도를 넘지 않는 만큼만
    //   remaining_new      : NEW 카드 중, 하루 신규 한도를 넘지 않는 만큼만
    // applyGrade() 뒤의 loadNextCard(), undoLast() 뒤 각각에서 호출된다.
    private suspend fun refreshProgress() {
        if (deckId == -1L) return  // startStudy()가 아직 호출 전이면 계산할 게 없으니 건너뜀
        val now        = System.currentTimeMillis()
        val todayStart = startOfTodayMillis(now)
        val todayEnd   = todayStart + 24 * 60 * 60 * 1000L  // 오늘 자정 ~ 내일 자정
        // [문법] val (done, total) = withContext(...) { ... to ... }
        //   Pair(두 값의 짝)를 "구조 분해 선언"으로 한 번에 두 변수에 나눠 담는 문법.
        //   블록의 마지막 식이 Pair를 만들어내면, 그걸 (done, total) 두 변수로 바로 풀어서 받을 수 있다.
        val (done, total) = withContext(Dispatchers.IO) {
            // 오늘 완료한 전체 채점 수 (점수 무관, 그냥 "채점한 횟수")
            val done       = db.reviewLogDao().countToday(deckId, todayStart, todayEnd)
            val limits     = db.deckDao().getStudyLimits(deckId)
            // 한도 초과분을 제외한 "남은 수"를 계산하려면, 오늘 이미 완료한 new/review 수가 따로 필요하다.
            val doneNew    = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
            val doneReview = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

            // LEARNING 카드는 한도 개념이 없어서 시각 조건과 무관하게 전부 "남은 카드"로 취급.
            val learningRemaining = db.cardDao().countLearningCards(deckId)
            // REVIEW 카드는 "오늘 대상인 카드 수"와 "한도 - 이미 완료한 수" 중 작은 쪽을 남은 수로 삼는다.
            // [문법] .coerceAtMost(x) → 값이 x보다 크면 x로 깎기(상한 고정). 한도를 넘지 않게 하는 용도.
            //        .coerceAtLeast(0) → 한도를 이미 넘겼을 때 음수가 나오는 걸 방지.
            val reviewRemaining   = db.cardDao().countReviewCards(deckId, todayStart)
                .coerceAtMost(limits.dailyReviewLimit - doneReview)
                .coerceAtLeast(0)
            // NEW 카드도 같은 방식으로 한도 이내만 남은 수로 계산.
            val newRemaining      = db.cardDao().countNewCards(deckId)
                .coerceAtMost(limits.dailyNewLimit - doneNew)
                .coerceAtLeast(0)

            // total = 완료 + 남은. 학습이 진행될수록 done은 늘고 remaining은 줄어서 total은 유지되거나 줄어든다.
            // [문법] a to b → Pair(a, b)를 만드는 짧은 표기법 (중위 함수, infix function).
            done to (done + learningRemaining + reviewRemaining + newRemaining)
        }
        _progress.value = StudyProgress(done, total)
    }

    // 다음에 보여줄 카드를 우선순위대로 골라서 화면 상태를 갱신.
    // 고를 카드가 없으면 DONE(완료 화면)으로, 있으면 QUESTION(질문 화면)으로 자동 전환한다.
    private fun loadNextCard() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val todayStart = startOfTodayMillis(now)

                // 우선순위 1: 재노출 시각이 된 LEARNING 카드 (한도와 무관하게 항상 최우선)
                // → 1분/10분 대기 시간이 정확히 지났을 때 나타나야 망각곡선 효과가 제대로 산다.
                val learning = db.cardDao().getNextLearningCard(deckId, now)
                // [문법] return@withContext 값
                //   람다/블록 안에서 "이 withContext 블록 전체를 여기서 끝내고 이 값을 결과로 써라"는 뜻.
                //   일반 return과 달리 바깥의 loadNextCard() 함수까지 끝내는 게 아니라, 이 블록만 끝낸다.
                if (learning != null) return@withContext learning

                // 오늘 이미 채점한 new/review 수를 조회해서 한도를 넘었는지 확인.
                val limits = db.deckDao().getStudyLimits(deckId)
                val newDoneToday = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
                val reviewDoneToday = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

                // 우선순위 2: 오늘 복습 대상 REVIEW 카드 (하루 복습 한도를 안 넘었을 때만)
                if (reviewDoneToday < limits.dailyReviewLimit) {
                    val review = db.cardDao().getNextReviewCard(deckId, todayStart)
                    if (review != null) return@withContext review
                }

                // 우선순위 3: NEW 카드 (하루 신규 한도를 안 넘었을 때만)
                if (newDoneToday < limits.dailyNewLimit) {
                    val new = db.cardDao().getNextNewCard(deckId)
                    if (new != null) return@withContext new
                }

                // 우선순위 4: 아직 재노출 시각이 안 됐지만 대기 중인 LEARNING 카드를 시간 무시하고 꺼내옴.
                // 1~3순위가 전부 바닥났는데도 이 카드가 남아있으면, 그냥 "학습 완료"로 잘못 끝나버리는
                // 걸 막기 위해 시간 조건을 무시하고 조기 등장시킨다.
                db.cardDao().getNextPendingLearningCard(deckId)
            }

            refreshProgress()  // 카드를 화면에 띄우기 전에 진행률부터 최신화

            if (next == null) {
                _uiState.value = StudyUiState.DONE   // 더 볼 카드가 없음 → 완료 화면
            } else {
                _currentCard.value = next
                _uiState.value = StudyUiState.QUESTION  // 새 카드 → 질문(앞면) 상태로
            }
        }
    }

    // ── SM-2 알고리즘 ──

    // 카드의 현재 상태(status)에 따라 "학습 중" 처리와 "복습 중" 처리로 나눠 위임.
    // NEW 카드는 LEARNING과 똑같이 취급한다 (둘 다 "아직 SM-2 간격 반복에 안 들어간 상태"라서).
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW -> resolveUpdatedReviewCard(card, score, now)
            else -> card
        }
    }

    // LEARNING(또는 NEW) 카드를 채점했을 때 다음 상태를 계산.
    // score 0(Again) → step 0으로 리셋, 1분 뒤 재등장
    // score 1(Hard)  → 지금 step 그대로, 같은 대기시간 뒤 재등장
    // score 2(Good)  → 다음 step으로 진행. 마지막 step까지 통과했으면 SM-2 계산 후 REVIEW로 졸업
    // score 3(Easy)  → step을 다 건너뛰고 곧바로 SM-2 계산 후 REVIEW로 졸업
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> // Again: step 0으로 리셋, 1분 후 재등장
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            1 -> { // Hard: 현재 step 유지
                // [문법] .coerceIn(0, LEARNING_STEPS_MS.lastIndex)
                //   값이 0과 lastIndex(리스트의 마지막 인덱스) 사이를 벗어나지 않게 잘라내는 범위 제한.
                //   learningStep 값이 배열 범위를 벗어나 IndexOutOfBounds가 나는 걸 막는 안전장치.
                val step = card.learningStep.coerceIn(0, LEARNING_STEPS_MS.lastIndex)
                card.copy(status = CARD_LEARNING, state = score, learningStep = step, nextReviewAt = now + LEARNING_STEPS_MS[step])
            }
            2 -> { // Good: 다음 step으로 진행
                val nextStep = card.learningStep + 1
                if (nextStep >= LEARNING_STEPS_MS.size) {
                    // 마지막 step(10분)까지 통과했으므로 REVIEW로 졸업
                    val sm2 = applySm2(card, score)
                    card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
                } else {
                    // 아직 다음 step이 남아있으므로 그쪽으로 이동
                    card.copy(status = CARD_LEARNING, state = score, learningStep = nextStep, nextReviewAt = now + LEARNING_STEPS_MS[nextStep])
                }
            }
            else -> { // Easy: step 상관없이 즉시 REVIEW로 졸업
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    // REVIEW 카드를 채점했을 때 다음 상태를 계산.
    // score 0(Again)   → REVIEW에서 LEARNING으로 강등, step 0부터 다시 시작
    // score 1/2/3      → SM-2 계산 후 REVIEW 유지 (난이도별로 간격/난이도 계수가 다르게 갱신됨)
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> // Again: REVIEW → LEARNING 강등
                card.copy(status = CARD_LEARNING, state = score, learningStep = 0, nextReviewAt = now + LEARNING_STEPS_MS[0])
            else -> { // Hard/Good/Easy: SM-2 계산 후 REVIEW 유지
                val sm2 = applySm2(card, score)
                card.copy(status = CARD_REVIEW, state = score, learningStep = 0, repetition = sm2.repetition, intervalDays = sm2.intervalDays, easeFactor = sm2.easeFactor, nextReviewAt = sm2.nextReviewAt)
            }
        }
    }

    // SM-2 알고리즘 본체: 품질 점수(q)에 따라 반복 횟수 / 간격(일) / 난이도 계수(ef)를 갱신한다.
    // q=0(Again): 반복 횟수 리셋, 간격=1일, ef-0.20 (최솟값 1.3)
    // q=3(Hard) : 반복+1, 간격*1.2, ef-0.15
    // q=4(Good) : 반복+1, 간격*ef,  ef는 그대로
    // q=5(Easy) : 반복+1, 간격*ef*1.3(이지 보너스), ef+0.15
    private fun applySm2(card: CardEntity, score: Int): Sm2Result {
        val q = toSm2Q(score)
        var ef = card.easeFactor          // 현재 난이도 계수 (기본 2.5, 최솟값 1.3으로 하한 고정)
        var rep = card.repetition         // 누적 "합격(Hard 이상)" 횟수
        var interval = card.intervalDays  // 현재 복습 간격 (일)

        when (q) {
            0 -> { // Again: 처음부터 다시 시작
                rep = 0
                interval = 1
                ef = (ef - 0.20).coerceAtLeast(1.3)  // ef가 1.3 밑으로 안 내려가게 보장
            }
            3 -> { // Hard: 느리게 진행
                rep += 1
                interval = when (rep) {
                    1 -> 1   // 첫 복습: 1일 뒤
                    2 -> 6   // 두 번째 복습: 6일 뒤
                    else -> (interval * 1.2).toInt().coerceAtLeast(1)  // 그 이후: 현재 간격 * 1.2
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
                // ef는 변화 없음
            }
            else -> { // Easy: 빠르게 진행
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> kotlin.math.round(interval * ef * 1.3).toInt().coerceAtLeast(1)  // 이지 보너스 1.3배
                }
                ef = (ef + 0.15).coerceAtLeast(1.3)
            }
        }

        // 다음 복습 시각 = "오늘 자정" + interval일. 자정 기준으로 맞추는 이유:
        // 같은 날 여러 번 학습해도 다음 복습일이 항상 "그날의 자정 기준"으로 일관되게 계산되도록.
        val nextAt = addDaysAtStartOfDay(System.currentTimeMillis(), interval)
        return Sm2Result(rep, interval, ef, nextAt)
    }

    // sm2Q()를 이 클래스 안에서 짧게 부르기 위한 위임 함수.
    private fun toSm2Q(score: Int) = sm2Q(score)

    // 주어진 시각이 속한 날의 자정(00:00:00.000)을 ms로 반환. 복습 카드 조회 기준점으로 쓰인다.
    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // "오늘 자정"을 기준으로 days일 뒤의 자정 시각을 ms로 반환.
    // 예: days=1 → 내일 자정, days=6 → 6일 뒤 자정.
    private fun addDaysAtStartOfDay(nowMillis: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfTodayMillis(nowMillis)
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }
}
