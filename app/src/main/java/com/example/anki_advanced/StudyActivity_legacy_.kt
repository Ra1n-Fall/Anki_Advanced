package com.example.anki_advanced

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.anki_advanced.databinding.ActivityStudyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar // 자정 계산을 위해 Calendar 사용

/*
private data class Sm2Result(
    val repetition: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val nextReviewAt: Long
)

// [변경] 큐 스냅샷 제거 → 채점 전 카드 상태 1개만 저장
// 기존: 큐 4개 전체 스냅샷 + prevCard + logId
// 변경: prevCard + logId 만 저장
// 이유: DB 직접 조회 방식으로 변경되어 큐 복구가 불필요해짐
//       언두 여러 번 해도 스택에 카드 1개씩만 쌓여 메모리 부담 없음
private data class UndoEntry(
    val prevCard: CardEntity,        // 채점 전 카드 상태 (status, learningStep, nextReviewAt 등)
    val insertedReviewLogId: Long?   // 실행 취소할 카드의 로그 DB 속 아이디
)

// [변경] LEARNING 단계 시간 추가
// 기존: LEARNING 카드를 todayLearningQueue 맨 뒤에 삽입 (순서 기반)
// 변경: step 시간만큼 후에 nextReviewAt 설정 (시간 기반)
// 이유: 실제 Anki처럼 망각곡선 타이밍에 맞춰 재등장하도록
private val LEARNING_STEPS_MS = listOf(
    1 * 60 * 1000L,    // step 0: 1분 후
    10 * 60 * 1000L    // step 1: 10분 후
)

// [변경] UI 상태를 열거형으로 단일 관리
// 기존: showUndoButton / hideUndoButton 등 개별 함수가 여기저기서 호출되어 타이밍 문제 발생
// 변경: StudyUiState로 상태를 한 곳에서 관리
// 이유: 완료 화면에서 실행취소/정답보기 버튼이 남아있는 문제 해결
enum class StudyUiState {
    QUESTION,  // 앞면만 보이는 상태
    ANSWER,    // 뒷면 + 난이도 버튼
    DONE       // 학습 완료
}

class StudyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudyBinding
    private lateinit var db: AppDatabase

    // [변경] 큐 4개 제거 → DB 직접 조회 방식으로 변경
    // 기존: carryLearningQueue / reviewQueue / newQueue / todayLearningQueue
    // 변경: 큐 없음, pollNextCard() 호출마다 DB에서 조건 만족하는 카드 조회
    // 이유: 언두 여러 번 시 큐 동기화 문제 없음, 메모리 효율적
    private val undoStack = ArrayDeque<UndoEntry>()
    private var currentCard: CardEntity? = null
    private var deckId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStudyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 덱 아이디를 바탕으로 선택된 덱을 구분
        deckId = intent.getLongExtra("deck_id", -1L)
        if (deckId == -1L) {
            finish()
            return
        }

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "anki.db")

            .build()

        // [변경] 초기 큐 로딩 제거
        // 기존: DB에서 카드를 미리 로딩해서 큐에 담아둠
        // 변경: showNextCardOrDone() 바로 호출, 카드는 pollNextCard()에서 그때그때 DB 조회
        lifecycleScope.launch {
            showNextCardOrDone()
        }

        binding.btnShowAnswer.setOnClickListener {
            showCard()
            applyUiState(StudyUiState.ANSWER)
        }

        binding.btnUndo.setOnClickListener {
            undoLast()
        }

        binding.btnAgain.setOnClickListener { applyGrade(0) }
        binding.btnHard.setOnClickListener  { applyGrade(1) }
        binding.btnGood.setOnClickListener  { applyGrade(2) }
        binding.btnEasy.setOnClickListener  { applyGrade(3) }

        // 통계 버튼 바인딩

        binding.btnGoHome.setOnClickListener {
            finish()
        }
    }

    // [변경] UI 상태를 한 곳에서 관리
    // 기존: showUndoButton() / hideUndoButton() 등 개별 함수가 여기저기서 호출
    // 변경: StudyUiState에 따라 모든 뷰의 visibility를 한 번에 결정
    // 이유: 타이밍 문제로 완료 화면에 실행취소/정답보기 버튼이 남아있던 문제 해결
    private fun applyUiState(state: StudyUiState) {
        when (state) {
            StudyUiState.QUESTION -> {
                binding.tvFront.visibility = View.VISIBLE
                binding.tvBack.visibility = View.GONE
                binding.btnShowAnswer.visibility = View.VISIBLE
                binding.layoutGrade.visibility = View.GONE
                // 언두 스택에 항목이 있을 때만 버튼 표시
                binding.btnUndo.visibility = if (undoStack.isEmpty()) View.GONE else View.VISIBLE
                binding.layoutDone.visibility = View.GONE
            }
            StudyUiState.ANSWER -> {
                binding.tvFront.visibility = View.VISIBLE
                binding.tvBack.visibility = View.VISIBLE
                binding.btnShowAnswer.visibility = View.GONE
                binding.layoutGrade.visibility = View.VISIBLE
                // 언두 스택에 항목이 있을 때만 버튼 표시
                binding.btnUndo.visibility = if (undoStack.isEmpty()) View.GONE else View.VISIBLE
                binding.layoutDone.visibility = View.GONE
            }
            StudyUiState.DONE -> {
                // 학습 완료 시 모든 학습 UI 숨기기
                binding.tvFront.visibility = View.GONE
                binding.tvBack.visibility = View.GONE
                binding.btnShowAnswer.visibility = View.GONE
                binding.layoutGrade.visibility = View.GONE
                binding.btnUndo.visibility = View.GONE  // 완료 시 언두 버튼 항상 숨기기
                binding.layoutDone.visibility = View.VISIBLE
            }
        }
    }

    // 카드 소진 여부 판단을 한 곳에서 처리
    // [변경] suspend 제거 → 코루틴 안에서 withContext로만 처리
    private fun showNextCardOrDone() {
        lifecycleScope.launch {
            // DB에서 조건 만족하는 카드를 그때그때 조회 (시간 기반)
            // 우선순위: LEARNING(nextReviewAt<=now) → REVIEW(nextReviewAt<=오늘자정) → NEW
            // 이유: LEARNING 카드가 정해진 시간(1분/10분)이 됐을 때 정확히 등장해야
            //       망각곡선 효과가 최대화됨
            val next = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val todayStart = startOfTodayMillis(now)

                // 1순위: 시간이 된 LEARNING 카드 (한도 무관)
                val learning = db.cardDao().getNextLearningCard(deckId, now)
                if (learning != null) return@withContext learning

                // [변경] 한도 체크 추가
                // 이유: dailyNewLimit, dailyReviewLimit 초과 시 해당 카드 스킵
                val limits = db.deckDao().getStudyLimits(deckId)
                val newDoneToday = db.reviewLogDao().countNewCardsToday(deckId, todayStart)
                val reviewDoneToday = db.reviewLogDao().countReviewCardsToday(deckId, todayStart)

                // 2순위: 오늘 REVIEW 카드 (한도 미초과 시에만)
                if (reviewDoneToday < limits.dailyReviewLimit) {
                    val review = db.cardDao().getNextReviewCard(deckId, todayStart)
                    if (review != null) return@withContext review
                }

                // 3순위: NEW 카드 (한도 미초과 시에만)
                if (newDoneToday < limits.dailyNewLimit) {
                    val new = db.cardDao().getNextNewCard(deckId)
                    if (new != null) return@withContext new
                }

                // 4순위: 시간 안 됐지만 대기 중인 LEARNING (조기 등장)
                // 사용자가 1분/10분 안에 모든 카드를 소진했을 때
                // 학습이 완료된 것처럼 잘못 끝나는 문제 방지
                db.cardDao().getNextPendingLearningCard(deckId)
            }

            if (next == null) {
                applyUiState(StudyUiState.DONE) // [변경] switchToDoneUi() → applyUiState()로 통일
                return@launch
            }
            currentCard = next
            refreshCardText()
            applyUiState(StudyUiState.QUESTION) // [변경] 개별 hide/show → applyUiState()로 통일
        }
    }

    private fun applyGrade(score: Int) {
        val card = currentCard ?: return

        // [변경] 큐 스냅샷 저장 제거 → 채점 전 카드 상태만 저장
        // 기존: 큐 4개 전체 스냅샷 + prevCard + logId
        // 변경: prevCard + logId 만 저장
        undoStack.addLast(
            UndoEntry(
                prevCard = card, // 점수를 적용하기 전 상태인 카드를 미리 백업
                insertedReviewLogId = null
            )
        )

        // UI 잠금
        // 사용자가 Again / Hard / Good / Easy 버튼을 빠르게 연타할 경우,
        // DB 업데이트가 끝나기 전에 다음 카드로 넘어가며
        // 여러 코루틴이 동시에 실행되어 상태가 꼬일 수 있음.
        //
        // 따라서:
        // - 버튼 입력을 잠그고
        // - 카드 이동, 상태 결정, UI 갱신을
        //   DB 반영이 끝난 이후 코루틴 내부에서만 수행하도록 구성함
        //
        // 참고: 작업스레드 이후의 코드는 잠시 중지되지만 이외의 다른 메인스레드 작업들은 중지되지 않는다
        setButtonsEnabled(false)

        lifecycleScope.launch {
            try {
                // [변경] applyGradeDb 전면 변경
                // 기존: resolveNextStatus()로 status만 결정 후 applySm2()로 DB 업데이트
                // 변경: resolveUpdatedCard()로 카드 전체 업데이트 상태 계산 (status + learningStep + nextReviewAt)
                // 이유: LEARNING 단계 step과 nextReviewAt을 함께 관리해야 시간 기반 재등장 가능
                val now = System.currentTimeMillis()
                val updatedCard = resolveUpdatedCard(card, score, now)

                var logId = -1L
                withContext(Dispatchers.IO) {
                    // DB 업데이트 + 로그 insert (여기서 로그 아이디가 생성됨)
                    db.cardDao().updateSm2(
                        id = card.id,
                        state = score,
                        status = updatedCard.status,
                        repetition = updatedCard.repetition,
                        intervalDays = updatedCard.intervalDays,
                        easeFactor = updatedCard.easeFactor,
                        nextReviewAt = updatedCard.nextReviewAt
                    )
                    // [변경] learningStep 별도 업데이트
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

                // 현재 카드를 언두 스택에서 임시로 빼뒀다가
                val lastUndo = undoStack.removeLast()
                // 리뷰 로그 아이디를 백업한 뒤 다시 넣기
                undoStack.addLast(
                    lastUndo.copy(insertedReviewLogId = logId)
                )

                // [변경] todayLearningQueue 재삽입 제거
                // 기존: nextStatus == CARD_LEARNING 이면 todayLearningQueue에 재삽입
                // 변경: nextReviewAt을 설정해두면 pollNextCard()가 시간이 됐을 때 자동으로 가져옴

                showNextCardOrDone()
                // [변경] showUndoButton() 제거 → applyUiState()에서 undoStack 기반으로 자동 처리

            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    // [변경] resolveNextCard → resolveUpdatedCard 로 이름 변경
    // 이유: "다음 카드"가 아니라 "현재 카드의 채점 후 상태"를 담고 있으므로
    private fun resolveUpdatedCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (card.status) {
            CARD_NEW, CARD_LEARNING -> resolveUpdatedLearningCard(card, score, now)
            CARD_REVIEW -> resolveUpdatedReviewCard(card, score, now)
            else -> card
        }
    }

    // [변경] NEW/LEARNING 카드 채점 처리
    // 기존: q < 3 이면 LEARNING, q >= 3 이면 REVIEW (단순 상태 변경)
    // 변경: step 기반으로 nextReviewAt 설정
    //   Again → step 0 초기화, 1분 후 재등장
    //   Hard  → 현재 step 유지, 같은 시간 후 재등장
    //   Good  → 다음 step으로 진행, 마지막 step이면 REVIEW 졸업
    //   Easy  → 즉시 REVIEW 졸업
    private fun resolveUpdatedLearningCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> { // Again → step 0, 1분 후
                card.copy(
                    status = CARD_LEARNING,
                    state = score,
                    learningStep = 0,
                    nextReviewAt = now + LEARNING_STEPS_MS[0]
                )
            }
            1 -> { // Hard → 현재 step 유지, 같은 시간
                val step = card.learningStep.coerceIn(0, LEARNING_STEPS_MS.lastIndex)
                card.copy(
                    status = CARD_LEARNING,
                    state = score,
                    learningStep = step,
                    nextReviewAt = now + LEARNING_STEPS_MS[step]
                )
            }
            2 -> { // Good → 다음 step, 마지막 step이면 REVIEW 졸업
                val nextStep = card.learningStep + 1
                if (nextStep >= LEARNING_STEPS_MS.size) {
                    val sm2 = applySm2(card, score)
                    card.copy(
                        status = CARD_REVIEW,
                        state = score,
                        learningStep = 0,
                        repetition = sm2.repetition,
                        intervalDays = sm2.intervalDays,
                        easeFactor = sm2.easeFactor,
                        nextReviewAt = sm2.nextReviewAt
                    )
                } else {
                    card.copy(
                        status = CARD_LEARNING,
                        state = score,
                        learningStep = nextStep,
                        nextReviewAt = now + LEARNING_STEPS_MS[nextStep]
                    )
                }
            }
            else -> { // Easy → 즉시 REVIEW 졸업
                val sm2 = applySm2(card, score)
                card.copy(
                    status = CARD_REVIEW,
                    state = score,
                    learningStep = 0,
                    repetition = sm2.repetition,
                    intervalDays = sm2.intervalDays,
                    easeFactor = sm2.easeFactor,
                    nextReviewAt = sm2.nextReviewAt
                )
            }
        }
    }

    // [변경] REVIEW 카드 채점 처리
    // 기존: q < 3 이면 LEARNING 강등, 나머지는 REVIEW 유지 (SM2 단일 계산)
    // 변경: Again이면 LEARNING 강등 + step 0 + 1분 후, 나머지는 난이도별 SM2 계산
    private fun resolveUpdatedReviewCard(card: CardEntity, score: Int, now: Long): CardEntity {
        return when (score) {
            0 -> { // Again → LEARNING 강등, step 0, 1분 후
                card.copy(
                    status = CARD_LEARNING,
                    state = score,
                    learningStep = 0,
                    nextReviewAt = now + LEARNING_STEPS_MS[0]
                )
            }
            else -> { // Hard/Good/Easy → SM2 계산 후 REVIEW 유지
                val sm2 = applySm2(card, score)
                card.copy(
                    status = CARD_REVIEW,
                    state = score,
                    learningStep = 0,
                    repetition = sm2.repetition,
                    intervalDays = sm2.intervalDays,
                    easeFactor = sm2.easeFactor,
                    nextReviewAt = sm2.nextReviewAt
                )
            }
        }
    }

    // [변경] undoLast 전면 변경
    // 기존: 큐 4개 스냅샷 복구 + carryLearningQueue 맨 앞에 prevCard 삽입 + showNextCardOrDone()
    // 변경: DB만 복구 + prevCard를 currentCard에 직접 설정해서 화면에 표시
    // 이유: 큐가 없으므로 큐 복구 불필요
    //       pollNextCard()를 거치지 않아야 시간 조건과 무관하게 정확한 카드 복구 가능
    //       (복구한 카드의 nextReviewAt이 미래일 수 있으므로)
    private fun undoLast() {
        if (undoStack.isEmpty()) return

        val undo = undoStack.removeLast()

        setButtonsEnabled(false)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1) 리뷰 로그 삭제
                    if (undo.insertedReviewLogId != null) {
                        db.reviewLogDao().deleteById(undo.insertedReviewLogId)
                    }
                    // 2) 카드 DB 상태 복구 (채점 전 상태로 되돌림)
                    db.cardDao().update(undo.prevCard)
                }

                // 3) 복구한 카드를 직접 화면에 표시 (pollNextCard 거치지 않음)
                currentCard = undo.prevCard
                refreshCardText()
                applyUiState(StudyUiState.QUESTION) // [변경] 개별 hide/show → applyUiState()로 통일

            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun toSm2Q(score: Int): Int { // state값을 sm2연산을 위한 q값(연산을 위한 score 값)으로 맵핑
        return when (score) {
            0 -> 0   // Again
            1 -> 3   // Hard
            2 -> 4   // Good
            else -> 5 // Easy
        }
    }

    // [변경] applySm2 난이도별 ef/interval 계산 분리
    // 기존: SM-2 공식으로 ef를 한꺼번에 계산 (난이도 구분 없음)
    // 변경: 실제 Anki처럼 난이도별로 ef와 interval을 다르게 계산
    //   Again → ef-0.20, interval=1
    //   Hard  → ef-0.15, interval*1.2
    //   Good  → ef 유지,  interval*ef
    //   Easy  → ef+0.15, interval*ef*1.3 (easyBonus)
    private fun applySm2(card: CardEntity, score: Int): Sm2Result {
        val q = toSm2Q(score)

        var ef = card.easeFactor
        var rep = card.repetition
        var interval = card.intervalDays

        when (q) {
            0 -> { // Again
                rep = 0
                interval = 1
                ef = (ef - 0.20).coerceAtLeast(1.3)
            }
            3 -> { // Hard
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> (interval * 1.2).toInt().coerceAtLeast(1)
                }
                ef = (ef - 0.15).coerceAtLeast(1.3)
            }
            4 -> { // Good
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> kotlin.math.round(interval * ef).toInt().coerceAtLeast(1)
                }
                // ef 변화 없음
            }
            else -> { // Easy
                rep += 1
                interval = when (rep) {
                    1 -> 1
                    2 -> 6
                    else -> kotlin.math.round(interval * ef * 1.3).toInt().coerceAtLeast(1)
                }
                ef = (ef + 0.15).coerceAtLeast(1.3)
            }
        }

        val nowMillis = System.currentTimeMillis() // 현재 시각
        val nextAt = addDaysAtStartOfDay(nowMillis, interval) // interval일 후 "자정"을 nextReviewAt으로 저장

        return Sm2Result(rep, interval, ef, nextAt)
    } // q값을 바탕으로
    // repetition, intervalDays, easeFactor, nextReviewAt 갱신

    // 카드 텍스트 갱신
    private fun refreshCardText() {
        val card = currentCard ?: return
        binding.tvFront.text = card.front
        binding.tvBack.text = card.back
    }

    // 카드 보이기
    private fun showCard() {
        binding.tvBack.visibility = View.VISIBLE
    }

    // consistency를 위한 카드 난이도 버튼 활성화/비활성화
    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnAgain.isEnabled = enabled
        binding.btnHard.isEnabled = enabled
        binding.btnGood.isEnabled = enabled
        binding.btnEasy.isEnabled = enabled
        binding.btnShowAnswer.isEnabled = enabled
        binding.btnUndo.isEnabled = enabled
    }

    private fun startOfTodayMillis(nowMillis: Long): Long { // 오늘 00:00(ms) 반환
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun addDaysAtStartOfDay(nowMillis: Long, days: Int): Long { // 오늘 자정 + days일 후를 반환
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfTodayMillis(nowMillis)
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }
}
*/
