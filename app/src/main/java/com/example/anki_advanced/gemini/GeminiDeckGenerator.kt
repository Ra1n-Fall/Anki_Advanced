package com.example.anki_advanced.gemini

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// 입력 내용(텍스트/이미지)을 분석해서 LLM이 "이 내용은 이런 식으로 카드를 만드는 게 좋겠다"고
// 고르는 콘텐츠 유형. 유형마다 프롬프트에 넣는 few-shot 예시와 지시문이 달라서, 같은
// "카드 생성"이라도 이 분류 결과에 따라 결과 품질이 크게 달라진다.
// (예: 코드 내용을 "일반 개념" 프롬프트로 생성하면 어색한 카드가 나오지만, "코드" 전용
//  프롬프트를 쓰면 "이 코드의 출력은?" 같은 자연스러운 카드가 나온다.)
//
// [문법] enum class CardType(val id: String, val label: String)
//   각 항목(FACT, CODE, ...)이 그냥 이름표가 아니라 생성자 파라미터(id, label)를 가진
//   "값이 있는 열거형". CardType.CODE.id는 "code"(Gemini에 보낼 문자열/DB 저장용),
//   CardType.CODE.label은 "코드"(사용자에게 보여줄 한글 이름) — 용도가 다른 두 문자열을
//   한 enum 안에 같이 들고 다니는 패턴이다.
enum class CardType(val id: String, val label: String) {
    FACT("fact", "일반 개념"),
    CODE("code", "코드"),
    FORMULA("formula", "수식"),
    TIMELINE("timeline", "연표");

    companion object {
        // Gemini가 돌려준 문자열(예: "code")을 다시 CardType enum 값으로 되돌리는 함수.
        // [문법] entries.firstOrNull { ... } ?: FACT
        //   entries는 이 enum의 모든 값(FACT, CODE, FORMULA, TIMELINE)을 담은 리스트.
        //   그중 id가 일치하는 첫 번째를 찾고, 하나도 없으면(Gemini가 스키마를 안 지켜서
        //   예상 밖의 문자열을 보냈거나 등) 안전하게 기본값 FACT로 대체한다.
        fun fromId(id: String): CardType = entries.firstOrNull { it.id == id } ?: FACT
    }
}

// 같은 유형 안에서도 "카드를 어떤 방향으로 물어볼지"를 고르는 세부 형태. 유형마다 2개씩 있고,
// 덱 만들기 화면에서 체크박스로 하나 또는 둘 다 고를 수 있다 (둘 다 고르면 섞어서 생성).
//   label: 체크박스 옆에 보여줄 이름, description: 그 아래 작은 설명,
//   instruction: Gemini 시스템 지시문에 그대로 들어가는 작성 규칙.
//
// [문법] enum class CardStyle(val cardType: CardType, ...)
//   각 항목이 자기가 속한 CardType을 들고 있어서, CardStyle.forType(CODE)처럼
//   "이 유형의 세부 형태들만" 골라낼 수 있다.
enum class CardStyle(
    val cardType: CardType,
    val label: String,
    val description: String,
    val instruction: String
) {
    FACT_TERM(
        CardType.FACT, "개념 맞히기", "설명을 보고 개념 이름을 떠올리기",
        "개념의 설명·특징·사례를 front에 주고, 그것이 가리키는 개념/용어 이름을 back에 담아."
    ),
    FACT_EXPLAIN(
        CardType.FACT, "개념 설명하기", "개념 이름을 보고 뜻·이유를 설명하기",
        "개념/용어 이름을 front에 주고, 그 의미·원인·특징 설명을 back에 담아."
    ),
    CODE_WRITE(
        CardType.CODE, "코드 작성", "요구사항을 보고 코드를 직접 쓰기",
        "구현할 동작이나 요구사항을 front에 주고, 그걸 수행하는 짧은 코드를 back에 담아. 코드는 백틱(`)으로 감싸."
    ),
    CODE_SYNTAX(
        CardType.CODE, "문법 의미", "코드·문법을 보고 의미나 출력 결과 말하기",
        "코드 스니펫이나 문법 요소를 front에 주고(백틱으로 감싸), 그 의미·동작·출력 결과를 back에 담아."
    ),
    FORMULA_CALC(
        CardType.FORMULA, "수식 계산", "수치가 주어진 문제를 직접 계산하기",
        "구체적인 수치가 주어진 계산 문제를 front에 주고, 답과 짧은 풀이를 back에 담아."
    ),
    FORMULA_CONCEPT(
        CardType.FORMULA, "개념·공식", "공식이나 변수·단위의 의미 떠올리기",
        "관계·정의·변수의 의미를 묻는 질문을 front에 주고, 공식이나 개념 설명을 back에 담아."
    ),
    TIMELINE_ORDER(
        CardType.TIMELINE, "사건 순서", "한 주제의 사건들을 시간 순으로 배열하기",
        "같은 주제에 속한 사건 3~4개를 시간 순서를 뒤섞어 front에 나열하고, 시간 순서대로 정렬한 결과를 연도와 함께 back에 담아."
    ),
    TIMELINE_YEAR(
        CardType.TIMELINE, "연도", "사건을 보고 일어난 연도 떠올리기",
        "사건 하나를 front에 주고, 그 사건이 일어난 연도(또는 날짜)를 back에 담아."
    );

    companion object {
        fun forType(cardType: CardType): List<CardStyle> = entries.filter { it.cardType == cardType }
    }
}

// 사용자가 첨부한 이미지(카메라 촬영/갤러리 선택 모두 이 형태로 변환돼 들어온다).
// bytes: JPEG로 압축된 실제 픽셀 데이터. mimeType: Gemini에게 "이건 JPEG야"라고 알려주는 값.
data class ImageInput(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg"
)

// Gemini가 만들어준 카드 한 장. 어떤 유형(cardType)으로 분류돼 생성됐는지도 같이 들고 있어서,
// 나중에 CardEntity.cardType 컬럼에 그대로 저장해 어떤 콘텐츠 유형이었는지 기록해둘 수 있다.
data class GeneratedCard(
    val front: String,
    val back: String,
    val cardType: String
)

// 생성 요청 한 번의 최종 결과물. "무슨 유형으로 만들었는지(typeLabel, 예: "코드" 또는
// 사용자 지정 유형 이름)"와 "그래서 만들어진 카드들"을 한 덩어리로 묶어서, ViewModel이
// 유형을 화면에 뱃지로 보여줄 수 있게 한다.
data class GenerationResult(
    val typeLabel: String,
    val cards: List<GeneratedCard>
)

// 덱 만들기 화면에서 사용자가 고른 "무엇으로 만들지".
// [문법] sealed interface
//   이 파일 안에 정의된 세 가지 경우(Auto/Builtin/Custom)만 존재할 수 있다고 컴파일러에게
//   알려주는 타입. when(target)에서 세 경우를 다 처리하면 else가 필요 없고, 경우를 빠뜨리면
//   컴파일 에러가 나서 실수를 막아준다.
sealed interface GenerationTarget {
    // 자동: Gemini가 내용을 보고 기본 유형 중 하나로 분류한 뒤, 그 유형의 형태를 전부 섞는다.
    data object Auto : GenerationTarget
    // 기본 유형 직접 선택: 체크한 세부 형태(styles)로만 만든다. 분류 호출은 생략.
    data class Builtin(val type: CardType, val styles: Set<CardStyle>) : GenerationTarget
    // 사용자 지정 유형: 그 유형의 작성 지침과 예시로 만든다.
    data class Custom(val type: CustomCardType) : GenerationTarget
}

// 실제 생성 호출에 필요한 것들을 다 정해둔 "계획". 기본 유형이든 사용자 지정 유형이든
// 여기까지 오면 똑같은 모양이라, 2단계 생성 로직은 둘을 구분할 필요가 없다.
//   storageId: 카드의 cardType 컬럼에 저장될 값 ("code" 또는 "custom:ab12cd34")
//   label: 화면 뱃지에 보여줄 이름, typeName: 시스템 지시문의 "카드 유형:" 뒤에 들어갈 이름
//   guide: 작성 지침, examples: 이미 세부 형태 필터까지 끝난 퓨샷 예시
private data class PromptPlan(
    val storageId: String,
    val label: String,
    val typeName: String,
    val guide: String,
    val examples: List<FewShotExample>
)

// 네트워크 실패나 Gemini 응답 이상 등, 사용자에게 그대로 보여줘도 되는 실패 이유를 담는 예외.
// [문법] class GeminiApiException(message: String, val retryable: Boolean = false) : Exception(message)
//   Exception을 상속해서 만든 커스텀 예외 타입. 코틀린/자바의 일반 Exception과 달리
//   "우리가 의도적으로 사용자용 메시지를 실어서 던지는 실패"라는 걸 타입으로 구분해두면,
//   catch (e: GeminiApiException) 처럼 "우리가 예상한 실패"만 따로 잡아서
//   e.message를 그대로 화면 에러 문구로 써도 안전하다고 판단할 수 있다.
//   retryable: 서버 과부하(503)나 요청 과다(429)처럼 "잠시 후 다시 하면 될 수도 있는"
//   실패인지 표시 — 잘못된 요청(400)이나 인증 실패(401) 같은 건 재시도해도 똑같이
//   실패할 게 뻔하므로 retryable=false로 즉시 포기한다.
class GeminiApiException(message: String, val retryable: Boolean = false) : Exception(message)

// Gemini의 generateContent REST API를 "2단계 프롬프트"로 호출해서 카드를 만들어내는 클래스.
//
// 1단계(분류기, classifyCardType): 입력(텍스트/이미지)이 fact/code/formula/timeline 중
//   어떤 콘텐츠 유형에 가까운지 Gemini에게 먼저 물어본다. 이 호출은 카드를 만들지 않고,
//   오직 유형 하나만 판단해서 돌려준다.
// 2단계(생성기, generateTypedCards): 1단계에서 판단된 유형 전용 few-shot 대화(유형에 맞는
//   "자료 → 카드" 예시 5쌍을 user/model 턴으로 먼저 보여주는 것, FewShotExamples.kt)로
//   실제 카드(front/back)를 생성한다.
//
// 왜 굳이 두 번 나눠서 부르는가: 하나의 프롬프트로 "아무거나 알아서 만들어줘"라고 하면
// LLM이 뒤죽박죽 스타일로 카드를 섞어 만들 수 있는데, 먼저 유형을 확정한 뒤 그 유형에 맞는
// 예시를 보여주면(few-shot) 훨씬 일관되고 형식이 맞는 카드가 나온다.
//
// [문법] responseMimeType / responseSchema (아래 performRequest에서 사용)
//   Gemini API의 옵션으로, "자유 텍스트로 대충 답하지 말고, 내가 지정한 이 JSON 구조를
//   정확히 지켜서 답해라"라고 강제하는 기능. 이게 없으면 Gemini가 "네, 여기 카드
//   목록입니다: ```json [...] ```" 처럼 사람이 읽기 좋은 잡담을 섞어서 응답할 수 있는데,
//   그러면 우리 코드가 JSON만 골라내는 별도 파싱 로직을 짜야 한다. 스키마를 강제하면
//   candidates[0].content.parts[0].text 자리에 순수 JSON 문자열만 오는 게 보장된다.
class GeminiDeckGenerator {

    // OkHttpClient: 실제 HTTP 요청을 보내는 객체. 앱 전체에서 매번 새로 만들지 않고
    // 이 클래스 인스턴스 하나당 하나씩만 두고 재사용한다(커넥션 재사용 등 성능상 이점).
    // connectTimeout/readTimeout: Gemini 응답이 원래 느릴 수 있어서(특히 이미지 포함 요청)
    // 기본값보다 넉넉하게 잡아둔 것.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // 이 클래스의 유일한 공개(public) 진입점. ViewModel은 이 함수 하나만 호출하면 되고,
    // 내부적으로 분류 → 생성 두 번의 네트워크 호출이 일어난다는 사실은 몰라도 된다.
    //
    // target: 자동 / 기본 유형 직접 선택 / 사용자 지정 유형 중 무엇으로 만들지.
    //   자동일 때만 1단계 분류 호출을 하고, 직접 골랐으면 API 호출이 1번으로 줄어든다.
    // fewShotSettings: 사용자가 설정 화면에서 만든 예시. 기본 유형의 "내 예시 사용"이 켜져
    //   있으면 내장 예시 대신 이걸 쓴다.
    //
    // [문법] suspend fun ... = withContext(Dispatchers.IO) { ... }
    //   suspend: 코루틴 안에서만 호출 가능하다는 표시 (ViewModel의 viewModelScope.launch { } 안에서 호출됨).
    //   withContext(Dispatchers.IO): 이 블록 안의 코드를 "네트워크/디스크 작업에 적합한
    //   스레드 풀"에서 실행하도록 전환한다. 안에서 부르는 classifyCardType/generateTypedCards가
    //   내부적으로 OkHttp의 blocking 호출(client.newCall(request).execute())을 쓰는데,
    //   이런 블로킹 코드는 반드시 메인(UI) 스레드가 아닌 곳에서 돌아야 화면이 멈추지 않는다.
    suspend fun generateCards(
        apiKey: String,
        content: String,
        image: ImageInput?,
        count: Int,
        language: String,
        target: GenerationTarget,
        fewShotSettings: FewShotSettings
    ): GenerationResult = withContext(Dispatchers.IO) {
        val plan = when (target) {
            GenerationTarget.Auto -> {
                val type = classifyCardType(apiKey, content, image)
                builtinPlan(type, CardStyle.forType(type), fewShotSettings)
            }
            is GenerationTarget.Builtin -> {
                // 다른 유형의 형태가 섞여 들어오는 경우까지 걸러내고, 남는 게 없으면 그 유형 전체로.
                val styles = target.styles.filter { it.cardType == target.type }
                    .ifEmpty { CardStyle.forType(target.type) }
                builtinPlan(target.type, styles, fewShotSettings)
            }
            is GenerationTarget.Custom -> customPlan(target.type)
        }
        val cards = generateTypedCards(apiKey, plan, content, image, count, language)
        GenerationResult(plan.label, cards)
    }

    // 기본 유형의 생성 계획. 예시는 "내 예시 사용"이 켜져 있으면 사용자 예시를, 아니면 내장
    // 예시를 쓰고, 어느 쪽이든 고른 세부 형태의 카드만 남긴다. 사용자 예시가 켜져 있어도
    // 고른 형태에 맞는 예시가 하나도 없으면(예: "연도" 카드만 만들어 뒀는데 "사건 순서"를 고름)
    // 내장 예시로 대신한다 — 예시가 아예 없는 것보다는 형태가 맞는 예시가 있는 편이 낫다.
    private fun builtinPlan(type: CardType, styles: List<CardStyle>, settings: FewShotSettings): PromptPlan {
        val userSetting = settings.builtinSetting(type)
        val userExamples = if (userSetting.useCustom) filterByStyles(userSetting.examples, styles) else emptyList()
        val examples = userExamples.ifEmpty { filterByStyles(FewShotExamples.forType(type), styles) }

        // 고른 형태가 하나면 그 형태만, 여러 개면 "섞어서" 만들라고 지시한다.
        val guide = if (styles.size == 1) {
            "카드 형태: ${styles[0].label} — ${styles[0].instruction} 모든 카드를 이 형태로만 만들어."
        } else {
            buildString {
                append("아래 카드 형태들을 비슷한 비율로 섞어서 만들어.")
                styles.forEach { append("\n  · ${it.label}: ${it.instruction}") }
            }
        }
        return PromptPlan(type.id, type.label, type.id, guide, examples)
    }

    // 사용자 지정 유형의 생성 계획. 작성 지침을 비워뒀으면 무난한 기본 지침을 쓰고,
    // 예시가 하나도 없으면 예시 없이(제로샷) 지침만으로 생성한다.
    private fun customPlan(type: CustomCardType): PromptPlan {
        val guide = type.instruction.trim().ifBlank { "자료의 핵심 내용을 묻는 질문을 front에, 명확한 답을 back에 담아." }
        return PromptPlan(type.storageId, type.name, type.name, "작성 지침: $guide", type.examples)
    }

    // 예시 카드 중 고른 형태(styles)의 카드만 남기고, 카드가 하나도 안 남은 예시는 통째로 뺀다.
    // style이 없는(null) 카드는 형태 구분이 없는 것으로 보고 항상 남긴다.
    private fun filterByStyles(examples: List<FewShotExample>, styles: List<CardStyle>): List<FewShotExample> =
        examples.mapNotNull { example ->
            val cards = example.cards.filter { it.style == null || it.style in styles }
            if (cards.isEmpty()) null else example.copy(cards = cards)
        }

    // ── 1단계: 콘텐츠 유형 분류 ──────────────────────────────────────────────
    // Gemini에게 "이 내용을 fact/code/formula/timeline 중 뭘로 보는 게 좋겠냐"만 물어보고,
    // 카드는 아직 하나도 만들지 않는다. 응답은 {"type": "code"} 같은 JSON 객체 하나뿐이다.
    private fun classifyCardType(apiKey: String, content: String, image: ImageInput?): CardType {
        // [문법] buildString { appendLine(...) }
        //   여러 줄의 문자열을 "+"로 이어붙이는 대신, StringBuilder를 내부적으로 써서
        //   한 줄씩 추가(appendLine은 추가 후 줄바꿈까지 넣어줌)하고 마지막에 통째로
        //   String으로 완성해주는 코틀린 표준 헬퍼. 조건부로 줄을 넣거나 뺄 때
        //   (아래처럼 content/image가 있을 때만 그 줄을 추가) 특히 편하다.
        val instruction = buildString {
            appendLine("다음 학습 자료를 분석해서, 플래시카드로 만들기 가장 적합한 콘텐츠 유형 하나를 판단해줘.")
            appendLine("- fact: 일반적인 개념/사실 설명")
            appendLine("- code: 프로그래밍 코드나 문법")
            appendLine("- formula: 수학/과학 공식이나 관계식")
            appendLine("- timeline: 역사적 사건이나 시간 순서")
            if (content.isNotBlank()) appendLine("내용: \"$content\"")
            if (image != null) appendLine("첨부된 이미지의 내용도 함께 고려해.")
        }

        // Gemini에게 강제할 응답 구조: { "type": "fact" | "code" | "formula" | "timeline" }
        // [문법] JSONObject().apply { put(...) }
        //   apply는 "이 객체를 만들자마자 블록 안에서 이런저런 설정을 하고, 그 객체 자체를
        //   결과로 돌려줘"라는 뜻의 스코프 함수. JSONObject를 변수에 담고 여러 줄에 걸쳐
        //   obj.put(...)을 반복하는 대신, 생성과 설정을 한 덩어리로 표현할 수 있다.
        // "enum": [...] 은 Gemini 스키마 문법으로 "이 STRING은 반드시 이 목록 중 하나여야
        //   한다"를 강제한다 — 즉 Gemini가 "fac"처럼 오타를 내거나 목록에 없는 값을
        //   답할 가능성을 원천적으로 줄여준다.
        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("type", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", JSONArray(CardType.entries.map { it.id }))
                })
            })
            put("required", JSONArray(listOf("type")))
        }

        // executeGenerateContent가 돌려주는 raw는 이미 "{'type':'code'}" 같은 JSON 문자열이다
        // (Gemini 응답 안의 candidates[0].content.parts[0].text 자리에서 꺼낸 값).
        // optString("type", 기본값): 그 키가 없거나 형식이 이상해도 예외 없이 기본값(fact)으로 대체.
        // 분류는 예시 없이(제로샷) 사용자 턴 하나만 보낸다.
        val contents = JSONArray().put(userTurn(instruction, image))
        val raw = executeGenerateContent(apiKey, contents, null, schema)
        val id = JSONObject(raw).optString("type", CardType.FACT.id)
        return CardType.fromId(id)
    }

    // ── 2단계: 유형 전용 few-shot 대화로 카드 생성 ─────────────────────────
    // 1단계에서 확정된 cardType에 맞는 시스템 지시문(buildSystemInstruction)과 퓨샷 예시
    // 대화(FewShotExamples)를 깔고 다시 한 번 Gemini를 호출한다. 응답으로 "카드 배열"을 받아
    // GeneratedCard 리스트로 바꾼다.
    //
    // 요청의 contents는 이런 "대화 기록" 모양이 된다:
    //   user : 예시1 자료로 만든 요청      model: 예시1 카드 JSON
    //   user : 예시2 자료로 만든 요청      model: 예시2 카드 JSON
    //   ... (유형별 5쌍)
    //   user : 실제 사용자 자료로 만든 요청 (+ 이미지)   ← 모델은 여기에 답한다
    // 예시 턴과 실제 요청 턴을 같은 함수(buildRequestText)로 만들어서 문장 형식이 완전히
    // 똑같게 맞춘다 — 형식이 같아야 모델이 "아까와 같은 종류의 요청"이라고 인식한다.
    //
    // plan.examples는 이미 세부 형태 필터가 끝난 상태다(builtinPlan 참고). 예: "연도"만 골랐으면
    // 예시에서도 "사건 순서" 카드는 빠져 있다 — 예시에 안 고른 형태가 섞여 있으면 모델이
    // 그것까지 따라 만들기 때문.
    private fun generateTypedCards(
        apiKey: String,
        plan: PromptPlan,
        content: String,
        image: ImageInput?,
        count: Int,
        language: String
    ): List<GeneratedCard> {
        val contents = JSONArray()
        plan.examples.forEach { example ->
            contents.put(userTurn(buildRequestText(example.source, hasImage = false, count = example.cards.size), null))
            contents.put(modelTurn(cardsToJson(example.cards)))
        }
        contents.put(userTurn(buildRequestText(content, image != null, count), image))
        val systemInstruction = buildSystemInstruction(plan, language)

        // 이번엔 응답 스키마가 "배열": [{front, back}, {front, back}, ...] 형태를 강제한다.
        // front/back을 모두 "required"로 지정해서, Gemini가 둘 중 하나를 빼먹는 경우를 줄인다.
        val schema = JSONObject().apply {
            put("type", "ARRAY")
            put("items", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("front", JSONObject().apply { put("type", "STRING") })
                    put("back", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray(listOf("front", "back")))
            })
        }

        val raw = executeGenerateContent(apiKey, contents, systemInstruction, schema)
        val cardsArray = JSONArray(raw)
        val result = mutableListOf<GeneratedCard>()
        // [문법] for (i in 0 until cardsArray.length())
        //   org.json의 JSONArray는 코틀린의 List처럼 for-each(for (item in array))로
        //   바로 순회할 수 없는 구식 자바 스타일 API라서, 인덱스를 0부터 length-1까지
        //   직접 돌면서 getJSONObject(i)로 하나씩 꺼내야 한다.
        for (i in 0 until cardsArray.length()) {
            val obj = cardsArray.getJSONObject(i)
            val front = obj.optString("front").trim()
            val back = obj.optString("back").trim()
            // 스키마로 required를 걸어도 Gemini가 100% 지킨다는 보장은 없어서,
            // front/back 중 하나라도 실제로 비어 있으면 그 카드는 그냥 버리고 넘어간다
            // (빈 카드가 덱에 섞여 들어가는 것보다는 장수가 조금 줄어드는 게 낫다는 판단).
            if (front.isNotEmpty() && back.isNotEmpty()) {
                result.add(GeneratedCard(front, back, plan.storageId))
            }
        }

        if (result.isEmpty()) {
            throw GeminiApiException("생성된 카드가 없습니다. 내용이나 이미지를 다시 확인해보세요.")
        }
        return result
    }

    // 시스템 지시문: 대화 턴과 별개로 "이 대화 전체에서 지켜야 할 규칙"을 담는 칸.
    // 유형별 작성 지침, 언어, 길이 제한처럼 매 요청마다 바뀌지 않는 규칙은 여기에 두고,
    // 대화 턴(user/model)에는 순수하게 "자료 → 카드" 예시와 실제 요청만 남긴다.
    private fun buildSystemInstruction(plan: PromptPlan, language: String): String {
        // guide가 여러 줄일 수 있어서 """...""".trimIndent()를 쓰지 않는다 — 끼워 넣은 줄의
        // 들여쓰기가 공통 들여쓰기 계산을 망가뜨리기 때문. 대신 줄 목록을 "\n"으로 이어 붙인다.
        return listOf(
            "너는 스페이스드 리피티션 학습용 플래시카드를 만드는 도우미야.",
            "카드 유형: ${plan.typeName}.",
            plan.guide,
            "규칙:",
            "- 모든 카드는 $language(으)로 작성해.",
            "- 사용자가 요청한 장수를 정확히 지켜.",
            "- 카드끼리 내용이 겹치지 않게 하고, front와 back은 각각 200자 이내로 간결하게 써.",
            "- 앞선 대화의 예시는 형식과 스타일 참고용이야. 예시의 주제를 다시 카드로 만들지 말고, 마지막 요청의 자료로만 만들어."
        ).joinToString("\n")
    }

    // user 턴의 요청 문장. 퓨샷 예시 턴과 실제 요청 턴이 모두 이 함수로 만들어진다.
    // 자료는 따옴표로 문장 안에 끼워 넣지 않고 "내용:" 아래 별도 줄에 둔다 — 자료 안에
    // 따옴표나 여러 줄 텍스트가 있어도 지시문과 자료의 경계가 흐려지지 않게.
    private fun buildRequestText(content: String, hasImage: Boolean, count: Int): String {
        // 이미지 유무 / 텍스트 유무 조합에 따라 "무엇을 참고해서 만들라고 할지" 문구를 바꾼다.
        // (이미지만 있고 텍스트가 없으면 "아래 내용을 바탕으로" 같은 어색한 문장이 안 나오게)
        val source = when {
            hasImage && content.isNotBlank() -> "첨부된 이미지와 아래 보충 설명을 바탕으로"
            hasImage -> "첨부된 이미지의 내용을 바탕으로"
            else -> "아래 내용을 바탕으로"
        }
        return buildString {
            append("$source 플래시카드를 정확히 ${count}장 만들어줘.")
            if (content.isNotBlank()) {
                append("\n\n내용:\n")
                append(content)
            }
        }
    }

    // 예시 카드 목록을 model 턴에 넣을 JSON 문자열([{"front":...,"back":...}, ...])로 바꾼다.
    // 실제 응답 스키마와 똑같은 모양이어야 모델이 그대로 따라 한다. JSONObject로 조립하면
    // 따옴표/역슬래시 같은 특수문자 이스케이프를 알아서 처리해준다.
    private fun cardsToJson(cards: List<ExampleCard>): String {
        val array = JSONArray()
        cards.forEach { card ->
            array.put(JSONObject().put("front", card.front).put("back", card.back))
        }
        return array.toString()
    }

    // Gemini contents 배열에 들어갈 대화 턴 하나. role은 "user"(사람) 또는 "model"(AI).
    // parts 배열: 하나의 턴 안에 텍스트와 이미지를 동시에 넣을 수 있다(멀티모달 입력).
    // 텍스트 파트는 항상 넣고, 이미지가 있을 때만 두 번째 파트로 추가한다.
    private fun userTurn(text: String, image: ImageInput?): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (image != null) {
            // [문법] inlineData { mimeType, data }
            //   이미지 원본 파일을 별도 URL로 올리는 게 아니라, 바이트 자체를 Base64
            //   문자열로 인코딩해서 요청 JSON 안에 통째로 끼워 넣는 방식.
            //   Base64.NO_WRAP: 인코딩 결과에 줄바꿈 문자를 넣지 않는 옵션. 줄바꿈이 섞이면
            //   JSON 문자열 값 안에 실제 개행이 들어가 파싱이 깨질 수 있어서 반드시 필요하다.
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", image.mimeType)
                        .put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
                )
            )
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    private fun modelTurn(text: String): JSONObject =
        JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text)))

    // ── Gemini 호출 공통 로직 ────────────────────────────────────────────────
    // classifyCardType과 generateTypedCards 둘 다 "대화 턴(contents) + (선택)시스템 지시문 +
    // 원하는 응답 스키마"만 다르고 나머지(HTTP 요청 조립, 전송, 에러 처리, 응답에서 text 뽑기)는
    // 완전히 똑같아서, 그 공통 부분을 performRequest 함수 하나로 모아뒀다.
    //
    // executeGenerateContent 자신은 실제 요청을 보내지 않고, "실패하면 조건에 따라 다시
    // 시도할지"만 판단하는 재시도 껍데기다. Gemini의 lite 모델도 무료 티어에서는
    // 순간적으로 503(과부하)이 뜨는 경우가 잦다고 알려져 있어서, 사용자가 매번 손으로
    // "생성하기"를 다시 누르지 않아도 되도록 여기서 자동으로 몇 번 더 두드려본다.
    // 지연 시간(5초 → 15초 → 45초)은 Google이 권장하는 지수 백오프 패턴을 따른 것.
    private fun executeGenerateContent(
        apiKey: String,
        contents: JSONArray,
        systemInstruction: String?,
        schema: JSONObject
    ): String {
        // [문법] RETRY_DELAYS_MS.forEachIndexed / for (delayMs in RETRY_DELAYS_MS)
        //   아래 반복문은 "최초 시도 1번 + 재시도 최대 RETRY_DELAYS_MS.size번"을 수행한다.
        //   재시도할 가치가 없는 실패(retryable=false)거나 마지막 재시도까지 다 써버리면
        //   그 예외를 그대로 밖으로 던진다.
        var lastException: GeminiApiException? = null
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                return performRequest(apiKey, contents, systemInstruction, schema)
            } catch (e: GeminiApiException) {
                lastException = e
                if (!e.retryable || attempt == RETRY_DELAYS_MS.size) throw e
                Thread.sleep(RETRY_DELAYS_MS[attempt])
            }
        }
        // 이론상 도달하지 않는 코드(루프 안에서 항상 return이나 throw로 끝남)지만,
        // 컴파일러에게 "이 함수는 항상 String을 반환하거나 예외를 던진다"를 보장해주기 위한 안전망.
        throw lastException ?: GeminiApiException("알 수 없는 오류로 요청에 실패했습니다.")
    }

    // 실제로 HTTP 요청 한 번을 조립해서 보내고 응답을 파싱하는 부분.
    // (재시도 로직과 "진짜 네트워크 작업"을 함수로 분리해두면 각각이 하는 일이 더 분명해진다)
    private fun performRequest(
        apiKey: String,
        contents: JSONArray,
        systemInstruction: String?,
        schema: JSONObject
    ): String {
        // Gemini generateContent 요청의 최상위 구조:
        // { "systemInstruction": { "parts": [...] },        ← 있을 때만
        //   "contents": [ { "role": "user"|"model", "parts": [...] }, ... ],
        //   "generationConfig": { responseMimeType, responseSchema } }
        val requestBody = JSONObject().apply {
            if (systemInstruction != null) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                }
            )
        }

        // [문법] Request.Builder().url(...).post(...).build()
        //   OkHttp에서 HTTP 요청 객체를 만드는 표준 빌더 패턴. API 키는 쿼리 파라미터
        //   (?key=...)로 붙인다 — Gemini REST API가 정한 인증 방식.
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = try {
            // [문법] client.newCall(request).execute().use { response -> ... }
            //   execute()는 이 스레드를 막고(blocking) 응답이 올 때까지 기다리는 동기 호출.
            //   .use { }는 Kotlin의 Closeable 확장 함수로, 블록이 끝나면(정상 종료든 예외든)
            //   response를 자동으로 close()해준다 — try/finally를 직접 안 써도 리소스 누수 방지.
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // HTTP 상태 코드가 2xx가 아니면(예: 400 잘못된 요청, 404 모델 없음,
                    // 429 요청 과다, 503 서버 과부하 등) 본문에서 에러 메시지를 뽑아 예외로 던진다.
                    // 503(서비스 불가)과 429(요청 과다)는 "지금은 안 되지만 잠시 후엔 될 수도
                    // 있는" 일시적 실패라서 retryable=true로 표시해 위 executeGenerateContent가
                    // 자동으로 재시도하게 한다. 그 외(400/401/404 등)는 재시도해도 똑같이
                    // 실패할 사용자/설정 문제라 retryable=false로 즉시 포기시킨다.
                    val retryable = response.code == 503 || response.code == 429
                    throw GeminiApiException(parseErrorMessage(bodyString, response.code), retryable)
                }
                bodyString
            }
        } catch (e: IOException) {
            // 요청 자체가 서버에 도달하지 못한 경우(기기가 오프라인, DNS 실패, 타임아웃 등).
            // 이때는 HTTP 응답 자체가 없으므로 위의 response.code 분기와는 별도로 처리해야 한다.
            throw GeminiApiException("네트워크 연결에 실패했습니다: ${e.message}")
        }

        return extractResponseText(responseText)
    }

    // Gemini 응답 JSON에서 "우리가 진짜 원하는 텍스트"만 꺼낸다.
    // 실제 Gemini 응답 구조는 대략 이런 모양이다:
    // { "candidates": [ { "content": { "parts": [ { "text": "...실제 응답 JSON 문자열..." } ] } } ] }
    // 즉 우리가 스키마로 강제한 JSON(카드 배열이나 {"type":...})은 이 depth 4짜리 구조
    // 안쪽의 "text" 필드에 "문자열로서" 들어있다 — 그래서 이 함수가 반환하는 값도 여전히
    // "JSON 형식의 문자열"이고, 호출한 쪽(classifyCardType/generateTypedCards)이
    // 그걸 다시 JSONObject(raw)/JSONArray(raw)로 한 번 더 파싱해야 한다.
    private fun extractResponseText(responseText: String): String {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            // candidates가 비어있는 경우는 보통 안전 필터에 걸려 응답이 차단됐거나,
            // 요청 자체가 이상해서 Gemini가 아무 결과도 안 준 상황이다.
            throw GeminiApiException("Gemini 응답에 결과가 없습니다.")
        }
        val content = candidates.getJSONObject(0).getJSONObject("content")
        return content.getJSONArray("parts").getJSONObject(0).getString("text")
    }

    // HTTP 에러 응답 본문에서 사람이 읽을 수 있는 에러 메시지를 최대한 뽑아낸다.
    // Gemini 에러 응답은 보통 { "error": { "code": ..., "message": "...", "status": "..." } }
    // 형태라서, error.message를 우선 시도하고 실패하면(형식이 다르거나 파싱 에러) 그냥
    // "HTTP 코드"만 담은 기본 문구로 대체한다 — 에러 처리 중에 또 에러가 나서 앱이
    // 죽는 일은 없어야 하므로 try/catch로 한 번 더 감싸둔 것.
    private fun parseErrorMessage(bodyString: String, code: Int): String {
        return try {
            val message = JSONObject(bodyString).optJSONObject("error")?.optString("message")
            if (!message.isNullOrBlank()) message else "요청이 실패했습니다. (HTTP $code)"
        } catch (e: Exception) {
            "요청이 실패했습니다. (HTTP $code)"
        }
    }

    companion object {
        // 사용 중인 Gemini 모델. 모델 이름은 Google이 새 모델을 내놓거나 구모델을 신규
        // 사용자에게 막을 때(실제로 gemini-2.5-flash가 그래서 막혔었다) 바뀔 수 있는
        // 값이라, 코드 여기저기 흩어놓지 않고 상수 하나로 관리한다.
        //
        // gemini-3.6-flash(비-lite)는 무료 티어에서 503(과부하) 응답 비율이 매우 높다고
        // 널리 보고돼 있고, 하루 요청 한도도 20건뿐이라(우리는 생성 1번에 분류+생성 2회
        // 호출) 실질적으로 하루 10번도 못 쓰는 셈이었다. lite 계열은 같은 무료 티어에서도
        // 하루 500건까지 허용되고 더 안정적으로 응답한다고 알려져 있어 이걸로 바꿨다.
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

        // 503/429로 실패했을 때 재시도까지 기다리는 시간(밀리초). 배열 길이만큼 재시도하고,
        // 시도할 때마다 간격을 늘려서(5초 → 15초 → 45초) 일시적 과부하가 풀릴 시간을 벌어준다.
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)
    }
}
