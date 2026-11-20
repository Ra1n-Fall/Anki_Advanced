package com.example.anki_advanced

// 채점 버튼 위에 "이 버튼을 누르면 카드가 언제 다시 나오는지"를 짧게 보여주기 위한 포맷 함수.
// ms(다음 등장까지 남은 시간)를 가장 알맞은 단위 하나로 반올림해 표시한다.
//   예) 60_000 → "1분", 600_000 → "10분", 7_200_000 → "2시간", 4일 → "4일"
fun formatGradeInterval(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return when {
        sec < 60     -> "${sec}초"
        sec < 3_600  -> "${(sec + 30) / 60}분"
        sec < 86_400 -> "${(sec + 1_800) / 3_600}시간"
        else         -> "${(sec + 43_200) / 86_400}일"
    }
}
