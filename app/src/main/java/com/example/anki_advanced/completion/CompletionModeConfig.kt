package com.example.anki_advanced.completion

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

// ── Entity ───────────────────────────────────────────────────────────────────

/**
 * 덱별 기간 완주 모드 설정 테이블
 *
 * @param deckId           덱 ID (기본키, DeckEntity.id 참조)
 * @param targetPeriodMs   목표 완주 기간 (ms)
 * @param windowStartHour  허용 윈도우 시작 시각 (0~23)
 * @param windowEndHour    허용 윈도우 종료 시각 (1~24)
 * @param modeStartAt      모드 시작 시각 (ms)
 * @param sessionIntervalMs 세션 간격 (ms). 기본 1일
 * @param isActive         모드 활성 여부
 */
@Entity(tableName = "completion_mode_configs")
data class CompletionModeConfigEntity(
    @PrimaryKey val deckId: Long,
    val targetPeriodMs: Long,
    val windowStartHour: Int = 0,
    val windowEndHour: Int = 24,
    val modeStartAt: Long,
    val sessionIntervalMs: Long = 86_400_000L,
    val isActive: Boolean = true
) {
    /** 설정에서 AllowedWindow 객체 생성 */
    fun toAllowedWindow() = AllowedWindow(windowStartHour, windowEndHour)

    /** 모드 종료 시각 (ms) */
    val modeEndAt: Long get() = modeStartAt + targetPeriodMs
}

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface CompletionModeDao {

    @Query("SELECT * FROM completion_mode_configs WHERE deckId = :deckId")
    suspend fun getConfig(deckId: Long): CompletionModeConfigEntity?

    /** 없으면 insert, 있으면 replace */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: CompletionModeConfigEntity)

    @Query("DELETE FROM completion_mode_configs WHERE deckId = :deckId")
    suspend fun delete(deckId: Long)

    @Query("UPDATE completion_mode_configs SET isActive = :isActive WHERE deckId = :deckId")
    suspend fun setActive(deckId: Long, isActive: Boolean)
}
