package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.PrimaryKey

data class DeckUi(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int,
    val lastStudiedAt: Long? = null,         // 마지막 학습 시각 (ms), 없으면 null
    val completionModeEndAt: Long? = null,   // completion mode 만기 시각, null = 모드 없음
    val completionTargetDays: Int? = null    // 목표 기간 (일), 배지 표시용
)

data class DeckStudyLimit(
    val dailyNewLimit: Int,
    val dailyReviewLimit: Int
)

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

