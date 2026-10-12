package com.example.anki_advanced.gemini

// 퓨샷 예시 한 쌍: "이런 학습 자료(source)가 들어오면 → 이런 카드들(cards)을 만든다".
// GeminiDeckGenerator가 이걸 실제 요청 앞에 가짜 대화 턴으로 깔아준다:
//   user  턴: source로 만든 요청 문장 ("아래 내용을 바탕으로 플래시카드를 정확히 N장 만들어줘. ...")
//   model 턴: cards를 JSON 배열로 바꾼 문자열 ([{"front":...,"back":...}, ...])
// 모델은 "아까 이런 입력엔 이렇게 답했었지"라고 받아들이고 마지막 실제 요청에 같은 패턴으로 답한다.
//
// 예시 카드마다 세부 형태(style)를 붙여둔다. 사용자가 덱 만들기 화면에서 형태를 하나만
// 고르면, 그 형태의 카드만 남긴 예시가 전송된다 — 그래서 모든 예시가 그 유형의 두 형태를
// 최소 1장씩 갖고 있어야 어떤 조합을 골라도 예시 5쌍이 빠짐없이 들어간다.
//
// 예시를 고를 때 신경 쓴 점:
//   - 유형마다 주제(분야)를 서로 다르게 섞어서, 모델이 특정 분야에 끌려가지 않게 한다.
//   - 장수도 2~3장으로 섞어서, "요청한 장수를 정확히 지킨다"는 패턴을 보여준다.
//
// 사용자가 설정 화면에서 직접 만든 예시(FewShotStore)도 같은 클래스를 쓴다.
// style은 기본 유형의 예시에서만 의미가 있고, 사용자 지정 유형의 예시 카드는 null이다
// (사용자 지정 유형에는 세부 형태 구분이 없으므로).
data class ExampleCard(
    val front: String,
    val back: String,
    val style: CardStyle?
)

data class FewShotExample(
    val source: String,
    val cards: List<ExampleCard>
)

object FewShotExamples {

    fun forType(cardType: CardType): List<FewShotExample> = when (cardType) {
        CardType.FACT -> FACT
        CardType.CODE -> CODE
        CardType.FORMULA -> FORMULA
        CardType.TIMELINE -> TIMELINE
    }

    // ── 일반 개념: 개념 맞히기(설명 → 용어) / 개념 설명하기(용어 → 설명) ──────
    private val FACT = listOf(
        FewShotExample(
            source = "광합성은 식물의 엽록체에서 일어나며, 빛에너지를 이용해 이산화탄소와 물로 포도당을 만들고 부산물로 산소를 내놓는다.",
            cards = listOf(
                ExampleCard("식물이 빛에너지로 이산화탄소와 물에서 포도당을 만드는 과정은?", "광합성", CardStyle.FACT_TERM),
                ExampleCard("광합성이 일어나는 식물 세포의 소기관은?", "엽록체", CardStyle.FACT_TERM),
                ExampleCard("광합성이란?", "식물이 엽록체에서 빛에너지를 이용해 이산화탄소와 물로 포도당을 만들고, 부산물로 산소를 내놓는 과정", CardStyle.FACT_EXPLAIN)
            )
        ),
        FewShotExample(
            source = "다른 조건이 같을 때 어떤 재화의 가격이 오르면 수요량은 줄고, 가격이 내리면 수요량은 늘어난다. 이를 수요의 법칙이라 한다. 단, 소득이나 선호처럼 가격 이외의 요인이 변하면 수요곡선 자체가 이동한다.",
            cards = listOf(
                ExampleCard("다른 조건이 같을 때 가격이 오르면 수요량이 줄고, 내리면 늘어나는 관계를 무엇이라 하는가?", "수요의 법칙", CardStyle.FACT_TERM),
                ExampleCard("수요곡선 자체가 이동하는 경우는?", "소득이나 선호처럼 가격 이외의 요인이 변할 때 (가격 변화는 곡선 위의 이동일 뿐이다)", CardStyle.FACT_EXPLAIN)
            )
        ),
        FewShotExample(
            source = "TCP는 연결을 맺은 뒤 데이터를 보내며 순서와 도착을 보장한다. UDP는 연결 없이 바로 보내서 빠르지만 손실이나 순서 뒤바뀜을 보장하지 않아, 실시간 스트리밍이나 온라인 게임에 주로 쓰인다.",
            cards = listOf(
                ExampleCard("연결 없이 바로 전송해 빠르지만 데이터의 순서와 도착을 보장하지 않는 전송 프로토콜은?", "UDP", CardStyle.FACT_TERM),
                ExampleCard("TCP의 특징은?", "연결을 맺은 뒤 데이터를 보내며, 데이터의 순서와 도착을 보장한다", CardStyle.FACT_EXPLAIN),
                ExampleCard("실시간 스트리밍이나 게임에 UDP를 주로 쓰는 이유는?", "연결 수립과 재전송 과정이 없어 지연이 적기 때문 (일부 손실은 감수)", CardStyle.FACT_EXPLAIN)
            )
        ),
        FewShotExample(
            source = "햇빛이 대기 중 기체 분자에 부딪히면 파장이 짧은 빛일수록 더 강하게 산란된다(레일리 산란). 그래서 낮에는 파란빛이 하늘 전체로 퍼져 하늘이 파랗게 보인다. 해 질 녘에는 빛이 대기를 더 길게 통과하면서 파란빛이 대부분 흩어지고 붉은빛이 남아 노을이 붉게 보인다.",
            cards = listOf(
                ExampleCard("파장이 짧은 빛일수록 대기 분자에 더 강하게 산란되는 현상은?", "레일리 산란", CardStyle.FACT_TERM),
                ExampleCard("노을이 붉게 보이는 이유는?", "빛이 대기를 길게 통과하는 동안 파란빛은 먼저 흩어지고 붉은빛이 남기 때문", CardStyle.FACT_EXPLAIN)
            )
        ),
        FewShotExample(
            source = "확증 편향은 자신의 기존 믿음을 뒷받침하는 정보는 쉽게 받아들이고, 반대되는 정보는 무시하거나 깎아내리는 경향이다. 예를 들어 특정 종목을 믿는 투자자는 그 종목의 호재 기사만 골라 읽는다.",
            cards = listOf(
                ExampleCard("기존 믿음에 맞는 정보만 받아들이고 반대 정보는 무시하는 경향은?", "확증 편향", CardStyle.FACT_TERM),
                ExampleCard("확증 편향이란? (예시 포함)", "기존 믿음을 뒷받침하는 정보만 받아들이고 반대 정보는 무시하는 경향. 예: 특정 종목을 믿는 투자자가 호재 기사만 골라 읽는 것", CardStyle.FACT_EXPLAIN)
            )
        )
    )

    // ── 코드: 코드 작성(요구사항 → 코드) / 문법 의미(코드 → 의미·출력) ───────
    private val CODE = listOf(
        FewShotExample(
            source = "파이썬 리스트 컴프리헨션은 [표현식 for 변수 in 반복가능객체 if 조건] 형태로 새 리스트를 만든다.",
            cards = listOf(
                ExampleCard("파이썬 `[x*2 for x in range(3)]`의 결과는?", "[0, 2, 4]", CardStyle.CODE_SYNTAX),
                ExampleCard("0~9 중 짝수의 제곱을 담은 리스트를 파이썬 리스트 컴프리헨션 한 줄로 쓰면?", "`[x*x for x in range(10) if x % 2 == 0]`", CardStyle.CODE_WRITE)
            )
        ),
        FewShotExample(
            source = "코틀린에서 ?.(안전 호출)는 수신 객체가 null이면 호출하지 않고 null을 반환한다. ?:(엘비스 연산자)는 왼쪽 식이 null일 때 오른쪽 값을 대신 쓴다.",
            cards = listOf(
                ExampleCard("코틀린 `name?.length ?: 0`에서 name이 null이면 결과는?", "0 — `?.`가 null을 돌려주고 `?:`가 0으로 대체한다", CardStyle.CODE_SYNTAX),
                ExampleCard("코틀린 엘비스 연산자 `?:`의 의미는?", "왼쪽 식이 null이면 오른쪽 값을 대신 사용한다", CardStyle.CODE_SYNTAX),
                ExampleCard("코틀린에서 nullable String `s`의 길이를 구하되, null이면 -1이 되는 식을 쓰면?", "`s?.length ?: -1`", CardStyle.CODE_WRITE)
            )
        ),
        FewShotExample(
            source = "JavaScript의 var는 함수 스코프라 반복문의 모든 회차가 같은 변수를 공유한다. let은 블록 스코프라 회차마다 새 변수가 만들어진다.",
            cards = listOf(
                ExampleCard("JS `for (var i = 0; i < 3; i++) setTimeout(() => console.log(i));`의 출력은?", "3, 3, 3 — var는 모든 회차가 같은 i를 공유한다", CardStyle.CODE_SYNTAX),
                ExampleCard("JS에서 setTimeout으로 0, 1, 2를 차례로 출력하는 반복문을 쓰면?", "`for (let i = 0; i < 3; i++) setTimeout(() => console.log(i));`", CardStyle.CODE_WRITE)
            )
        ),
        FewShotExample(
            source = "GROUP BY로 묶은 그룹에 조건을 걸 때는 WHERE가 아니라 HAVING을 쓴다. WHERE는 그룹으로 묶기 전에 개별 행을 거른다.",
            cards = listOf(
                ExampleCard("emp 테이블에서 직원이 5명 이상인 부서(dept)와 인원수를 조회하는 SQL은?", "`SELECT dept, COUNT(*) FROM emp GROUP BY dept HAVING COUNT(*) >= 5;`", CardStyle.CODE_WRITE),
                ExampleCard("SQL에서 WHERE와 HAVING의 차이는?", "WHERE는 그룹화 전의 행을, HAVING은 그룹화 후의 그룹을 거른다", CardStyle.CODE_SYNTAX)
            )
        ),
        FewShotExample(
            source = "Java의 HashMap은 put(k, v)로 키-값 쌍을 넣고 get(k)로 꺼내며, 키가 없으면 get은 null을 반환한다. getOrDefault(k, d)는 키가 없을 때 null 대신 d를 반환한다. 키의 해시값으로 바로 찾아가므로 조회는 평균 O(1)이다.",
            cards = listOf(
                ExampleCard("Java `map.get(\"x\")`에서 키 \"x\"가 없으면 반환값은?", "null", CardStyle.CODE_SYNTAX),
                ExampleCard("Java `HashMap.get()`의 평균 시간 복잡도는?", "O(1) — 키의 해시값으로 버킷을 바로 찾는다", CardStyle.CODE_SYNTAX),
                ExampleCard("Java `Map<String, Integer> count`에서 단어 w의 개수를 1 늘리는 코드를 (없으면 0부터) 한 줄로 쓰면?", "`count.put(w, count.getOrDefault(w, 0) + 1);`", CardStyle.CODE_WRITE)
            )
        )
    )

    // ── 수식: 수식 계산(수치 문제 → 답+풀이) / 개념·공식(관계 → 공식·의미) ──────
    private val FORMULA = listOf(
        FewShotExample(
            source = "직각삼각형에서 빗변의 길이를 제곱한 값은 나머지 두 변의 길이를 각각 제곱해 더한 값과 같다.",
            cards = listOf(
                ExampleCard("피타고라스 정리의 식은? (빗변 c, 나머지 두 변 a, b)", "a² + b² = c²", CardStyle.FORMULA_CONCEPT),
                ExampleCard("빗변이 아닌 두 변이 6, 8인 직각삼각형의 빗변 길이는?", "10 (√(36 + 64) = √100)", CardStyle.FORMULA_CALC)
            )
        ),
        FewShotExample(
            source = "물체의 가속도는 가해진 알짜힘에 비례하고 질량에 반비례한다. 힘의 단위는 N(뉴턴)이다.",
            cards = listOf(
                ExampleCard("뉴턴 운동 제2법칙의 식은?", "F = ma", CardStyle.FORMULA_CONCEPT),
                ExampleCard("1 N을 기본 단위로 나타내면?", "1 kg·m/s²", CardStyle.FORMULA_CONCEPT),
                ExampleCard("질량 2 kg인 물체에 10 N의 알짜힘을 가하면 가속도는?", "5 m/s² (a = F/m = 10/2)", CardStyle.FORMULA_CALC)
            )
        ),
        FewShotExample(
            source = "이차방정식 ax² + bx + c = 0 (a ≠ 0)의 해는 근의 공식으로 구한다. 판별식 D = b² − 4ac의 부호로 실근의 개수를 알 수 있다.",
            cards = listOf(
                ExampleCard("이차방정식 ax² + bx + c = 0의 근의 공식은?", "x = (−b ± √(b² − 4ac)) / 2a", CardStyle.FORMULA_CONCEPT),
                ExampleCard("판별식 D < 0일 때 실근의 개수는?", "0개 (서로 다른 두 허근)", CardStyle.FORMULA_CONCEPT),
                ExampleCard("x² − 5x + 6 = 0의 근은?", "x = 2 또는 3 (D = 25 − 24 = 1, x = (5 ± 1) / 2)", CardStyle.FORMULA_CALC)
            )
        ),
        FewShotExample(
            source = "도체에 흐르는 전류는 양 끝의 전압에 비례하고 저항에 반비례한다(옴의 법칙).",
            cards = listOf(
                ExampleCard("옴의 법칙을 전압(V), 전류(I), 저항(R)으로 나타내면?", "V = IR", CardStyle.FORMULA_CONCEPT),
                ExampleCard("전압 12 V, 저항 4 Ω인 회로에 흐르는 전류는?", "3 A (I = V/R = 12/4)", CardStyle.FORMULA_CALC)
            )
        ),
        FewShotExample(
            source = "원금 P를 연이율 r로 n년 동안 복리로 맡기면 원리합계 A는 매년 (1 + r)배씩 불어난다.",
            cards = listOf(
                ExampleCard("연 복리 원리합계 공식은?", "A = P(1 + r)ⁿ", CardStyle.FORMULA_CONCEPT),
                ExampleCard("100만 원을 연 10% 복리로 2년 맡기면 원리합계는?", "121만 원 (100 × 1.1² = 121)", CardStyle.FORMULA_CALC)
            )
        )
    )

    // ── 연표: 사건 순서(뒤섞인 사건들 → 시간 순 정렬) / 연도(사건 → 연도) ──────
    private val TIMELINE = listOf(
        FewShotExample(
            source = "1418년 세종이 즉위했다. 1420년 집현전을 확대 개편했고, 1443년 훈민정음을 창제했으며, 1446년 이를 반포했다.",
            cards = listOf(
                ExampleCard("훈민정음이 창제된 연도는?", "1443년", CardStyle.TIMELINE_YEAR),
                ExampleCard("훈민정음이 반포된 연도는?", "1446년", CardStyle.TIMELINE_YEAR),
                ExampleCard("다음 사건을 시간 순으로 배열하면? 훈민정음 반포 / 세종 즉위 / 훈민정음 창제 / 집현전 확대 개편", "세종 즉위(1418) → 집현전 확대 개편(1420) → 훈민정음 창제(1443) → 훈민정음 반포(1446)", CardStyle.TIMELINE_ORDER)
            )
        ),
        FewShotExample(
            source = "1592년 일본의 침략으로 임진왜란이 시작되었고, 1597년 일본이 다시 쳐들어와 정유재란이 일어났다. 이후 1627년 후금이 침입한 정묘호란, 1636년 청이 침입한 병자호란이 이어졌다.",
            cards = listOf(
                ExampleCard("임진왜란이 시작된 연도는?", "1592년", CardStyle.TIMELINE_YEAR),
                ExampleCard("다음 전쟁을 시간 순으로 배열하면? 병자호란 / 임진왜란 / 정묘호란 / 정유재란", "임진왜란(1592) → 정유재란(1597) → 정묘호란(1627) → 병자호란(1636)", CardStyle.TIMELINE_ORDER)
            )
        ),
        FewShotExample(
            source = "1789년 7월 14일 파리 시민들이 바스티유 감옥을 습격하며 프랑스 혁명이 본격화되었다. 같은 해 8월 인권 선언이 발표되었고, 1793년 루이 16세가 처형되었다. 1799년 나폴레옹이 쿠데타로 권력을 잡으며 혁명기는 사실상 막을 내렸다.",
            cards = listOf(
                ExampleCard("바스티유 감옥 습격이 일어난 날짜는?", "1789년 7월 14일", CardStyle.TIMELINE_YEAR),
                ExampleCard("나폴레옹이 쿠데타로 권력을 잡은 연도는?", "1799년", CardStyle.TIMELINE_YEAR),
                ExampleCard("다음 사건을 시간 순으로 배열하면? 나폴레옹 쿠데타 / 루이 16세 처형 / 바스티유 습격 / 인권 선언", "바스티유 습격(1789.7) → 인권 선언(1789.8) → 루이 16세 처형(1793) → 나폴레옹 쿠데타(1799)", CardStyle.TIMELINE_ORDER)
            )
        ),
        FewShotExample(
            source = "1914년 6월 사라예보에서 오스트리아-헝가리 제국의 황태자가 암살되었고, 한 달 뒤인 7월 오스트리아-헝가리가 세르비아에 선전포고하며 제1차 세계대전이 시작되었다. 1917년 미국이 참전했고, 전쟁은 1918년 11월 휴전 협정으로 끝났다.",
            cards = listOf(
                ExampleCard("제1차 세계대전의 계기가 된 사라예보 사건이 일어난 시기는?", "1914년 6월", CardStyle.TIMELINE_YEAR),
                ExampleCard("다음 사건을 시간 순으로 배열하면? 미국 참전 / 사라예보 사건 / 휴전 협정 / 오스트리아-헝가리의 세르비아 선전포고", "사라예보 사건(1914.6) → 세르비아 선전포고(1914.7) → 미국 참전(1917) → 휴전 협정(1918.11)", CardStyle.TIMELINE_ORDER)
            )
        ),
        FewShotExample(
            source = "1957년 소련이 최초의 인공위성 스푸트니크 1호를 발사했다. 1961년에는 유리 가가린이 최초의 유인 우주 비행에 성공했고, 1969년 미국의 아폴로 11호가 인류 최초로 달에 착륙했다.",
            cards = listOf(
                ExampleCard("최초의 인공위성 스푸트니크 1호가 발사된 연도는?", "1957년", CardStyle.TIMELINE_YEAR),
                ExampleCard("다음 사건을 시간 순으로 배열하면? 아폴로 11호 달 착륙 / 스푸트니크 1호 발사 / 가가린의 유인 우주 비행", "스푸트니크 1호 발사(1957) → 가가린의 유인 우주 비행(1961) → 아폴로 11호 달 착륙(1969)", CardStyle.TIMELINE_ORDER)
            )
        )
    )
}
