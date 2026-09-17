package com.example.anki_advanced.completion

import androidx.room.Dao
import androidx.room.Query
import com.example.anki_advanced.CardEntity

// 완주 모드(기간 안에 끝내기) 전용 카드 쿼리 창구.
// 기존 CardDao와는 별개의 인터페이스지만, 같은 "cards" 테이블을 그대로 조회/수정한다.
// (Room은 하나의 테이블에 여러 DAO가 접근하는 걸 허용한다.)
//
// 표준 모드(CardDao)와 다른 핵심 포인트:
//   - REVIEW 카드가 "복습할 때 됐는지" 판정 기준이 다름: 오늘 자정(todayStart) 기준이 아니라
//     지금 이 순간(now)과 바로 비교 (nextReviewAt <= now)
//   - baseInterval / lastReviewAt 처럼 완주 모드 계산에만 쓰는 필드도 같이 업데이트
@Dao
interface CompletionCardDao {

    // ── 카드 조회 ────────────────────────────────────────────────────────────

    // 지금 당장(now 이전) 재노출 시각이 된 LEARNING 카드. (우선순위 1: 이미 학습 중이던 카드부터)
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextDueLearningCard(deckId: Long, now: Long): CardEntity?

    // 지금 당장 복습할 때가 된 REVIEW 카드.
    // (LEARNING/REVIEW/NEW 중 무엇을 먼저 보여줄지는 여기가 아니라 ViewModel의
    //  classifyDueCard() 함수가 계산해서 정렬한다 — 이 쿼리는 "후보 하나"만 뽑아줄 뿐)
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextDueReviewCard(deckId: Long, now: Long): CardEntity?

    // 한 번도 안 본 NEW 카드 (id 오름차순. 기존 CardDao와 동일한 방식)
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 0
        ORDER BY id ASC
        LIMIT 1
    """)
    suspend fun getNextNewCard(deckId: Long): CardEntity?

    // 시각 조건 없이(아직 재노출 시각이 안 됐어도) 대기 중인 LEARNING 카드를 조기 등장시키는 용도.
    // 위 세 쿼리로 뽑을 카드가 하나도 없을 때, 이 카드라도 미리 보여줘서 세션이
    // "카드 없음"으로 뚝 끊기지 않게 한다.
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

    // [문법] status != 0  →  "0이 아닌" 이라는 뜻. NEW(0)가 아닌, 즉 한 번이라도 공부한 카드 수.
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status != 0")
    suspend fun countStudied(deckId: Long): Int

    // 세션이 시작되는 시점에 "이미 밀려있는(due)" LEARNING 카드 수. 세션 목표치 계산에 쓰인다.
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 1 AND nextReviewAt <= :now")
    suspend fun countDueLearning(deckId: Long, now: Long): Int

    // 세션이 시작되는 시점에 이미 밀려있는 REVIEW 카드 수.
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 2 AND nextReviewAt <= :now")
    suspend fun countDueReview(deckId: Long, now: Long): Int

    // ── 압축 비율(compressionRatio) 계산용 ──────────────────────────────────────

    // 이 덱의 REVIEW 카드들 중 "원래(압축 전) 간격"이 가장 긴 값. 없으면(REVIEW 카드 자체가 없으면) null.
    // 완주 모드는 이 값을 기준으로 "목표 기간 안에 들어오게 간격을 얼마나 압축할지" 비율을 계산한다.
    @Query("SELECT MAX(baseInterval) FROM cards WHERE deckId = :deckId AND status = 2")
    suspend fun getMaxBaseInterval(deckId: Long): Long?

    // ── 모드 전환 시 전체 카드 재계산용 ─────────────────────────────────────

    // 이미 한 번이라도 공부한(LEARNING/REVIEW) 카드 전체. 완주 모드를 켜거나 끌 때
    // 이 카드들의 nextReviewAt을 한꺼번에 다시 계산해야 하므로 전체 목록이 필요하다.
    @Query("SELECT * FROM cards WHERE deckId = :deckId AND status != 0")
    suspend fun getAllStudiedCards(deckId: Long): List<CardEntity>

    // ── 업데이트 ─────────────────────────────────────────────────────────────

    // 완주 모드의 SM-2 계산 결과를 저장. 표준 모드의 updateSm2()와 내용이 비슷해 보이지만,
    // baseInterval / lastReviewAt처럼 완주 모드 전용 필드까지 같이 갱신한다는 점이 다르다.
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

    // 완주 모드를 켜거나 끄거나 설정을 바꿨을 때, nextReviewAt(다음 복습 시각)만 콕 집어 갱신.
    @Query("UPDATE cards SET nextReviewAt = :nextReviewAt WHERE id = :id")
    suspend fun updateNextReviewAt(id: Long, nextReviewAt: Long)
}
