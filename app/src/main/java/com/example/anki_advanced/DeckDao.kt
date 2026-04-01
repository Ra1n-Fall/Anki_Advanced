package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DeckDao {

    @Query("SELECT * FROM decks ORDER BY id DESC")
    suspend fun getAll(): List<DeckEntity>

    @Insert
    suspend fun insert(deck: DeckEntity): Long

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        SELECT dailyNewLimit, dailyReviewLimit
        FROM decks
        WHERE id = :deckId
    """)
    suspend fun getStudyLimits(
        deckId: Long
    ): DeckStudyLimit
    // 덱 학습 한도를 가져오는 쿼리

    @Query("""
    UPDATE decks
    SET dailyNewLimit = :newLimit,
        dailyReviewLimit = :reviewLimit
    WHERE id = :deckId
""")
    suspend fun updateStudyLimits(deckId: Long, newLimit: Int, reviewLimit: Int)
    // 덱 학습 한도를 업데이트하는 쿼리
    // 덱 설정 화면에서 한도 변경 시 호출
}
