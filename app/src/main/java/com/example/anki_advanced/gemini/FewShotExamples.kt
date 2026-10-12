package com.example.anki_advanced.gemini

// 퓨샷 예시 한 쌍: "이런 학습 자료(source)가 들어오면 → 이런 카드들(cards)을 만든다".
// GeminiDeckGenerator가 이걸 실제 요청 앞에 가짜 대화 턴으로 깔아준다:
//   user  턴: source로 만든 요청 문장 ("아래 내용을 바탕으로 플래시카드를 정확히 N장 만들어줘. ...")
//   model 턴: cards를 JSON 배열로 바꾼 문자열 ([{"front":...,"back":...}, ...])
// 모델은 "아까 이런 입력엔 이렇게 답했었지"라고 받아들이고 마지막 실제 요청에 같은 패턴으로 답한다.
//
// 예시를 고를 때 신경 쓴 점:
//   - 유형마다 주제(분야)를 서로 다르게 섞어서, 모델이 특정 분야에 끌려가지 않게 한다.
//   - 카드 질문 형태도 섞는다 (정의/이유/비교/적용, 출력 예측/빈칸/버그 찾기 등) —
//     예시가 전부 "~은?" 단답형이면 결과 카드도 전부 단답형으로 굳어버리기 때문.
//   - 장수(cards.size)도 2~3장으로 섞어서, "요청한 장수를 정확히 지킨다"는 패턴을 보여준다.
//
// [문법] "질문" to "답"
//   Pair("질문", "답")을 만드는 코틀린 짧은 표기. first = front, second = back.
data class FewShotExample(
    val source: String,
    val cards: List<Pair<String, String>>
)

object FewShotExamples {

    fun forType(cardType: CardType): List<FewShotExample> = when (cardType) {
        CardType.FACT -> FACT
        CardType.CODE -> CODE
        CardType.FORMULA -> FORMULA
        CardType.TIMELINE -> TIMELINE
    }

    // ── 일반 개념: 정의 / 원인 / 비교 / 이유 / 용어+사례 ────────────────────
    private val FACT = listOf(
        FewShotExample(
            source = "광합성은 식물의 엽록체에서 일어나며, 빛에너지를 이용해 이산화탄소와 물로 포도당을 만들고 부산물로 산소를 내놓는다.",
            cards = listOf(
                "광합성이 일어나는 세포 소기관은?" to "엽록체",
                "광합성의 원료 두 가지는?" to "이산화탄소와 물",
                "광합성의 부산물로 방출되는 기체는?" to "산소"
            )
        ),
        FewShotExample(
            source = "다른 조건이 같을 때 어떤 재화의 가격이 오르면 수요량은 줄고, 가격이 내리면 수요량은 늘어난다. 이를 수요의 법칙이라 한다. 단, 소득이나 선호처럼 가격 이외의 요인이 변하면 수요곡선 자체가 이동한다.",
            cards = listOf(
                "수요의 법칙이란?" to "다른 조건이 같을 때 가격이 오르면 수요량이 줄고, 가격이 내리면 수요량이 늘어나는 관계",
                "소득 증가처럼 가격 이외의 요인이 변하면 수요곡선은 어떻게 되는가?" to "곡선 위에서 움직이는 게 아니라 곡선 자체가 이동한다"
            )
        ),
        FewShotExample(
            source = "TCP는 연결을 맺은 뒤 데이터를 보내며 순서와 도착을 보장한다. UDP는 연결 없이 바로 보내서 빠르지만 손실이나 순서 뒤바뀜을 보장하지 않아, 실시간 스트리밍이나 온라인 게임에 주로 쓰인다.",
            cards = listOf(
                "TCP와 UDP 중 데이터의 순서와 도착을 보장하는 프로토콜은?" to "TCP",
                "실시간 스트리밍이나 게임에 UDP를 주로 쓰는 이유는?" to "연결 수립과 재전송 과정이 없어 지연이 적기 때문 (일부 손실은 감수)"
            )
        ),
        FewShotExample(
            source = "햇빛이 대기 중 기체 분자에 부딪히면 파장이 짧은 빛일수록 더 강하게 산란된다(레일리 산란). 그래서 낮에는 파란빛이 하늘 전체로 퍼져 하늘이 파랗게 보인다. 해 질 녘에는 빛이 대기를 더 길게 통과하면서 파란빛이 대부분 흩어지고 붉은빛이 남아 노을이 붉게 보인다.",
            cards = listOf(
                "낮 하늘이 파랗게 보이게 하는 현상은?" to "레일리 산란 — 파장이 짧은 파란빛이 대기 분자에 더 많이 산란된다",
                "노을이 붉게 보이는 이유는?" to "빛이 대기를 길게 통과하는 동안 파란빛은 먼저 흩어지고 붉은빛이 남기 때문"
            )
        ),
        FewShotExample(
            source = "확증 편향은 자신의 기존 믿음을 뒷받침하는 정보는 쉽게 받아들이고, 반대되는 정보는 무시하거나 깎아내리는 경향이다. 예를 들어 특정 종목을 믿는 투자자는 그 종목의 호재 기사만 골라 읽는다.",
            cards = listOf(
                "기존 믿음에 맞는 정보만 받아들이고 반대 정보는 무시하는 경향을 무엇이라 하는가?" to "확증 편향",
                "확증 편향의 예를 하나 들면?" to "특정 종목을 믿는 투자자가 그 종목의 호재 기사만 골라 읽는 것"
            )
        )
    )

    // ── 코드: 출력 예측 / 연산자 의미 / 버그 찾기 / 빈칸 채우기 / 시간 복잡도 ──
    private val CODE = listOf(
        FewShotExample(
            source = "파이썬 리스트 컴프리헨션은 [표현식 for 변수 in 반복가능객체 if 조건] 형태로 새 리스트를 만든다.",
            cards = listOf(
                "파이썬 `[x*2 for x in range(3)]`의 결과는?" to "[0, 2, 4]",
                "파이썬 `[x*x for x in range(5) if x % 2 == 1]`의 결과는?" to "[1, 9]"
            )
        ),
        FewShotExample(
            source = "코틀린에서 ?.(안전 호출)는 수신 객체가 null이면 호출하지 않고 null을 반환한다. ?:(엘비스 연산자)는 왼쪽 식이 null일 때 오른쪽 값을 대신 쓴다.",
            cards = listOf(
                "코틀린 `val name: String? = null; println(name?.length ?: 0)`의 출력은?" to "0",
                "코틀린 엘비스 연산자 `?:`의 역할은?" to "왼쪽 식이 null이면 오른쪽 값을 대신 사용한다"
            )
        ),
        FewShotExample(
            source = "JavaScript의 var는 함수 스코프라 반복문의 모든 회차가 같은 변수를 공유한다. let은 블록 스코프라 회차마다 새 변수가 만들어진다.",
            cards = listOf(
                "JS `for (var i = 0; i < 3; i++) setTimeout(() => console.log(i));`의 출력은?" to "3, 3, 3",
                "위 코드가 0, 1, 2를 출력하게 고치려면?" to "var를 let으로 바꾼다 — 회차마다 새로운 i가 만들어진다"
            )
        ),
        FewShotExample(
            source = "GROUP BY로 묶은 그룹에 조건을 걸 때는 WHERE가 아니라 HAVING을 쓴다. WHERE는 그룹으로 묶기 전에 개별 행을 거른다.",
            cards = listOf(
                "직원이 5명 이상인 부서만 조회할 때 빈칸은? `SELECT dept, COUNT(*) FROM emp GROUP BY dept ____ COUNT(*) >= 5;`" to "HAVING",
                "SQL에서 WHERE와 HAVING의 차이는?" to "WHERE는 그룹화 전의 행을, HAVING은 그룹화 후의 그룹을 거른다"
            )
        ),
        FewShotExample(
            source = "Java의 HashMap은 키의 해시값으로 버킷을 바로 찾아 평균 O(1)에 조회한다. 반면 ArrayList의 contains는 앞에서부터 원소를 하나씩 비교하므로 O(n)이다.",
            cards = listOf(
                "Java `HashMap.get()`의 평균 시간 복잡도는?" to "O(1)",
                "Java `ArrayList.contains()`의 시간 복잡도와 그 이유는?" to "O(n) — 앞에서부터 원소를 하나씩 비교하기 때문"
            )
        )
    )

    // ── 수식: 공식 자체 / 단위·변수 의미 / 간단한 대입 계산 ─────────────────
    private val FORMULA = listOf(
        FewShotExample(
            source = "직각삼각형에서 빗변의 길이를 제곱한 값은 나머지 두 변의 길이를 각각 제곱해 더한 값과 같다.",
            cards = listOf(
                "피타고라스 정리의 식은? (빗변 c, 나머지 두 변 a, b)" to "a² + b² = c²",
                "빗변이 아닌 두 변이 3, 4인 직각삼각형의 빗변 길이는?" to "5 (√(9 + 16) = √25)"
            )
        ),
        FewShotExample(
            source = "물체의 가속도는 가해진 알짜힘에 비례하고 질량에 반비례한다. 힘의 단위는 N(뉴턴)이다.",
            cards = listOf(
                "뉴턴 운동 제2법칙의 식은?" to "F = ma",
                "1 N을 기본 단위로 나타내면?" to "1 kg·m/s²",
                "질량 2 kg인 물체에 10 N의 알짜힘을 가하면 가속도는?" to "5 m/s²"
            )
        ),
        FewShotExample(
            source = "이차방정식 ax² + bx + c = 0 (a ≠ 0)의 해는 근의 공식으로 구한다. 판별식 D = b² − 4ac의 부호로 실근의 개수를 알 수 있다.",
            cards = listOf(
                "이차방정식 ax² + bx + c = 0의 근의 공식은?" to "x = (−b ± √(b² − 4ac)) / 2a",
                "판별식 D < 0일 때 실근의 개수는?" to "0개 (서로 다른 두 허근)"
            )
        ),
        FewShotExample(
            source = "도체에 흐르는 전류는 양 끝의 전압에 비례하고 저항에 반비례한다(옴의 법칙).",
            cards = listOf(
                "옴의 법칙을 전압(V), 전류(I), 저항(R)으로 나타내면?" to "V = IR",
                "전압 12 V, 저항 4 Ω인 회로에 흐르는 전류는?" to "3 A"
            )
        ),
        FewShotExample(
            source = "원금 P를 연이율 r로 n년 동안 복리로 맡기면 원리합계 A는 매년 (1 + r)배씩 불어난다.",
            cards = listOf(
                "연 복리 원리합계 공식은?" to "A = P(1 + r)ⁿ",
                "100만 원을 연 10% 복리로 2년 맡기면 원리합계는?" to "121만 원 (100 × 1.1²)"
            )
        )
    )

    // ── 연표: 연도 / 날짜 / 선후 관계 / 계기 / 연쇄 순서 ────────────────────
    private val TIMELINE = listOf(
        FewShotExample(
            source = "세종대왕은 1443년에 훈민정음을 창제하고, 1446년에 이를 반포하였다.",
            cards = listOf(
                "세종대왕이 훈민정음을 창제한 연도는?" to "1443년",
                "훈민정음이 반포된 연도는?" to "1446년"
            )
        ),
        FewShotExample(
            source = "1592년 일본이 조선을 침략하며 임진왜란이 시작되었다. 이후 1636년 청나라가 침입한 병자호란이 일어났고, 인조는 남한산성에서 항전하다 1637년 삼전도에서 항복하였다.",
            cards = listOf(
                "임진왜란이 시작된 연도는?" to "1592년",
                "임진왜란과 병자호란을 순서대로 연도와 함께 쓰면?" to "임진왜란(1592) → 병자호란(1636)",
                "병자호란 때 인조가 항전한 곳은?" to "남한산성"
            )
        ),
        FewShotExample(
            source = "1789년 7월 14일 파리 시민들이 바스티유 감옥을 습격하며 프랑스 혁명이 본격화되었다. 같은 해 8월 인권 선언이 발표되었고, 1799년 나폴레옹이 쿠데타로 권력을 잡으며 혁명기는 사실상 막을 내렸다.",
            cards = listOf(
                "바스티유 감옥 습격이 일어난 날짜는?" to "1789년 7월 14일",
                "프랑스 인권 선언이 발표된 시기는?" to "1789년 8월",
                "나폴레옹이 쿠데타로 권력을 잡은 연도는?" to "1799년"
            )
        ),
        FewShotExample(
            source = "1914년 6월 사라예보에서 오스트리아-헝가리 제국의 황태자가 암살된 사건을 계기로 제1차 세계대전이 시작되었다. 전쟁은 1918년 11월 휴전 협정으로 끝났다.",
            cards = listOf(
                "제1차 세계대전의 직접적인 계기가 된 사건은?" to "1914년 6월 사라예보 사건 (오스트리아-헝가리 황태자 암살)",
                "제1차 세계대전이 휴전으로 끝난 시기는?" to "1918년 11월"
            )
        ),
        FewShotExample(
            source = "1957년 소련이 최초의 인공위성 스푸트니크 1호를 발사했다. 1961년에는 유리 가가린이 최초의 유인 우주 비행에 성공했고, 1969년 미국의 아폴로 11호가 인류 최초로 달에 착륙했다.",
            cards = listOf(
                "최초의 인공위성 이름과 발사 연도는?" to "스푸트니크 1호, 1957년",
                "스푸트니크 1호 발사, 가가린의 우주 비행, 아폴로 11호 달 착륙을 연도순으로 쓰면?" to "1957 → 1961 → 1969"
            )
        )
    )
}
