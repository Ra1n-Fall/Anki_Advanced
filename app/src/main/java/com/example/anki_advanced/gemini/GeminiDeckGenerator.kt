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

// 생성 요청 한 번의 최종 결과물. "무슨 유형으로 판단했는지"와 "그래서 만들어진 카드들"을
// 한 덩어리로 묶어서, ViewModel이 감지된 유형을 화면에 뱃지로 보여줄 수 있게 한다.
data class GenerationResult(
    val cardType: CardType,
    val cards: List<GeneratedCard>
)

// 네트워크 실패나 Gemini 응답 이상 등, 사용자에게 그대로 보여줘도 되는 실패 이유를 담는 예외.
// [문법] class GeminiApiException(message: String) : Exception(message)
//   Exception을 상속해서 만든 커스텀 예외 타입. 코틀린/자바의 일반 Exception과 달리
//   "우리가 의도적으로 사용자용 메시지를 실어서 던지는 실패"라는 걸 타입으로 구분해두면,
//   catch (e: GeminiApiException) 처럼 "우리가 예상한 실패"만 따로 잡아서
//   e.message를 그대로 화면 에러 문구로 써도 안전하다고 판단할 수 있다.
class GeminiApiException(message: String) : Exception(message)

// Gemini의 generateContent REST API를 "2단계 프롬프트"로 호출해서 카드를 만들어내는 클래스.
//
// 1단계(분류기, classifyCardType): 입력(텍스트/이미지)이 fact/code/formula/timeline 중
//   어떤 콘텐츠 유형에 가까운지 Gemini에게 먼저 물어본다. 이 호출은 카드를 만들지 않고,
//   오직 유형 하나만 판단해서 돌려준다.
// 2단계(생성기, generateTypedCards): 1단계에서 판단된 유형 전용 few-shot 프롬프트(유형에
//   맞는 예시 카드 하나를 프롬프트에 끼워 보여주는 것)로 실제 카드(front/back)를 생성한다.
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
        language: String
    ): GenerationResult = withContext(Dispatchers.IO) {
        val cardType = classifyCardType(apiKey, content, image)
        val cards = generateTypedCards(apiKey, cardType, content, image, count, language)
        GenerationResult(cardType, cards)
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
        val raw = executeGenerateContent(apiKey, instruction, image, schema)
        val id = JSONObject(raw).optString("type", CardType.FACT.id)
        return CardType.fromId(id)
    }

    // ── 2단계: 유형 전용 few-shot 프롬프트로 카드 생성 ──────────────────────
    // 1단계에서 확정된 cardType에 맞는 지시문 + 예시(buildTypedPrompt)를 만들어 다시 한 번
    // Gemini를 호출하고, 이번엔 응답으로 "카드 배열"을 받아 GeneratedCard 리스트로 바꾼다.
    private fun generateTypedCards(
        apiKey: String,
        cardType: CardType,
        content: String,
        image: ImageInput?,
        count: Int,
        language: String
    ): List<GeneratedCard> {
        val prompt = buildTypedPrompt(cardType, content, image != null, count, language)

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

        val raw = executeGenerateContent(apiKey, prompt, image, schema)
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
                result.add(GeneratedCard(front, back, cardType.id))
            }
        }

        if (result.isEmpty()) {
            throw GeminiApiException("생성된 카드가 없습니다. 내용이나 이미지를 다시 확인해보세요.")
        }
        return result
    }

    // cardType에 따라 다른 지시문 + few-shot 예시 하나를 조합해서 최종 프롬프트 문자열을 만든다.
    // (few-shot: "정답 예시를 하나 미리 보여주고 그 형식/스타일을 따라 하게 하는" 프롬프트 기법)
    private fun buildTypedPrompt(
        cardType: CardType,
        content: String,
        hasImage: Boolean,
        count: Int,
        language: String
    ): String {
        // 이미지 유무 / 텍스트 유무 조합에 따라 "무엇을 참고해서 만들라고 할지" 문구를 바꾼다.
        // (이미지만 있고 텍스트가 없으면 "다음 내용을 바탕으로" 같은 어색한 문장이 안 나오게)
        val source = when {
            hasImage && content.isNotBlank() -> "아래 이미지와 보충 설명(\"$content\")을 참고해서"
            hasImage -> "첨부된 이미지의 내용을 참고해서"
            else -> "다음 내용(\"$content\")을 바탕으로"
        }

        // [문법] when (cardType) { ... } 각 분기가 "A to B" (Pair) 를 반환 → (guide, example)로 구조 분해
        //   "A to B"는 Pair(A, B)를 만드는 코틀린의 짧은 표기법. when 식 전체의 결과가
        //   Pair<String, String>이 되고, 그걸 val (guide, example) = ... 로 한 번에
        //   두 변수(guide, example)로 풀어서 받는다. guide는 카드 작성 지침 문장,
        //   example은 "이런 형식으로 만들어라"를 보여주는 실제 JSON 예시 문자열.
        val (guide, example) = when (cardType) {
            CardType.FACT -> "핵심 개념을 묻는 질문을 front에, 명확한 답변을 back에 담아." to
                """[{"front":"광합성이 일어나는 세포 소기관은?","back":"엽록체"}]"""
            CardType.CODE -> "코드 스니펫이나 문법을 front에, 동작 설명이나 출력 결과를 back에 담아." to
                """[{"front":"파이썬 리스트 컴프리헨션 [x*2 for x in range(3)]의 결과는?","back":"[0, 2, 4]"}]"""
            CardType.FORMULA -> "관계나 정의를 묻는 질문을 front에, 정확한 공식을 back에 담아." to
                """[{"front":"피타고라스 정리에서 세 변의 관계는?","back":"a² + b² = c²"}]"""
            CardType.TIMELINE -> "사건이나 시점을 묻는 질문을 front에, 정확한 시기/순서를 back에 담아." to
                """[{"front":"세종대왕이 훈민정음을 반포한 연도는?","back":"1446년"}]"""
        }

        // [문법] """ ... """.trimIndent()
        //   여러 줄 문자열(raw string) 안에서는 소스 코드의 들여쓰기가 그대로 문자열에
        //   포함되는데, trimIndent()가 각 줄의 공통 들여쓰기를 계산해서 잘라내
        //   "코드는 예쁘게 들여써도, 실제 프롬프트 문자열은 왼쪽 정렬된 깔끔한 텍스트"가 되게 한다.
        return """
            $source 스페이스드 리피티션 학습용 플래시카드를 정확히 $count 장 만들어줘.
            카드 유형: ${cardType.id}. $guide
            모든 카드는 $language 로 작성하고, 중복 없이, 각 항목은 200자 이내로 간결하게 써.
            아래는 형식 참고용 예시일 뿐이니 내용은 새로 만들어: $example
        """.trimIndent()
    }

    // ── Gemini 호출 공통 로직 ────────────────────────────────────────────────
    // classifyCardType과 generateTypedCards 둘 다 "지시문 텍스트 + (선택)이미지 + 원하는
    // 응답 스키마"만 다르고 나머지(HTTP 요청 조립, 전송, 에러 처리, 응답에서 text 뽑기)는
    // 완전히 똑같아서, 그 공통 부분을 이 함수 하나로 모아뒀다.
    private fun executeGenerateContent(
        apiKey: String,
        instructionText: String,
        image: ImageInput?,
        schema: JSONObject
    ): String {
        // Gemini의 "parts" 배열: 하나의 요청 안에 텍스트와 이미지를 동시에 넣을 수 있다
        // (멀티모달 입력). 텍스트 파트는 항상 넣고, 이미지가 있을 때만 두 번째 파트로 추가한다.
        val parts = JSONArray().put(JSONObject().apply { put("text", instructionText) })
        if (image != null) {
            // [문법] inlineData { mimeType, data }
            //   이미지 원본 파일을 별도 URL로 올리는 게 아니라, 바이트 자체를 Base64
            //   문자열로 인코딩해서 요청 JSON 안에 통째로 끼워 넣는 방식.
            //   Base64.NO_WRAP: 인코딩 결과에 줄바꿈 문자를 넣지 않는 옵션. 줄바꿈이 섞이면
            //   JSON 문자열 값 안에 실제 개행이 들어가 파싱이 깨질 수 있어서 반드시 필요하다.
            parts.put(
                JSONObject().apply {
                    put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", image.mimeType)
                            put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
                        }
                    )
                }
            )
        }

        // Gemini generateContent 요청의 최상위 구조:
        // { "contents": [ { "parts": [...] } ], "generationConfig": { responseMimeType, responseSchema } }
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply { put("parts", parts) }))
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
                    // 429 요청 과다 등) 본문에서 에러 메시지를 뽑아 예외로 던진다.
                    throw GeminiApiException(parseErrorMessage(bodyString, response.code))
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
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent"
    }
}
