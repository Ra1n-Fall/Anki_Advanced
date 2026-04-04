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

// [변경] DeckManageActivity → DeckManageViewModel
// 기존: AppCompatActivity 상속, lifecycleScope + items 리스트 + adapter.notify~ 로 UI 직접 제어
// 변경: AndroidViewModel 상속, StateFlow로 상태 노출 → DeckManageScreen이 구독
// 이유: UI 로직(Compose)과 비즈니스 로직 분리
class DeckManageViewModel(application: Application) : AndroidViewModel(application) {

    // [유지] DB 인스턴스 생성 방식 동일
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    // [변경] items: MutableList<CardUi> → _cards: MutableStateFlow
    // 기존: items.clear() + adapter.notifyDataSetChanged() 로 화면 갱신
    // 변경: _cards.value 갱신 → DeckManageScreen이 collectAsState()로 구독해 자동 재구성
    private val _cards = MutableStateFlow<List<CardUi>>(emptyList())
    val cards: StateFlow<List<CardUi>> = _cards.asStateFlow()

    // [변경] initialLoadFromDb() → loadCards()
    // 기존: items.clear() 후 for loop + adapter.notifyDataSetChanged()
    // 변경: map으로 변환 후 _cards.value에 한 번에 대입
    fun loadCards(deckId: Long) {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { db.cardDao().getByDeck(deckId) }
            _cards.value = all.map { CardUi(it.id, it.front, it.back, it.tags, it.state, it.status) }
        }
    }

    // [변경] insertCardAndUpdateUi() → addCard()
    // 기존: items.add(0, ...) + adapter.notifyItemInserted(0) + scrollToPosition(0)
    // 변경: 새 카드를 리스트 앞에 추가한 새 리스트를 _cards.value에 대입
    fun addCard(deckId: Long, front: String, back: String, tags: String) {
        viewModelScope.launch {
            val entity = CardEntity(
                deckId = deckId,
                front = front,
                back = back,
                tags = tags,
                state = 0,
                status = CARD_NEW
            )
            val newId = withContext(Dispatchers.IO) { db.cardDao().insert(entity) }
            _cards.value = listOf(CardUi(newId, front, back, tags, 0, CARD_NEW)) + _cards.value
        }
    }

    // [변경] deleteCardAndUpdateUi() → deleteCard()
    // 기존: items.removeAt(idx) + adapter.notifyItemRemoved(idx)
    // 변경: filter로 해당 카드 제외한 새 리스트를 _cards.value에 대입
    fun deleteCard(card: CardUi) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.cardDao().deleteById(card.id) }
            _cards.value = _cards.value.filter { it.id != card.id }
        }
    }

    // [변경] updateCardAndUpdateUi() → updateCard()
    // 기존: items[position] 직접 교체 + adapter.notifyItemChanged(position)
    // 변경: map으로 해당 id 카드만 교체한 새 리스트를 _cards.value에 대입
    fun updateCard(deckId: Long, old: CardUi, front: String, back: String, tags: String) {
        viewModelScope.launch {
            val entity = CardEntity(
                id = old.id,
                deckId = deckId,
                front = front,
                back = back,
                tags = tags,
                state = old.state,
                status = old.status   // 카드 수정 시 아이디와 학습 상태는 유지
            )
            withContext(Dispatchers.IO) { db.cardDao().update(entity) }
            _cards.value = _cards.value.map {
                if (it.id == old.id) CardUi(old.id, front, back, tags, old.state, old.status) else it
            }
        }
    }
}
