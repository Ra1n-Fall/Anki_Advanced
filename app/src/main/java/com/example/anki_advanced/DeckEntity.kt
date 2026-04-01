package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val name: String,

    // 하루 새 카드 수
    val dailyNewLimit: Int = 10,

    // 하루 복습 카드 수
    val dailyReviewLimit: Int = 50
)

