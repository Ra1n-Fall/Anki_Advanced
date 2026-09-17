package com.example.anki_advanced.completion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anki_advanced.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "완주 모드 설정" 화면(CompletionModeSetupScreen)의 상태를 들고 있는 ViewModel.
// 화면은 이 클래스가 들고 있는 StateFlow 값만 구독해서 그리고, DB 접근/계산은 전부 여기서 처리한다.
//
// [문법] class X(application: Application) : AndroidViewModel(application)
//   AndroidViewModel은 Application(앱 전체 컨텍스트)을 필요로 하는 ViewModel의 기본 클래스.
//   화면(Activity/Compose)이 회전되거나 다시 그려져도 이 객체는 안 죽고 값을 유지해준다.
class CompletionModeSetupViewModel(application: Application) : AndroidViewModel(application) {

    // 모든 화면이 공유하는 AppDatabase 싱글턴 인스턴스를 받아온다.
    private val db = AppDatabase.getInstance(application)

    // [문법] MutableStateFlow / StateFlow 짝 패턴
    //   _totalCards(밑줄 붙은 이름)는 이 클래스 안에서만 값을 바꿀 수 있는 "쓰기용".
    //   totalCards(밑줄 없는 이름)는 바깥(화면)에는 읽기 전용으로 노출하는 "읽기용".
    //   화면은 totalCards.collectAsState()로 구독하면, 값이 바뀔 때마다 자동으로 다시 그려진다.
    private val _totalCards = MutableStateFlow(0)
    val totalCards: StateFlow<Int> = _totalCards.asStateFlow()

    // 이미 완주 모드가 켜져있는 덱이면(수정 모드) 기존 마감 시각이 들어있고, 아니면 null.
    private val _existingEndAt = MutableStateFlow<Long?>(null)
    val existingEndAt: StateFlow<Long?> = _existingEndAt.asStateFlow()

    // 화면이 열릴 때 호출: 이 덱의 카드 수 + 기존 완주 모드 설정을 불러와 상태를 채운다.
    // [문법] viewModelScope.launch { ... }
    //   코루틴을 하나 띄워서 그 안의 코드를 비동기로 실행한다. ViewModel이 사라지면
    //   이 코루틴도 자동으로 같이 취소되므로, 화면이 꺼졌는데 작업만 계속 도는 걸 막아준다.
    fun load(deckId: Long) {
        viewModelScope.launch {
            // [문법] withContext(Dispatchers.IO) { ... }
            //   DB 조회처럼 시간이 걸리는 작업을 "IO 전용 스레드"에서 실행하도록 전환.
            //   이 블록이 끝나면 자동으로 원래 스레드(메인)로 돌아온다.
            val count = withContext(Dispatchers.IO) {
                db.cardDao().countAllCards(deckId)
            }
            _totalCards.value = count

            val config = withContext(Dispatchers.IO) {
                db.completionModeDao().getConfig(deckId)
            }
            // [문법] config?.takeIf { it.isActive }?.modeEndAt
            //   ?. 은 "안전 호출(safe call)": config가 null이면 그 뒤 전체가 그냥 null이 되고 에러 안 남.
            //   takeIf { 조건 } 은 조건이 true면 자기 자신을 그대로 반환하고, false면 null로 바꿔버림.
            //   즉 "설정이 있고 + 그 설정이 활성 상태일 때만" 마감 시각을 꺼내온다는 뜻.
            _existingEndAt.value = config?.takeIf { it.isActive }?.modeEndAt
        }
    }

    // 사용자가 캘린더에서 마감일을 고르고 저장을 누르면 호출.
    // onDone: () -> Unit 은 "저장이 끝난 뒤 호출할 콜백 함수"를 파라미터로 받는다는 뜻.
    fun save(deckId: Long, endDateMs: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // 목표 기간 = 마감시각 - 지금. 최소 하루(86_400_000ms)는 보장 (너무 짧으면 계산이 이상해지므로).
            // [문법] .coerceAtLeast(x) → 값이 x보다 작으면 x로, 크면 그대로 유지 (하한선 고정)
            val targetPeriodMs = (endDateMs - now).coerceAtLeast(86_400_000L)
            withContext(Dispatchers.IO) {
                db.completionModeDao().upsert(
                    CompletionModeConfigEntity(
                        deckId = deckId,
                        targetPeriodMs = targetPeriodMs,
                        modeStartAt = now,
                        isActive = true
                    )
                )
            }
            // 저장이 끝나면 메인 스레드에서 콜백 실행 (화면 전환 등 UI 작업은 메인 스레드에서 해야 함)
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
