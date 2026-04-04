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

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    private val _uiState = MutableStateFlow(DeckManageUiState())
    val uiState: StateFlow<DeckManageUiState> = _uiState.asStateFlow()

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

    fun onFrontTextChange(value: String) {
        _uiState.update { it.copy(frontText = value) }
    }

    fun onBackTextChange(value: String) {
        _uiState.update { it.copy(backText = value) }
    }

    fun onTagsTextChange(value: String) {
        _uiState.update { it.copy(tagsText = value) }
    }

    fun onSearchQueryChange(value: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = value,
                visibleCards = filterCards(state.allCards, value)
            )
        }
    }

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
                status = target.status
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
