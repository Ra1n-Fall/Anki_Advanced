package com.example.anki_advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [변경] DeckManageActivity → DeckManageViewModel
// 기존: AppCompatActivity 상속, lifecycleScope + items: MutableList<CardUi> + adapter.notify~ 로 UI 직접 제어
//       다이얼로그도 Activity 안에서 AlertDialog.Builder로 직접 생성
// 변경: AndroidViewModel 상속, 단일 UiState StateFlow로 모든 상태 노출 → DeckManageScreen이 구독
// 이유: UI 로직(Compose)과 비즈니스 로직 분리, 입력/다이얼로그 상태까지 ViewModel에서 통합 관리

// [변경] 개별 멤버 변수 → DeckManageUiState 단일 상태 객체
// 기존: DeckManageActivity의
//       - var deckId: Long
//       - val items = mutableListOf<CardUi>()
//       - lateinit var adapter: CardAdapter
//       등 개별 멤버 변수로 관리
// 변경: data class 하나로 묶어 관리
//       - copy()로 원하는 필드만 변경
//       - Screen은 하나의 StateFlow만 구독하면 됨
//       - allCards: 전체 카드 목록 (DB 기준)
//       - visibleCards: 검색 필터가 적용된 화면에 표시될 카드 목록
data class DeckManageUiState(
    val deckId: Long = -1L,
    val deckName: String = "영어 단어",
    val allCards: List<CardUi> = emptyList(),    // 필터 전 전체 목록 (검색 초기화 시 복원용)
    val visibleCards: List<CardUi> = emptyList(), // 화면에 표시되는 목록 (검색 필터 적용)
    val frontText: String = "",
    val backText: String = "",
    val tagsText: String = "",
    val searchQuery: String = "",
    val showDeleteDialog: Boolean = false,
    val cardToDelete: CardUi? = null,
    val showEditDialog: Boolean = false,
    val cardToEdit: CardUi? = null,
    val editFront: String = "",
    val editBack: String = "",
    val editTags: String = ""
)

class DeckManageViewModel(application: Application) : AndroidViewModel(application) {

    // [유지] DB 인스턴스 생성 방식 동일
    // application Context로 생성하므로 Activity 생명주기에 독립적
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    // [변경] items: MutableList + adapter + 개별 변수 → _uiState: MutableStateFlow<DeckManageUiState>
    // 기존: items.clear() + for loop + adapter.notifyDataSetChanged() 로 화면 갱신
    // 변경: _uiState.update { } 로 상태 갱신 → DeckManageScreen이 collectAsState()로 구독해 자동 재구성
    // _uiState: ViewModel 내부에서만 쓰기 가능
    // uiState: Screen에는 읽기 전용으로 노출
    private val _uiState = MutableStateFlow(DeckManageUiState())
    val uiState: StateFlow<DeckManageUiState> = _uiState.asStateFlow()

    // [변경] Activity onCreate 직접 호출 → initialize()로 분리
    // 기존: Activity onCreate에서 intent.getLongExtra("deck_id")로 deckId 받아
    //       바로 initialLoadFromDb() 호출
    // 변경: Screen의 LaunchedEffect(deckId, deckName)에서 initialize() 호출
    //       이미 같은 덱이 로딩된 경우 중복 DB 조회를 방지하는 early return 포함
    fun initialize(deckId: Long, deckName: String) {
        val current = _uiState.value
        // 같은 덱이고 이미 카드가 로딩된 경우 → 재조회 불필요 (화면 회전 등으로 재진입 시 방지)
        if (current.deckId == deckId && current.deckName == deckName && current.allCards.isNotEmpty()) return

        // deckId / deckName 먼저 업데이트 후 loadCards() 호출
        _uiState.update {
            it.copy(
                deckId = deckId,
                deckName = deckName
            )
        }
        loadCards()
    }

    // [변경] initialLoadFromDb() → loadCards()
    // 기존: items.clear() 후 for loop으로 CardUi 변환 + adapter.notifyDataSetChanged()
    // 변경: map으로 한 번에 변환 후 _uiState.update로 allCards / visibleCards 동시 갱신
    //       visibleCards = 현재 검색어(searchQuery)로 필터링된 결과로 함께 갱신
    fun loadCards() {
        val deckId = _uiState.value.deckId
        if (deckId < 0L) return  // initialize() 전에 호출되면 무시
        viewModelScope.launch {
            // IO 스레드에서 DB 조회
            val all = withContext(Dispatchers.IO) { db.cardDao().getByDeck(deckId) }
            // CardEntity → CardUi 변환 (UI 표시용 모델)
            val cards = all.map { CardUi(it.id, it.front, it.back, it.tags, it.state, it.status) }
            _uiState.update { state ->
                state.copy(
                    allCards = cards,
                    visibleCards = filterCards(cards, state.searchQuery)
                    // 재로딩 시에도 현재 검색어 유지
                )
            }
        }
    }

    // [변경] binding.etFront/etBack/etTags 직접 읽기 → StateFlow 이벤트 함수로 분리
    // 기존: btnAdd.setOnClickListener 안에서 binding.etFront.text.toString().trim() 직접 읽음
    // 변경: 텍스트 변경 시마다 이 함수들로 UiState에 반영
    //       onAddCard() 호출 시점에 UiState.frontText / backText / tagsText 에서 값을 읽음
    //       → ViewModel이 View 참조를 갖지 않아도 됨
    fun onFrontTextChange(value: String) {
        _uiState.update { it.copy(frontText = value) }
    }

    fun onBackTextChange(value: String) {
        _uiState.update { it.copy(backText = value) }
    }

    fun onTagsTextChange(value: String) {
        _uiState.update { it.copy(tagsText = value) }
    }

    // [변경] 검색 기능 추가 (레거시에 없던 기능)
    // 기존: 검색 없음 — 전체 목록만 표시
    // 변경: searchQuery 변경 시 allCards에서 필터링해 visibleCards 즉시 갱신
    //       allCards는 유지하므로 검색어를 지우면 전체 목록으로 복원됨
    fun onSearchQueryChange(value: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = value,
                visibleCards = filterCards(state.allCards, value)
            )
        }
    }

    // [변경] insertCardAndUpdateUi() → onAddCard()
    // 기존: items.add(0, CardUi(...)) + adapter.notifyItemInserted(0) + rvCards.scrollToPosition(0)
    //       입력 필드 초기화는 binding.etFront.setText("") 등으로 직접 처리
    // 변경: DB insert 후 allCards 앞에 새 카드 추가
    //       입력 필드 초기화(frontText="", ...)까지 _uiState.update 한 번으로 처리
    fun onAddCard() {
        val state = _uiState.value
        // 유효성 검사: deckId 미설정 또는 앞면/뒷면이 비어있으면 무시
        if (state.deckId < 0L || state.frontText.isBlank() || state.backText.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                deckId = state.deckId,
                front = state.frontText.trim(),
                back = state.backText.trim(),
                tags = state.tagsText.trim(),
                state = 0,
                status = CARD_NEW  // 새 카드는 항상 NEW 상태로 시작
            )
            // IO 스레드에서 DB insert → 생성된 row id 반환
            val newId = withContext(Dispatchers.IO) { db.cardDao().insert(entity) }
            val newCard = CardUi(newId, entity.front, entity.back, entity.tags, 0, CARD_NEW)
            _uiState.update { current ->
                val updatedCards = listOf(newCard) + current.allCards  // 목록 맨 앞에 추가
                current.copy(
                    allCards = updatedCards,
                    visibleCards = filterCards(updatedCards, current.searchQuery),
                    frontText = "",  // 추가 완료 후 입력 필드 초기화
                    backText = "",
                    tagsText = ""
                )
            }
        }
    }

    // [변경] showDeleteDialog() → onDeleteRequest() + dismissDeleteDialog() + confirmDeleteCard()로 분리
    // 기존: showDeleteDialog()에서 AlertDialog.Builder로 다이얼로그를 직접 생성하고
    //       확인 버튼 클릭 시 deleteCardAndUpdateUi() 호출
    // 변경: 다이얼로그의 표시/닫기/확인을 각각 함수로 분리
    //       다이얼로그 UI는 DeckManageScreen의 if (uiState.showDeleteDialog) 블록이 담당

    // 삭제 요청: 삭제할 카드를 UiState에 저장하고 다이얼로그 표시 플래그를 true로
    fun onDeleteRequest(card: CardUi) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                cardToDelete = card
            )
        }
    }

    // 삭제 취소: 다이얼로그 닫기, cardToDelete 초기화
    fun dismissDeleteDialog() {
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                cardToDelete = null
            )
        }
    }

    // [변경] deleteCardAndUpdateUi() → confirmDeleteCard()
    // 기존: items.indexOfFirst { it.id == card.id } → items.removeAt(idx) + adapter.notifyItemRemoved(idx)
    // 변경: filter로 해당 id를 제외한 새 리스트를 만들어 _uiState.update로 반영
    //       allCards와 visibleCards 모두 갱신
    fun confirmDeleteCard() {
        val card = _uiState.value.cardToDelete ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.cardDao().deleteById(card.id)
            }
            _uiState.update { state ->
                val updatedCards = state.allCards.filter { it.id != card.id }
                state.copy(
                    allCards = updatedCards,
                    visibleCards = filterCards(updatedCards, state.searchQuery),
                    showDeleteDialog = false,
                    cardToDelete = null
                )
            }
        }
    }

    // [변경] showEditDialog() → onEditRequest() + confirmEditCard() + dismissEditDialog()로 분리
    // 기존: showEditDialog()에서 AlertDialog.Builder + EditText.setText()로 기존 값을 채우고
    //       확인 버튼 클릭 시 updateCardAndUpdateUi() 호출
    // 변경: 수정 대상 카드와 현재 입력값을 UiState에 저장
    //       다이얼로그 UI는 DeckManageScreen의 if (uiState.showEditDialog) 블록이 담당

    // 수정 요청: 수정할 카드와 현재 값을 UiState에 저장, 다이얼로그 표시 플래그를 true로
    fun onEditRequest(card: CardUi) {
        _uiState.update {
            it.copy(
                showEditDialog = true,
                cardToEdit = card,
                editFront = card.front,  // 다이얼로그 초기값을 현재 카드 내용으로 채움
                editBack = card.back,
                editTags = card.tags
            )
        }
    }

    // 수정 다이얼로그의 각 입력 필드 변경 이벤트
    fun onEditFrontChange(value: String) {
        _uiState.update { it.copy(editFront = value) }
    }

    fun onEditBackChange(value: String) {
        _uiState.update { it.copy(editBack = value) }
    }

    fun onEditTagsChange(value: String) {
        _uiState.update { it.copy(editTags = value) }
    }

    // 수정 취소: 다이얼로그 닫기, cardToEdit 초기화
    fun dismissEditDialog() {
        _uiState.update {
            it.copy(
                showEditDialog = false,
                cardToEdit = null
            )
        }
    }

    // [변경] updateCardAndUpdateUi() → confirmEditCard()
    // 기존: items[position] = CardUi(...) + adapter.notifyItemChanged(position)
    //       position 인덱스로 직접 접근 → 리스트가 변경되면 인덱스 불일치 위험
    // 변경: map으로 id가 일치하는 카드만 교체한 새 리스트를 _uiState.update로 반영
    //       id 기반이므로 인덱스 불일치 문제 없음
    fun confirmEditCard() {
        val state = _uiState.value
        val target = state.cardToEdit ?: return
        if (state.deckId < 0L || state.editFront.isBlank() || state.editBack.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                id = target.id,            // 기존 id 유지
                deckId = state.deckId,
                front = state.editFront.trim(),
                back = state.editBack.trim(),
                tags = state.editTags.trim(),
                state = target.state,       // 채점 점수 유지 (수정해도 학습 상태는 그대로)
                status = target.status      // NEW/LEARNING/REVIEW 상태 유지
            )
            withContext(Dispatchers.IO) { db.cardDao().update(entity) }
            _uiState.update { current ->
                // allCards에서 수정된 카드만 교체한 새 리스트 생성
                val updatedCards = current.allCards.map {
                    if (it.id == target.id) {
                        CardUi(
                            id = target.id,
                            front = entity.front,
                            back = entity.back,
                            tags = entity.tags,
                            state = target.state,
                            status = target.status
                        )
                    } else {
                        it  // 나머지 카드는 그대로
                    }
                }
                current.copy(
                    allCards = updatedCards,
                    visibleCards = filterCards(updatedCards, current.searchQuery),
                    showEditDialog = false,
                    cardToEdit = null
                )
            }
        }
    }

    // [변경] 검색 필터 함수 추가 (레거시에 없던 기능)
    // 앞면 / 뒷면 / 태그 중 하나라도 keyword를 포함하면 결과에 포함
    // ignoreCase = true: 대소문자 구분 없이 검색
    // keyword가 비어있으면 전체 반환 (검색어 없음 = 필터 없음)
    private fun filterCards(cards: List<CardUi>, query: String): List<CardUi> {
        val keyword = query.trim()
        if (keyword.isBlank()) return cards

        return cards.filter { card ->
            card.front.contains(keyword, ignoreCase = true) ||
                card.back.contains(keyword, ignoreCase = true) ||
                card.tags.contains(keyword, ignoreCase = true)
        }
    }
}
