package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 카드를 채점(복습)할 때마다 기록 한 줄을 남기는 "복습 로그" 테이블.
// 통계 화면이나 "이 카드를 몇 번 틀렸는지" 같은 분석에 쓰인다.
//
// [문법] @Entity(...)  → Room 라이브러리가 이 클래스를 SQLite의 "테이블"로 만들어준다.
//   tableName으로 실제 테이블 이름을 지정.
//   indices = [...] 는 검색 속도를 위한 "색인(index)". 자주 같이 조회하는 컬럼 조합을
//   미리 색인해두면, WHERE deckId = ... AND reviewedAt > ... 같은 조회가 훨씬 빨라진다.
@Entity(
    tableName = "review_logs",
    indices = [
        Index(value = ["deckId", "reviewedAt"]), // 덱별 + 시간순 조회를 빠르게
        Index(value = ["cardId", "reviewedAt"])  // 카드별 + 시간순 조회를 빠르게
    ]
)
data class ReviewLogEntity(
    // [문법] @PrimaryKey(autoGenerate = true)
    //   이 컬럼을 기본키로 쓰고, 값을 직접 넣지 않으면 DB가 1, 2, 3... 자동으로 채워준다.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,          // 로그 한 줄의 고유 번호

    val deckId: Long,           // 어느 덱에서 일어난 채점인지
    val cardId: Long,           // 어느 카드를 채점했는지

    val score: Int,             // 사용자가 누른 채점 버튼 (0=Again, 1=Hard, 2=Good, 3=Easy)
    val sm2Q: Int,               // 위 score를 SM-2 알고리즘이 쓰는 품질 점수(q)로 변환한 값 (0/3/4/5)

    val reviewedAt: Long,        // 채점한 시각 (1970-01-01부터 흐른 밀리초, "에폭 타임")

    val isNewAtReview: Int       // 채점 당시 이 카드가 "처음 보는 카드(NEW)"였는지 (0=아니오, 1=예)
)
