package com.example.anki_advanced.completion

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

// ── Entity: 덱마다 하나씩 저장되는 "완주 모드 설정" ─────────────────────────

// [문법] @PrimaryKey val deckId: Long  (autoGenerate 없음)
//   보통 기본키는 자동 채번(autoGenerate = true)을 쓰지만, 여기서는 그냥
//   deckId 자체를 기본키로 씀. 즉 "덱 하나당 완주 모드 설정은 딱 1개"라는 규칙을
//   DB 스키마 레벨에서 강제하는 것 (같은 deckId로 또 insert하면 충돌).
@Entity(tableName = "completion_mode_configs")
data class CompletionModeConfigEntity(
    @PrimaryKey val deckId: Long,            // 어느 덱의 설정인지 (동시에 기본키)
    val targetPeriodMs: Long,                // 목표 완주 기간 (밀리초). "오늘부터 며칠 안에 끝낸다"
    val windowStartHour: Int = 0,            // 학습 허용 시작 시각 (0~23시)
    val windowEndHour: Int = 24,             // 학습 허용 종료 시각 (1~24시)
    val modeStartAt: Long,                   // 완주 모드를 시작한 시각 (ms)
    val sessionIntervalMs: Long = 86_400_000L, // 세션(하루 학습 단위) 간격. 기본값 = 86,400,000ms = 24시간
    val isActive: Boolean = true             // 지금 이 완주 모드가 켜져 있는지
) {
    // 저장된 시/분 값으로 AllowedWindow 객체를 만들어주는 편의 함수.
    // [문법] fun toAllowedWindow() = AllowedWindow(...)
    //   중괄호{} 없이 "= 식" 형태로 쓰면 "이 함수의 결과가 곧 저 식의 값"이라는 뜻 (단일 표현식 함수).
    //   fun toAllowedWindow(): AllowedWindow { return AllowedWindow(...) } 와 완전히 같다.
    fun toAllowedWindow() = AllowedWindow(windowStartHour, windowEndHour)

    // 완주 모드가 끝나는 시각 = 시작 시각 + 목표 기간
    val modeEndAt: Long get() = modeStartAt + targetPeriodMs
}

// ── DAO: completion_mode_configs 테이블에 접근하는 창구 ─────────────────────

@Dao
interface CompletionModeDao {

    // 특정 덱의 완주 모드 설정을 조회. 설정이 없으면 null.
    // [문법] 반환 타입이 CompletionModeConfigEntity? 인 이유:
    //   "설정이 아예 없는 덱"도 있을 수 있으니, 값이 없을 가능성을 타입에 명시한 것.
    @Query("SELECT * FROM completion_mode_configs WHERE deckId = :deckId")
    suspend fun getConfig(deckId: Long): CompletionModeConfigEntity?

    // [문법] @Insert(onConflict = OnConflictStrategy.REPLACE)
    //   같은 기본키(deckId)로 또 insert를 시도하면 에러 내지 않고 "기존 값을 덮어쓰기(REPLACE)".
    //   이런 동작을 흔히 "upsert" (update + insert) 라고 부른다.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: CompletionModeConfigEntity)

    // 특정 덱의 완주 모드 설정을 완전히 삭제 (다시 처음부터 설정하고 싶을 때).
    @Query("DELETE FROM completion_mode_configs WHERE deckId = :deckId")
    suspend fun delete(deckId: Long)

    // 삭제하지 않고 isActive 값만 켜고/끄고 싶을 때 사용.
    @Query("UPDATE completion_mode_configs SET isActive = :isActive WHERE deckId = :deckId")
    suspend fun setActive(deckId: Long, isActive: Boolean)
}
