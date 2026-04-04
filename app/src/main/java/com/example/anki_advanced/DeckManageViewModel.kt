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
// 기존: AppCompatActivity 상속, lifecycleScope + items 리스트 + adapter.notify~ 로 UI 직접 제어
// 변경: AndroidViewModel 상속, 단일 UiState StateFlow로 상태 노출 → DeckManageScreen이 구독
// 이유: UI 로직(Compose)과 비즈니스 로직 분리, 다이얼로그/입력 상태까지 ViewModel에서 통합 관리

// [변경] 개별 변수 → DeckManageUiState 단일 상태 객체
// 기존: DeckManageActivity의 items, deckId, showDeleteDialog 등 개별 멤버 변수
// 변경: UiState 하나로 묶어 관리 → copy()로 부분 갱신, Screen은 하나의 Flow만 구독
data class DeckManageUiState(
    val deckId: Long = -1L,
    val deckName: String = "영어 단어",
    val allCards: List<CardUi> = emptyList(),
    val visibleCards: List<CardUi> = emptyList(),
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
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    // [변경] items: MutableList<CardUi> + 개별 변수 → _uiState: MutableStateFlow<DeckManageUiState>
    // 기존: items.clear() + adapter.notifyDataSetChanged() 로 화면 갱신
    // 변경: _uiState.update { } 로 상태 갱신 → DeckManageScreen이 collectAsState()로 구독해 자동 재구성
    private val _uiState = MutableStateFlow(DeckManageUiState())
    val uiState: StateFlow<DeckManageUiState> = _uiState.asStateFlow()

    // [변경] Activity onCreate 직접 호출 → initialize()로 분리
    // 기존: Activity onCreate에서 deckId를 intent로 받아 바로 initialLoadFromDb() 호출
    // 변경: Screen의 LaunchedEffect(deckId)에서 호출, 이미 로딩된 경우 중복 조회 방지
    fun initialize(deckId: Long, deckName: String) {
        val current = _uiState.value
        if (current.deckId == deckId && current.deckName == deckName && current.allCards.isNotEmpty()) return

        _uiState.update {
            it.copy(
                deckId = deckId,
                deckName = deckName
            )
        }
        loadCards()
    }

    // [변경] initialLoadFromDb() → loadCards()
    // 기존: items.clear() 후 for loop + adapter.notifyDataSetChanged()
    // 변경: map으로 변환 후 _uiState.update로 allCards / visibleCards 함께 갱신
    fun loadCards() {
        val deckId = _uiState.value.deckId
        if (deckId < 0L) return
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { db.cardDao().getByDeck(deckId) }
            val cards = all.map { CardUi(it.id, it.front, it.back, it.tags, it.state, it.status) }
            _uiState.update { state ->
                state.copy(
                    allCards = cards,
                    visibleCards = filterCards(cards, state.searchQuery)
                )
            }
        }
    }

    // [변경] binding.etFront/etBack/etTags 직접 읽기 → StateFlow 이벤트 함수로 분리
    // 기존: btnAdd.setOnClickListener 안에서 binding.etFront.text.toString() 직접 읽음
    // 변경: 입력값 변경 시마다 ViewModel에 알려 UiState에 반영 → onAddCard() 호출 시 UiState에서 읽음
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
    // 기존: 검색 없음
    // 변경: searchQuery 변경 시 allCards에서 필터링해 visibleCards 갱신
    fun onSearchQueryChange(value: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = value,
                visibleCards = filterCards(state.allCards, value)
            )
        }
    }

    // [변경] insertCardAndUpdateUi() → onAddCard()
    // 기존: items.add(0, ...) + adapter.notifyItemInserted(0) + scrollToPosition(0)
    // 변경: DB insert 후 allCards 앞에 추가, 입력 필드 초기화를 UiState update 한 번으로 처리
    fun onAddCard() {
        val state = _uiState.value
        if (state.deckId < 0L || state.frontText.isBlank() || state.backText.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                deckId = state.deckId,
                front = state.frontText.trim(),
                back = state.backText.trim(),
                tags = state.tagsText.trim(),
                state = 0,
                status = CARD_NEW
            )
            val newId = withContext(Dispatchers.IO) { db.cardDao().insert(entity) }
            val newCard = CardUi(newId, entity.front, entity.back, entity.tags, 0, CARD_NEW)
            _uiState.update { current ->
                val updatedCards = listOf(newCard) + current.allCards
                current.copy(
                    allCards = updatedCards,
                    visibleCards = filterCards(updatedCards, current.searchQuery),
                    frontText = "",
                    backText = "",
                    tagsText = ""
                )
            }
        }
    }

    // [변경] showDeleteDialog() → onDeleteRequest() + dismissDeleteDialog() + confirmDeleteCard()
    // 기존: showDeleteDialog()에서 AlertDialog.Builder로 다이얼로그 생성 + 확인 시 deleteCardAndUpdateUi() 호출
    // 변경: 다이얼로그 표시/닫기/확인을 각각 함수로 분리, 다이얼로그 UI는 DeckManageScreen이 담당
    fun onDeleteRequest(card: CardUi) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                cardToDelete = card
            )
        }
    }

    fun dismissDeleteDialog() {
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                cardToDelete = null
            )
        }
    }

    // [변경] deleteCardAndUpdateUi() → confirmDeleteCard()
    // 기존: items.removeAt(idx) + adapter.notifyItemRemoved(idx)
    // 변경: filter로 해당 카드 제외한 새 리스트를 UiState update로 반영
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

    // [변경] showEditDialog() → onEditRequest() + confirmEditCard() + dismissEditDialog()
    // 기존: showEditDialog()에서 AlertDialog.Builder + EditText.setText()로 기존 값 채움
    // 변경: 수정 대상과 입력값을 UiState에 저장, 다이얼로그 UI는 DeckManageScreen이 담당
    fun onEditRequest(card: CardUi) {
        _uiState.update {
            it.copy(
                showEditDialog = true,
                cardToEdit = card,
                editFront = card.front,
                editBack = card.back,
                editTags = card.tags
            )
        }
    }

    fun onEditFrontChange(value: String) {
        _uiState.update { it.copy(editFront = value) }
    }

    fun onEditBackChange(value: String) {
        _uiState.update { it.copy(editBack = value) }
    }

    fun onEditTagsChange(value: String) {
        _uiState.update { it.copy(editTags = value) }
    }

    fun dismissEditDialog() {
        _uiState.update {
            it.copy(
                showEditDialog = false,
                cardToEdit = null
            )
        }
    }

    // [변경] updateCardAndUpdateUi() → confirmEditCard()
    // 기존: items[position] 직접 교체 + adapter.notifyItemChanged(position)
    // 변경: map으로 해당 id 카드만 교체한 새 리스트를 UiState update로 반영
    fun confirmEditCard() {
        val state = _uiState.value
        val target = state.cardToEdit ?: return
        if (state.deckId < 0L || state.editFront.isBlank() || state.editBack.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                id = target.id,
                deckId = state.deckId,
                front = state.editFront.trim(),
                back = state.editBack.trim(),
                tags = state.editTags.trim(),
                state = target.state,
                status = target.status  // 카드 수정 시 아이디와 학습 상태는 유지
            )
            withContext(Dispatchers.IO) { db.cardDao().update(entity) }
            _uiState.update { current ->
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
                        it
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
