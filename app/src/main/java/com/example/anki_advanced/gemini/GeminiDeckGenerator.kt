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

// 입력 내용을 분석해서 LLM이 "이 내용은 이런 식으로 카드를 만드는 게 좋겠다"고 고르는 콘텐츠 유형.
// 유형마다 프롬프트에 넣는 few-shot 예시와 지시문이 달라서, 같은 "카드 생성"이라도 결과 품질이 달라진다.
enum class CardType(val id: String, val label: String) {
    FACT("fact", "일반 개념"),
    CODE("code", "코드"),
    FORMULA("formula", "수식"),
    TIMELINE("timeline", "연표");

    companion object {
        fun fromId(id: String): CardType = entries.firstOrNull { it.id == id } ?: FACT
    }
}

// 사용자가 첨부한 이미지(카메라 촬영/갤러리 선택 모두 이 형태로 들어온다).
data class ImageInput(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg"
)

// Gemini가 만들어준 카드 한 장. 어떤 유형으로 분류돼 생성됐는지도 같이 들고 있는다.
data class GeneratedCard(
    val front: String,
    val back: String,
    val cardType: String
)

// 생성 요청 한 번의 최종 결과: 분류된 콘텐츠 유형 + 카드 목록.
data class GenerationResult(
    val cardType: CardType,
    val cards: List<GeneratedCard>
)

// 네트워크 실패나 Gemini 응답 이상 등, 사용자에게 보여줄 이유가 있는 실패를 표현하는 예외.
class GeminiApiException(message: String) : Exception(message)

// Gemini의 generateContent REST API를 "2단계 프롬프트"로 호출해서 카드를 만들어내는 클래스.
// 1단계(분류기): 입력(텍스트/이미지)이 어떤 콘텐츠 유형(fact/code/formula/timeline)인지 판단.
// 2단계(생성기): 판단된 유형 전용 few-shot 프롬프트로 실제 카드(front/back)를 생성.
// [문법] responseMimeType/responseSchema
//   Gemini에게 "자유 텍스트 말고, 이 JSON 구조를 딱 지켜서 답해라"라고 강제하는 옵션.
class GeminiDeckGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateCards(
        apiKey: String,
        topic: String,
        image: ImageInput?,
        count: Int,
        language: String
    ): GenerationResult = withContext(Dispatchers.IO) {
        val cardType = classifyCardType(apiKey, topic, image)
        val cards = generateTypedCards(apiKey, cardType, topic, image, count, language)
        GenerationResult(cardType, cards)
    }

    // ── 1단계: 콘텐츠 유형 분류 ──────────────────────────────────────────────
    private fun classifyCardType(apiKey: String, topic: String, image: ImageInput?): CardType {
        val instruction = buildString {
            appendLine("다음 학습 자료를 분석해서, 플래시카드로 만들기 가장 적합한 콘텐츠 유형 하나를 판단해줘.")
            appendLine("- fact: 일반적인 개념/사실 설명")
            appendLine("- code: 프로그래밍 코드나 문법")
            appendLine("- formula: 수학/과학 공식이나 관계식")
            appendLine("- timeline: 역사적 사건이나 시간 순서")
            if (topic.isNotBlank()) appendLine("주제 또는 텍스트: \"$topic\"")
            if (image != null) appendLine("첨부된 이미지의 내용도 함께 고려해.")
        }

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

        val raw = executeGenerateContent(apiKey, instruction, image, schema)
        val id = JSONObject(raw).optString("type", CardType.FACT.id)
        return CardType.fromId(id)
    }

    // ── 2단계: 유형 전용 few-shot 프롬프트로 카드 생성 ──────────────────────
    private fun generateTypedCards(
        apiKey: String,
        cardType: CardType,
        topic: String,
        image: ImageInput?,
        count: Int,
        language: String
    ): List<GeneratedCard> {
        val prompt = buildTypedPrompt(cardType, topic, image != null, count, language)

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
        for (i in 0 until cardsArray.length()) {
            val obj = cardsArray.getJSONObject(i)
            val front = obj.optString("front").trim()
            val back = obj.optString("back").trim()
            if (front.isNotEmpty() && back.isNotEmpty()) {
                result.add(GeneratedCard(front, back, cardType.id))
            }
        }

        if (result.isEmpty()) {
            throw GeminiApiException("생성된 카드가 없습니다. 주제나 이미지를 다시 확인해보세요.")
        }
        return result
    }

    private fun buildTypedPrompt(
        cardType: CardType,
        topic: String,
        hasImage: Boolean,
        count: Int,
        language: String
    ): String {
        val source = when {
            hasImage && topic.isNotBlank() -> "아래 이미지와 보충 설명(\"$topic\")을 참고해서"
            hasImage -> "첨부된 이미지의 내용을 참고해서"
            else -> "다음 주제(\"$topic\")에 대해"
        }

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

        return """
            $source 스페이스드 리피티션 학습용 플래시카드를 정확히 $count 장 만들어줘.
            카드 유형: ${cardType.id}. $guide
            모든 카드는 $language 로 작성하고, 중복 없이, 각 항목은 200자 이내로 간결하게 써.
            아래는 형식 참고용 예시일 뿐이니 내용은 새로 만들어: $example
        """.trimIndent()
    }

    // ── Gemini 호출 공통 로직 ────────────────────────────────────────────────
    // parts에 텍스트(+ 있으면 이미지)를 담아 요청하고, responseSchema로 정해둔 JSON 문자열을 그대로 반환.
    // 호출한 쪽(classifyCardType/generateTypedCards)이 이 문자열을 JSONObject/JSONArray로 다시 파싱한다.
    private fun executeGenerateContent(
        apiKey: String,
        instructionText: String,
        image: ImageInput?,
        schema: JSONObject
    ): String {
        val parts = JSONArray().put(JSONObject().apply { put("text", instructionText) })
        if (image != null) {
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

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GeminiApiException(parseErrorMessage(bodyString, response.code))
                }
                bodyString
            }
        } catch (e: IOException) {
            throw GeminiApiException("네트워크 연결에 실패했습니다: ${e.message}")
        }

        return extractResponseText(responseText)
    }

    private fun extractResponseText(responseText: String): String {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw GeminiApiException("Gemini 응답에 결과가 없습니다.")
        }
        val content = candidates.getJSONObject(0).getJSONObject("content")
        return content.getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun parseErrorMessage(bodyString: String, code: Int): String {
        return try {
            val message = JSONObject(bodyString).optJSONObject("error")?.optString("message")
            if (!message.isNullOrBlank()) message else "요청이 실패했습니다. (HTTP $code)"
        } catch (e: Exception) {
            "요청이 실패했습니다. (HTTP $code)"
        }
    }

    companion object {
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent"
    }
}
