package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "review_logs",
    indices = [
        Index(value = ["deckId", "reviewedAt"]),
        Index(value = ["cardId", "reviewedAt"])
    ]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val deckId: Long,
    val cardId: Long,

    val score: Int,        // 0~3 (Again/Hard/Good/Easy)
    val sm2Q: Int,         // 0/3/4/5 (SM-2 q)

    val reviewedAt: Long,  // 채점한 시각(ms)

    val isNewAtReview: Int // 채점 당시 새 카드였는지(0/1)
)
