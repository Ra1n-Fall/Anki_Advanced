package com.example.anki_advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anki_advanced.completion.CompletionModeConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// DB 조회·상태 보관은 HomeViewModel, 화면 그리기·다이얼로그는 HomeScreen으로 분리
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    // application = 앱 전체 Context, DB 생성에 필요

    // DB 인스턴스는 AppDatabase 싱글턴을 공유한다 (화면마다 따로 만들지 않음)
    private val db = AppDatabase.getInstance(application)

    //  HomeScreen이 collectAsState()로 구독
    //       값이 바뀌면 Compose가 자동으로 화면을 다시 그림 (notify 불필요)
    private val _decks = MutableStateFlow<List<DeckUi>>(emptyList())
    // _decks = 내부에서만 값을 바꿀 수 있는 StateFlow (쓰기 가능)
    val decks: StateFlow<List<DeckUi>> = _decks.asStateFlow()
    // decks = 외부(HomeScreen)에는 읽기 전용으로 노출 asStateFlow를 통해

    // 오늘 학습한 카드 수
    // 히어로 카드의 "오늘 N장 완료" 표시를 위해 추가
    private val _todayStudied = MutableStateFlow(0)
    val todayStudied: StateFlow<Int> = _todayStudied.asStateFlow()

    //  오늘 진도율 (0.0 ~ 1.0)
    // 히어로 카드의 프로그레스바 표시를 위해 추가
    //       계산식: 오늘 완료 / (오늘 완료 + 남은 카드 수)
    private val _todayProgress = MutableStateFlow(0f)
    val todayProgress: StateFlow<Float> = _todayProgress.asStateFlow()

    // 전체 누적 학습 카드 수 — QuickStats "총 암기한 카드" 표시용
    private val _totalCards = MutableStateFlow(0)
    val totalCards: StateFlow<Int> = _totalCards.asStateFlow()

    // 연속 학습 스트릭 (일) — QuickStats "연속 학습 스트릭" 표시용
    private val _streakDays = MutableStateFlow(0)
    val streakDays: StateFlow<Int> = _streakDays.asStateFlow()

    // [변경] isFirstLoad 플래그 제거 → init 블록으로 대체
    // 기존: onResume()에서 isFirstLoad 플래그로 onCreate 중복 호출 방지
    //       private var isFirstLoad = true
    //       override fun onResume() { if (isFirstLoad) { isFirstLoad = false; return } ... }
    // 변경: ViewModel 생성 시 init에서 한 번만 호출
    //       onResume 역할은 HomeScreen의 DisposableEffect(ON_RESUME)가 대신함
    init {
        loadDecks()
    }

    fun loadDecks() {
        viewModelScope.launch {
            // viewModelScope = ViewModel이 살아있는 동안 유지되는 코루틴 스코프
            val now = System.currentTimeMillis()         // 현재 시각 (ms)
            val todayStart = startOfTodayMillis(now)     // 오늘 00:00:00 (ms)

            val all = withContext(Dispatchers.IO) { db.deckDao().getAll() }
            // IO 스레드에서 전체 덱 목록 조회 → List<DeckEntity>

            var totalStudied = 0  // 전체 덱의 오늘 완료 카드 수 합산용
            var totalDue = 0      // 전체 덱의 남은 카드 수 합산용

            val deckUiList = all.map { e ->
                // 리스트에서 하나씩 꺼내어 작업 수행 후 리스트로 합쳐서 반환

                val newCount = withContext(Dispatchers.IO) {
                    db.cardDao().countNewCards(e.id)      // 이 덱의 NEW 카드 수
                }
                val learnCount = withContext(Dispatchers.IO) {
                    db.cardDao().countLearningCards(e.id) // 이 덱의 LEARNING 카드 수
                }
                val reviewCount = withContext(Dispatchers.IO) {
                    db.cardDao().countReviewCards(e.id, todayStart) // 이 덱의 오늘 REVIEW 카드 수
                }
                val studied = withContext(Dispatchers.IO) {
                    db.reviewLogDao().countToday(e.id, todayStart, now) // 이 덱의 오늘 완료 카드 수
                }
                val lastStudiedAt = withContext(Dispatchers.IO) {
                    db.reviewLogDao().getLastStudied(e.id) // 이 덱의 마지막 학습 시각
                }
                val completionConfig = withContext(Dispatchers.IO) {
                    db.completionModeDao().getConfig(e.id)
                }

                totalStudied += studied                          // 전체 완료 수에 누적
                totalDue += newCount + learnCount + reviewCount  // 전체 남은 수에 누적

                val activeConfig = completionConfig?.takeIf { it.isActive }
                DeckUi(
                    id = e.id,
                    name = e.name,
                    newCount = newCount,
                    learnCount = learnCount,
                    reviewCount = reviewCount,
                    lastStudiedAt = lastStudiedAt,
                    completionModeEndAt = activeConfig?.modeEndAt,
                    completionTargetDays = activeConfig?.let {
                        (it.targetPeriodMs / 86_400_000L).toInt().coerceAtLeast(1)
                    }
                )
                // 이 줄이 이 덱의 변환 결과 → map이 모아서 List<DeckUi>로 만듦
            }

            _decks.value = deckUiList        // 덱 목록 갱신 → HomeScreen 자동 재구성
            _todayStudied.value = totalStudied  // 오늘 완료 수 갱신

            // 전체 누적 학습 카드 수
            _totalCards.value = withContext(Dispatchers.IO) { db.reviewLogDao().countAll() }

            // 연속 학습 스트릭 계산
            val dayKeys = withContext(Dispatchers.IO) { db.reviewLogDao().getAllStudyDayKeys() }
            _streakDays.value = calculateStreak(dayKeys)

            val total = totalStudied + totalDue
            // 홈 화면 진행률 바의 원본 계산식은 여기다.
            // 여기서 만든 값이 `todayProgress`가 되고, HomeScreen으로 전달된다.
            // 따라서 바가 기대와 다르게 보이면 이 계산식을 먼저 확인하면 된다.
            _todayProgress.value = if (total > 0) totalStudied.toFloat() / total.toFloat() else 0f
            // 진도율 = 완료 / (완료 + 남은), 카드가 하나도 없으면 0f
        }
    }

    // [변경] showAddDeckDialog() → addDeck()
    // 기존: HomeActivity.showAddDeckDialog()
    //       AlertDialog 생성, EditText 입력, items 추가, adapter.notifyItemInserted() 호출
    //       UI 로직(다이얼로그)과 DB 로직이 섞여 있었음
    // 변경: DB insert + loadDecks() 재호출만 담당
    //       다이얼로그 표시는 HomeScreen의 AddDeckDialog()가 담당
    fun addDeck(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deckDao().insert(DeckEntity(name = name)) // DB에 새 덱 추가
            }
            loadDecks() // 목록 새로 불러와서 화면 갱신
        }
    }

    // [변경] showDeleteDeckDialog() → deleteDeck()
    // 기존: HomeActivity.showDeleteDeckDialog()
    //       AlertDialog 생성, 확인 시 DB 삭제 + items.removeAt() + adapter.notifyItemRemoved()
    // 변경: DB 삭제 + loadDecks() 재호출만 담당
    //       삭제 확인 다이얼로그는 HomeScreen의 DeleteDeckDialog()가 담당
    /** 완주 모드 활성화 — 설정 저장 후 덱 목록 갱신 */
    fun activateCompletionMode(
        deckId: Long,
        targetPeriodMs: Long,
        windowStartHour: Int = 0,
        windowEndHour: Int = 24
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                db.completionModeDao().upsert(
                    CompletionModeConfigEntity(
                        deckId          = deckId,
                        targetPeriodMs  = targetPeriodMs,
                        windowStartHour = windowStartHour,
                        windowEndHour   = windowEndHour,
                        modeStartAt     = now,
                        isActive        = true
                    )
                )
            }
            loadDecks()
        }
    }

    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.completionModeDao().delete(deckId)
            }
            loadDecks()
        }
    }

    fun deleteDeck(deckId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deckDao().deleteById(deckId) // DB에서 해당 덱 삭제
            }
            loadDecks() // 목록 새로 불러와서 화면 갱신
        }
    }

    // 연속 학습 스트릭 계산
    // dayKeys: 학습한 날짜의 day key (ms / 86400000) 목록 — DESC 정렬
    // 오늘 또는 어제부터 시작해서 연속된 날 수를 반환
    private fun calculateStreak(dayKeys: List<Long>): Int {
        if (dayKeys.isEmpty()) return 0
        val todayKey = System.currentTimeMillis() / 86_400_000L
        // 오늘 학습했으면 오늘부터, 아니면 어제부터 카운트
        val start = if (dayKeys.first() == todayKey) todayKey else todayKey - 1
        var streak = 0
        var expected = start
        for (dayKey in dayKeys) {
            if (dayKey == expected) {
                streak++
                expected--
            } else {
                break
            }
        }
        return streak
    }

    // [유지] startOfTodayMillis()
    // 기존과 동일 — 오늘 00:00:00(ms)를 반환
    // HomeActivity에 있던 함수를 ViewModel로 이동
    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)  // 시 → 0
        cal.set(Calendar.MINUTE, 0)        // 분 → 0
        cal.set(Calendar.SECOND, 0)        // 초 → 0
        cal.set(Calendar.MILLISECOND, 0)   // 밀리초 → 0
        return cal.timeInMillis            // 오늘 자정(ms) 반환
    }
}
