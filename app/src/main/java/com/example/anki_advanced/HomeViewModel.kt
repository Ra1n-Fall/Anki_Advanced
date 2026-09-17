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

// 홈 화면(덱 목록)의 데이터를 담당. "DB 조회 + 상태 보관"은 여기, "화면 그리기 + 다이얼로그"는 HomeScreen이 맡는다.
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체가 공유하는 AppDatabase 싱글턴 인스턴스.
    private val db = AppDatabase.getInstance(application)

    // [문법] MutableStateFlow / StateFlow 짝 패턴 (이 파일에서 반복적으로 나옴)
    //   _decks(밑줄 O): 이 클래스 안에서만 값을 바꿀 수 있는 "쓰기용" 통.
    //   decks(밑줄 X): 바깥(HomeScreen)에는 읽기 전용으로만 노출하는 "읽기용" 통로.
    //   HomeScreen이 decks.collectAsState()로 구독해두면, _decks.value가 바뀔 때마다
    //   화면이 자동으로 다시 그려진다 (직접 "다시 그려라" 호출 안 해도 됨).
    private val _decks = MutableStateFlow<List<DeckUi>>(emptyList())
    val decks: StateFlow<List<DeckUi>> = _decks.asStateFlow()

    // 오늘 학습한 카드 수. 홈 화면 상단 "오늘 N장 완료" 표시용.
    private val _todayStudied = MutableStateFlow(0)
    val todayStudied: StateFlow<Int> = _todayStudied.asStateFlow()

    // 오늘 진도율 (0.0 ~ 1.0). 프로그레스 바 표시용.
    // 계산식: 오늘 완료 / (오늘 완료 + 아직 남은 카드 수)
    private val _todayProgress = MutableStateFlow(0f)
    val todayProgress: StateFlow<Float> = _todayProgress.asStateFlow()

    // 지금까지 누적으로 공부한 전체 카드 수. "총 암기한 카드" 통계용.
    private val _totalCards = MutableStateFlow(0)
    val totalCards: StateFlow<Int> = _totalCards.asStateFlow()

    // 연속 학습 스트릭(며칠 연속 공부했는지). "연속 학습 스트릭" 통계용.
    private val _streakDays = MutableStateFlow(0)
    val streakDays: StateFlow<Int> = _streakDays.asStateFlow()

    // [문법] init { ... } 블록
    //   생성자가 실행될 때(=이 ViewModel이 처음 만들어질 때) 딱 한 번 자동으로 실행되는 코드.
    //   여기서는 "ViewModel이 태어나자마자 덱 목록을 한 번 불러와라"는 초기화 작업.
    init {
        loadDecks()
    }

    // 덱 목록 + 각종 통계를 DB에서 새로 읽어와 화면 상태를 갱신. (새로고침 함수)
    fun loadDecks() {
        // [문법] viewModelScope.launch { ... }
        //   ViewModel이 살아있는 동안 유지되는 코루틴 범위. 화면이 없어지면 자동으로 취소된다.
        viewModelScope.launch {
            val now = System.currentTimeMillis()         // 현재 시각 (ms)
            val todayStart = startOfTodayMillis(now)     // 오늘 00:00:00 시각 (ms)

            // [문법] withContext(Dispatchers.IO) { ... }
            //   DB 조회처럼 오래 걸리는 작업을 IO 전용 스레드에서 실행. 끝나면 자동으로 원래 스레드로 복귀.
            val all = withContext(Dispatchers.IO) { db.deckDao().getAll() }

            var totalStudied = 0  // 모든 덱을 통틀어 "오늘 완료한 카드 수" 합계
            var totalDue = 0      // 모든 덱을 통틀어 "아직 남은 카드 수" 합계

            // [문법] list.map { e -> ... 마지막_식 }
            //   리스트의 원소 하나하나(e)를 순회하면서, 블록 안의 마지막 식을 결과로 모아
            //   새로운 리스트를 만들어내는 함수. 여기서는 DeckEntity 리스트를 DeckUi 리스트로 변환한다.
            val deckUiList = all.map { e ->

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

                // [문법] completionConfig?.takeIf { it.isActive }
                //   설정이 있고(?.), 그중에서도 isActive가 true인 것만(takeIf) 통과시키고,
                //   나머지 경우엔 전부 null이 된다. "완주 모드가 켜진 덱만" 걸러내는 관용구.
                val activeConfig = completionConfig?.takeIf { it.isActive }
                DeckUi(
                    id = e.id,
                    name = e.name,
                    newCount = newCount,
                    learnCount = learnCount,
                    reviewCount = reviewCount,
                    lastStudiedAt = lastStudiedAt,
                    completionModeEndAt = activeConfig?.modeEndAt,
                    // [문법] activeConfig?.let { ... }
                    //   activeConfig가 null이 아닐 때만 { } 블록을 실행해서 그 결과를 쓰고,
                    //   null이면 전체가 그냥 null이 됨. it은 "null 아님이 확인된 activeConfig"를 가리킴.
                    completionTargetDays = activeConfig?.let {
                        (it.targetPeriodMs / 86_400_000L).toInt().coerceAtLeast(1)
                    }
                )
                // map 블록의 마지막 식(DeckUi(...))이 이 덱 하나의 변환 결과가 되고,
                // map은 전체 결과를 모아 List<DeckUi>로 돌려준다.
            }

            _decks.value = deckUiList           // 덱 목록 갱신 → HomeScreen 자동 재구성
            _todayStudied.value = totalStudied  // 오늘 완료 수 갱신

            // 전체 누적 학습 카드 수
            _totalCards.value = withContext(Dispatchers.IO) { db.reviewLogDao().countAll() }

            // 연속 학습 스트릭 계산
            val dayKeys = withContext(Dispatchers.IO) { db.reviewLogDao().getAllStudyDayKeys() }
            _streakDays.value = calculateStreak(dayKeys)

            val total = totalStudied + totalDue
            // 홈 화면 진행률 바의 실제 계산식은 여기 한 줄이다.
            // 여기서 만든 값이 todayProgress가 되어 HomeScreen으로 전달되므로,
            // 진행률 바가 이상하게 보이면 이 식부터 확인하면 된다.
            // [문법] if (조건) A else B → if를 "값을 만드는 식"으로 사용.
            _todayProgress.value = if (total > 0) totalStudied.toFloat() / total.toFloat() else 0f
        }
    }

    // 새 덱을 만들고, 목록을 새로고침한다. (다이얼로그 표시 자체는 HomeScreen이 담당)
    fun addDeck(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deckDao().insert(DeckEntity(name = name)) // DB에 새 덱 추가
            }
            loadDecks() // 목록 새로 불러와서 화면 갱신
        }
    }

    // 완주 모드를 켠다: 설정을 저장하고 덱 목록을 새로고침해서 배지가 바로 보이게 한다.
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

    // 완주 모드를 끈다 (설정 자체를 삭제).
    fun deactivateCompletionMode(deckId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.completionModeDao().delete(deckId)
            }
            loadDecks()
        }
    }

    // 덱을 삭제하고 목록을 새로고침한다.
    fun deleteDeck(deckId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deckDao().deleteById(deckId) // DB에서 해당 덱 삭제
            }
            loadDecks() // 목록 새로 불러와서 화면 갱신
        }
    }

    // 연속 학습 스트릭 계산.
    // dayKeys: 학습한 날짜들의 day key(ms / 86400000) 목록, 최신순(DESC) 정렬로 들어온다.
    // "오늘" 또는 "어제"부터 시작해서 하루도 안 끊기고 연속된 날 수를 센다.
    private fun calculateStreak(dayKeys: List<Long>): Int {
        if (dayKeys.isEmpty()) return 0
        val todayKey = System.currentTimeMillis() / 86_400_000L
        // 오늘 이미 공부했으면 오늘부터, 아직 안 했으면 어제부터 세기 시작
        // (아직 오늘 공부 안 했다고 스트릭이 0으로 끊기면 안 되니까)
        val start = if (dayKeys.first() == todayKey) todayKey else todayKey - 1
        var streak = 0
        var expected = start
        // [문법] for (dayKey in dayKeys) { ... }  → 리스트를 하나씩 순회하는 기본 반복문.
        for (dayKey in dayKeys) {
            if (dayKey == expected) {
                streak++
                expected--       // 하루 전 날짜를 기대값으로 갱신
            } else {
                break             // 연속이 끊기면 더 볼 필요 없이 반복 종료
            }
        }
        return streak
    }

    // 주어진 시각이 속한 "오늘"의 자정(00:00:00.000)을 ms로 반환.
    // [문법] Calendar.getInstance() + cal.set(...)
    //   자바/코틀린에서 날짜·시간을 다루는 오래된 표준 API. timeInMillis에 원하는 시각을 넣고,
    //   시/분/초/밀리초를 각각 0으로 세팅하면 "그 날의 자정"이 만들어진다.
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
