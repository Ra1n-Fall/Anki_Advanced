package com.example.anki_advanced.completion

import androidx.room.Dao
import androidx.room.Query
import com.example.anki_advanced.CardEntity

/**
 * completion mode 전용 카드 쿼리 DAO
 *
 * 기존 CardDao와 별도 인터페이스이지만 같은 cards 테이블에 접근한다.
 * (Room은 동일 테이블에 복수 DAO를 허용)
 *
 * 표준 모드와의 핵심 차이:
 *   - REVIEW due 판정: nextReviewAt <= now (절대 시각 기준, todayStart 아님)
 *   - baseInterval / lastReviewAt 필드 포함 업데이트
 */
@Dao
interface CompletionCardDao {

    // ── 카드 조회 ────────────────────────────────────────────────────────────

    /** 시간이 된 LEARNING 카드 (nextReviewAt <= now, 우선순위 1) */
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextDueLearningCard(deckId: Long, now: Long): CardEntity?

    /**
     * 시간이 된 REVIEW 카드 (nextReviewAt <= now)
     * 우선순위는 ViewModel에서 classifyDueCard()로 계산 후 정렬
     */
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextDueReviewCard(deckId: Long, now: Long): CardEntity?

    /** NEW 카드 (id 순, 기존 CardDao와 동일) */
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 0
        ORDER BY id ASC
        LIMIT 1
    """)
    suspend fun getNextNewCard(deckId: Long): CardEntity?

    /**
     * 시간 무관 대기 LEARNING 카드 (조기 등장용)
     * 1~3순위 소진 후 LEARNING 카드가 남아있을 때 조기 등장
     */
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextPendingLearningCard(deckId: Long): CardEntity?

    // ── 카운트 ───────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId")
    suspend fun countAll(deckId: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 0")
    suspend fun countNew(deckId: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status != 0")
    suspend fun countStudied(deckId: Long): Int

    // ── compressionRatio 계산용 ──────────────────────────────────────────────

    /** 덱 내 REVIEW 카드의 최대 baseInterval (ms). 없으면 null */
    @Query("SELECT MAX(baseInterval) FROM cards WHERE deckId = :deckId AND status = 2")
    suspend fun getMaxBaseInterval(deckId: Long): Long?

    // ── 모드 전환 시 전체 카드 재계산용 ─────────────────────────────────────

    /** 덱의 모든 LEARNING/REVIEW 카드 (모드 전환 시 nextReviewAt 일괄 재계산) */
    @Query("SELECT * FROM cards WHERE deckId = :deckId AND status != 0")
    suspend fun getAllStudiedCards(deckId: Long): List<CardEntity>

    // ── 업데이트 ─────────────────────────────────────────────────────────────

    /**
     * completion mode SM-2 결과 저장
     * baseInterval, lastReviewAt 포함 (표준 모드의 updateSm2와 별개)
     */
    @Query("""
        UPDATE cards
        SET state = :state,
            status = :status,
            repetition = :repetition,
            baseInterval = :baseInterval,
            easeFactor = :easeFactor,
            nextReviewAt = :nextReviewAt,
            lastReviewAt = :lastReviewAt,
            learningStep = :learningStep
        WHERE id = :id
    """)
    suspend fun updateCompletion(
        id: Long,
        state: Int,
        status: Int,
        repetition: Int,
        baseInterval: Long,
        easeFactor: Double,
        nextReviewAt: Long,
        lastReviewAt: Long,
        learningStep: Int
    )

    /**
     * 모드 전환/종료 후 nextReviewAt만 일괄 갱신
     */
    @Query("UPDATE cards SET nextReviewAt = :nextReviewAt WHERE id = :id")
    suspend fun updateNextReviewAt(id: Long, nextReviewAt: Long)
}
