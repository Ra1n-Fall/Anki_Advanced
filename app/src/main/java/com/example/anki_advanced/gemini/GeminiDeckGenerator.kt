package com.example.anki_advanced.gemini

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

// Gemini가 만들어준 카드 한 장(앞면/뒷면).
data class GeneratedCard(
    val front: String,
    val back: String
)

// 네트워크 실패나 Gemini 응답 이상 등, 사용자에게 보여줄 이유가 있는 실패를 표현하는 예외.
class GeminiApiException(message: String) : Exception(message)

// Gemini의 generateContent REST API를 호출해서 "주제 → 카드 목록"을 만들어내는 클래스.
// [문법] responseMimeType/responseSchema
//   Gemini에게 "자유 텍스트 말고, 이 JSON 구조를 딱 지켜서 답해라"라고 강제하는 옵션.
//   덕분에 마크다운 코드블록이나 잡담 없이 파싱 가능한 JSON 배열만 돌아온다.
class GeminiDeckGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateCards(
        apiKey: String,
        topic: String,
        count: Int,
        language: String
    ): List<GeneratedCard> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(
                buildRequestBody(topic, count, language)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            )
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

        parseGeneratedCards(responseText)
    }

    private fun buildRequestBody(topic: String, count: Int, language: String): JSONObject {
        val prompt = """
            You are creating flashcards for a spaced-repetition study app.
            Topic: "$topic"
            Create exactly $count flashcards about this topic, written in $language.
            Each card needs a short "front" (question or term) and a concise "back" (answer or definition).
            Avoid duplicate cards and keep each side under 200 characters.
        """.trimIndent()

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

        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                }
            )
        }
    }

    private fun parseGeneratedCards(responseText: String): List<GeneratedCard> {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw GeminiApiException("Gemini 응답에 결과가 없습니다.")
        }

        val content = candidates.getJSONObject(0).getJSONObject("content")
        val jsonText = content.getJSONArray("parts").getJSONObject(0).getString("text")

        val cardsArray = JSONArray(jsonText)
        val result = mutableListOf<GeneratedCard>()
        for (i in 0 until cardsArray.length()) {
            val obj = cardsArray.getJSONObject(i)
            val front = obj.optString("front").trim()
            val back = obj.optString("back").trim()
            if (front.isNotEmpty() && back.isNotEmpty()) {
                result.add(GeneratedCard(front, back))
            }
        }

        if (result.isEmpty()) {
            throw GeminiApiException("생성된 카드가 없습니다. 주제를 다시 입력해보세요.")
        }
        return result
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
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }
}
