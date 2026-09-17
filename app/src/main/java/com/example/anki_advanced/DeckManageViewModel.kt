package com.example.anki_advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 덱 관리 화면(카드 목록 보기/추가/수정/삭제/검색)의 모든 상태를 한 곳에 모아둔 데이터 덩어리.
// 화면(DeckManageScreen)은 이 객체 하나만 구독하면, 입력창 값부터 다이얼로그 열림 여부까지 전부 알 수 있다.
//
// [문법] data class로 "화면 상태 전체"를 표현하는 패턴 (UiState 패턴)
//   상태 하나하나를 개별 변수로 흩어놓지 않고 한 덩어리로 묶어두면,
//   나중에 copy(필드 = 새값)로 "일부만 바뀐 새 상태"를 쉽게 만들 수 있다.
data class DeckManageUiState(
    val deckId: Long = -1L,
    val deckName: String = "영어 단어",
    val allCards: List<CardUi> = emptyList(),     // 검색 필터를 적용하기 전, DB에서 읽어온 전체 카드 목록
    val visibleCards: List<CardUi> = emptyList(), // 실제로 화면에 표시되는 목록 (검색어로 걸러진 결과)
    val frontText: String = "",   // 카드 추가 입력창 - 앞면
    val backText: String = "",    // 카드 추가 입력창 - 뒷면
    val tagsText: String = "",    // 카드 추가 입력창 - 태그
    val searchQuery: String = "",
    val showDeleteDialog: Boolean = false,
    val cardToDelete: CardUi? = null,
    val showEditDialog: Boolean = false,
    val cardToEdit: CardUi? = null,
    val editFront: String = "",   // 카드 수정 입력창 - 앞면
    val editBack: String = "",    // 카드 수정 입력창 - 뒷면
    val editTags: String = ""     // 카드 수정 입력창 - 태그
)

class DeckManageViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체가 공유하는 AppDatabase 싱글턴 인스턴스.
    private val db = AppDatabase.getInstance(application)

    // _uiState: 이 ViewModel 안에서만 값을 바꿀 수 있는 "쓰기용" 통.
    // uiState : DeckManageScreen에는 읽기 전용으로만 노출하는 "읽기용" 통로.
    // 화면은 uiState.collectAsState()로 구독해두면 값이 바뀔 때마다 자동으로 다시 그려진다.
    private val _uiState = MutableStateFlow(DeckManageUiState())
    val uiState: StateFlow<DeckManageUiState> = _uiState.asStateFlow()

    // 화면에 처음 들어올 때 호출: deckId/deckName을 상태에 채우고 카드 목록을 불러온다.
    fun initialize(deckId: Long, deckName: String) {
        val current = _uiState.value
        // 이미 같은 덱을 로딩해둔 상태라면(예: 화면 회전으로 재진입) DB를 또 조회할 필요가 없다.
        if (current.deckId == deckId && current.deckName == deckName && current.allCards.isNotEmpty()) return

        // [문법] _uiState.update { it.copy(...) }
        //   현재 상태(it)를 받아서, 바뀐 부분만 copy()로 교체한 "새 상태 객체"를 만들어 반영하는 표준 패턴.
        //   StateFlow의 값은 직접 필드를 바꾸는 게 아니라 항상 "새 객체로 통째로 교체"하는 방식으로 갱신한다.
        _uiState.update {
            it.copy(
                deckId = deckId,
                deckName = deckName
            )
        }
        loadCards()
    }

    // 현재 deckId의 카드 전체를 DB에서 다시 읽어와 목록을 갱신.
    fun loadCards() {
        val deckId = _uiState.value.deckId
        if (deckId < 0L) return  // initialize()가 아직 안 불렸으면 그냥 무시
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { db.cardDao().getByDeck(deckId) }
            // DB용 모델(CardEntity)을 화면용 모델(CardUi)로 변환
            val cards = all.map { CardUi(it.id, it.front, it.back, it.tags, it.state, it.status) }
            _uiState.update { state ->
                state.copy(
                    allCards = cards,
                    // 재로딩 후에도 사용자가 입력해둔 검색어(state.searchQuery)를 그대로 유지하면서 다시 필터링
                    visibleCards = filterCards(cards, state.searchQuery)
                )
            }
        }
    }

    // 아래 세 함수는 "카드 추가" 입력창에 글자를 입력할 때마다 화면에서 호출해주는 콜백들.
    // 값을 ViewModel 상태에 저장해두면, ViewModel이 View(EditText 등)를 직접 들고 있지 않아도
    // onAddCard()를 호출하는 시점에 state.frontText 등으로 최신 입력값을 읽을 수 있다.
    fun onFrontTextChange(value: String) {
        _uiState.update { it.copy(frontText = value) }
    }

    fun onBackTextChange(value: String) {
        _uiState.update { it.copy(backText = value) }
    }

    fun onTagsTextChange(value: String) {
        _uiState.update { it.copy(tagsText = value) }
    }

    // 검색어가 바뀔 때마다 호출: allCards는 그대로 두고, visibleCards만 새로 필터링해서 갱신.
    // (allCards를 안 건드리기 때문에 검색어를 지우면 전체 목록으로 바로 복원된다)
    fun onSearchQueryChange(value: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = value,
                visibleCards = filterCards(state.allCards, value)
            )
        }
    }

    // "추가" 버튼을 눌렀을 때 호출.
    fun onAddCard() {
        val state = _uiState.value
        // 유효성 검사: 덱이 아직 안 정해졌거나, 앞면/뒷면이 비어있으면 그냥 무시.
        // [문법] "  ".isBlank() → 공백만 있거나 완전히 빈 문자열이면 true.
        if (state.deckId < 0L || state.frontText.isBlank() || state.backText.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                deckId = state.deckId,
                // [문법] "  hi  ".trim() → 문자열 앞뒤 공백만 제거.
                front = state.frontText.trim(),
                back = state.backText.trim(),
                tags = state.tagsText.trim(),
                state = 0,
                status = CARD_NEW  // 새 카드는 항상 NEW 상태로 시작
            )
            val newId = withContext(Dispatchers.IO) { db.cardDao().insert(entity) }
            val newCard = CardUi(newId, entity.front, entity.back, entity.tags, 0, CARD_NEW)
            _uiState.update { current ->
                // [문법] listOf(newCard) + current.allCards
                //   리스트끼리 + 연산으로 이어붙이기. newCard를 맨 앞에 두고 기존 목록을 뒤에 붙여서
                //   "새로 추가한 카드가 목록 맨 위에 보이게" 만든다.
                val updatedCards = listOf(newCard) + current.allCards
                current.copy(
                    allCards = updatedCards,
                    visibleCards = filterCards(updatedCards, current.searchQuery),
                    frontText = "",  // 추가 완료 후 입력창 비우기
                    backText = "",
                    tagsText = ""
                )
            }
        }
    }

    // 카드 삭제 버튼을 누르면: 실제로 지우지 않고, "삭제 확인 다이얼로그"를 띄우기 위한 상태만 세팅.
    fun onDeleteRequest(card: CardUi) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                cardToDelete = card
            )
        }
    }

    // 삭제 확인 다이얼로그에서 "취소"를 누르면 호출.
    fun dismissDeleteDialog() {
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                cardToDelete = null
            )
        }
    }

    // 삭제 확인 다이얼로그에서 "확인"을 누르면 호출: 실제로 DB에서 삭제.
    fun confirmDeleteCard() {
        // [문법] state.cardToDelete ?: return
        //   ?: (엘비스 연산자): 왼쪽 값이 null이면 오른쪽 코드를 실행. 여기서는
        //   "삭제할 카드가 지정 안 돼 있으면 함수를 여기서 끝낸다"는 방어 코드.
        val card = _uiState.value.cardToDelete ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.cardDao().deleteById(card.id)
            }
            _uiState.update { state ->
                // [문법] list.filter { 조건 }  → 조건을 만족하는 원소만 남긴 새 리스트를 만듦.
                //   여기서는 "삭제 대상과 id가 다른 것들만" 남겨서 자연스럽게 삭제 효과를 낸다.
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

    // 카드 수정(연필 아이콘 등) 버튼을 누르면: 수정 다이얼로그를 띄우고, 입력창들을
    // 지금 카드의 값으로 미리 채워 넣는다.
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

    // 수정 다이얼로그 안의 입력창들이 바뀔 때마다 호출되는 콜백들.
    fun onEditFrontChange(value: String) {
        _uiState.update { it.copy(editFront = value) }
    }

    fun onEditBackChange(value: String) {
        _uiState.update { it.copy(editBack = value) }
    }

    fun onEditTagsChange(value: String) {
        _uiState.update { it.copy(editTags = value) }
    }

    // 수정 다이얼로그에서 "취소"를 누르면 호출.
    fun dismissEditDialog() {
        _uiState.update {
            it.copy(
                showEditDialog = false,
                cardToEdit = null
            )
        }
    }

    // 수정 다이얼로그에서 "확인"을 누르면 호출: DB를 갱신하고 목록의 해당 카드만 새 값으로 교체.
    fun confirmEditCard() {
        val state = _uiState.value
        val target = state.cardToEdit ?: return
        if (state.deckId < 0L || state.editFront.isBlank() || state.editBack.isBlank()) return

        viewModelScope.launch {
            val entity = CardEntity(
                id = target.id,            // 기존 id 그대로 유지 (같은 행을 UPDATE 해야 하므로)
                deckId = state.deckId,
                front = state.editFront.trim(),
                back = state.editBack.trim(),
                tags = state.editTags.trim(),
                state = target.state,       // 채점 점수는 그대로 유지 (내용 수정이 학습 진도에 영향 안 줌)
                status = target.status      // NEW/LEARNING/REVIEW 상태도 그대로 유지
            )
            withContext(Dispatchers.IO) { db.cardDao().update(entity) }
            _uiState.update { current ->
                // [문법] list.map { if (조건) 새값 else it }
                //   전체를 순회하면서, 대상 카드만 새 값으로 바꾸고 나머지는 그대로(it) 둔 새 리스트를 만든다.
                //   "리스트 안의 딱 하나만 바꾸기"를 흔히 이런 식으로 표현한다.
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
                        it  // 나머지 카드는 손대지 않고 그대로 둠
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

    // 검색어로 카드 목록을 걸러주는 함수.
    // 앞면 / 뒷면 / 태그 중 하나라도 검색어를 포함하면 결과에 남긴다.
    // [문법] str.contains(keyword, ignoreCase = true) → 대소문자 구분 없이 포함 여부 검사.
    private fun filterCards(cards: List<CardUi>, query: String): List<CardUi> {
        val keyword = query.trim()
        if (keyword.isBlank()) return cards  // 검색어가 없으면 필터링 없이 전체 반환

        return cards.filter { card ->
            card.front.contains(keyword, ignoreCase = true) ||
                card.back.contains(keyword, ignoreCase = true) ||
                card.tags.contains(keyword, ignoreCase = true)
        }
    }
}
