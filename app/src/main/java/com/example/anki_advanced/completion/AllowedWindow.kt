package com.example.anki_advanced.completion

// "완주 모드"에서 하루 중 학습을 허용하는 시간대를 표현하는 데이터 클래스.
// 예: 새벽에는 공부 안 하고 09시~22시에만 공부하고 싶다 → startHour=9, endHour=22
//
// [문법] data class 란?
//   값을 담는 용도의 클래스에 붙이는 키워드. class 대신 data class로 선언하면
//   코틀린이 equals(), hashCode(), toString(), copy() 를 자동으로 만들어준다.
//   즉 "이 객체는 로직이 아니라 데이터 덩어리다"라는 표시.
//
// [문법] 생성자 자리의 기본값(= 0, = 24)
//   AllowedWindow() 처럼 인자 없이 호출하면 startHour=0, endHour=24가 자동으로 들어간다.
//   자바의 오버로딩(여러 생성자 만들기) 없이 기본값 하나로 같은 효과를 낸다.
data class AllowedWindow(
    val startHour: Int = 0,   // 허용 시작 시각 (0~23시)
    val endHour: Int = 24     // 허용 종료 시각 (1~24시, 24는 "자정까지"라는 뜻)
) {
    // [문법] val ... get() = ...  → "커스텀 게터(getter)"
    //   생성자에서 안 받고, 다른 프로퍼티(startHour, endHour)를 계산해서 만들어내는 읽기 전용 값.
    //   저장 공간을 따로 차지하지 않고, isFullDay를 읽을 때마다 매번 이 식을 계산해서 돌려준다.
    //   즉 "필드"가 아니라 "호출할 때마다 계산되는 값" 이라고 이해하면 된다.

    // 하루 24시간 전부 허용이면 true. (시간 제한이 아예 없는 경우를 빠르게 구분하기 위한 값)
    val isFullDay: Boolean get() = startHour == 0 && endHour == 24

    // 허용된 하루 시간 길이를 밀리초(ms)로 환산.
    // (endHour - startHour)는 "시간" 단위 → 60(분) * 60(초) * 1000(ms)를 곱해서 ms로 바꾼다.
    // 뒤에 붙은 L은 "이 숫자는 Long 타입"이라는 표시 (Int 범위를 넘어설 수 있어서 Long 사용).
    val dailyWindowMs: Long get() = (endHour - startHour) * 60L * 60L * 1000L
}
