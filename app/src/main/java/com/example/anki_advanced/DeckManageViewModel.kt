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

class DeckManageViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "anki.db"
    ).fallbackToDestructiveMigration().build()

    private val _cards = MutableStateFlow<List<CardUi>>(emptyList())
    val cards: StateFlow<List<CardUi>> = _cards.asStateFlow()

    fun loadCards(deckId: Long) {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { db.cardDao().getByDeck(deckId) }
            _cards.value = all.map { CardUi(it.id, it.front, it.back, it.tags, it.state, it.status) }
        }
    }

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

    fun deleteCard(card: CardUi) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.cardDao().deleteById(card.id) }
            _cards.value = _cards.value.filter { it.id != card.id }
        }
    }

    fun updateCard(deckId: Long, old: CardUi, front: String, back: String, tags: String) {
        viewModelScope.launch {
            val entity = CardEntity(
                id = old.id,
                deckId = deckId,
                front = front,
                back = back,
                tags = tags,
                state = old.state,
                status = old.status
            )
            withContext(Dispatchers.IO) { db.cardDao().update(entity) }
            _cards.value = _cards.value.map {
                if (it.id == old.id) CardUi(old.id, front, back, tags, old.state, old.status) else it
            }
        }
    }
}
