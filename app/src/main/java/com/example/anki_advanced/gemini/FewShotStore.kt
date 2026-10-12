package com.example.anki_advanced.gemini

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// 사용자가 "퓨샷 예시 설정" 화면에서 만든 예시들을 기기에 저장하는 저장소.
//
// 저장하는 것은 두 가지다:
//   1) 기본 유형(fact/code/formula/timeline)마다 "내 예시 사용" 스위치 + 내 예시 목록.
//      스위치를 켜고 예시가 하나라도 있으면, 앱에 내장된 기본 예시(FewShotExamples) 대신
//      내 예시가 Gemini에 전송된다.
//   2) 사용자 지정 유형 목록. 이름 + 작성 지침 + 예시를 가진, 사용자가 새로 만든 카드 유형.
//      덱 만들기 화면에서 기본 유형 옆에 칩으로 나타난다.
//
// 왜 Room DB가 아니라 SharedPreferences(JSON 문자열 하나)인가:
//   예시는 많아야 수십 개짜리 작은 설정 데이터이고, 항상 "통째로 읽고 통째로 저장"하기 때문에
//   테이블로 쪼갤 이유가 없다. DB 버전을 올리고 Migration을 추가하는 부담도 피할 수 있다.

// 기본 유형 하나에 대한 사용자 설정.
data class BuiltinFewShotSetting(
    val useCustom: Boolean = false,
    val examples: List<FewShotExample> = emptyList()
)

// 사용자가 직접 만든 카드 유형.
//   id: 이름을 바꿔도 변하지 않는 고유값. 생성된 카드의 cardType 컬럼에 "custom:<id>"로 저장된다.
//   instruction: Gemini 시스템 지시문에 들어가는 작성 지침 (예: "영단어를 front에, 뜻과 예문을 back에").
data class CustomCardType(
    val id: String,
    val name: String,
    val instruction: String,
    val examples: List<FewShotExample>
) {
    val storageId: String get() = "custom:$id"

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}

data class FewShotSettings(
    val builtin: Map<CardType, BuiltinFewShotSetting> = emptyMap(),
    val customTypes: List<CustomCardType> = emptyList()
) {
    fun builtinSetting(type: CardType): BuiltinFewShotSetting = builtin[type] ?: BuiltinFewShotSetting()
    fun customType(id: String): CustomCardType? = customTypes.firstOrNull { it.id == id }
}

// [문법] private constructor + companion object { getInstance() }
//   AppDatabase와 같은 싱글턴 패턴. 덱 만들기 화면과 설정 화면이 서로 다른 ViewModel을 쓰지만
//   같은 인스턴스(같은 StateFlow)를 바라보게 해서, 설정 화면에서 저장하면 덱 만들기 화면의
//   유형 칩 목록이 따로 새로고침하지 않아도 즉시 바뀐다.
class FewShotStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FewShotSettings> = _settings.asStateFlow()

    // 현재 설정을 받아 새 설정을 돌려주는 함수(transform)를 적용하고, 그 결과를 바로 디스크에 쓴다.
    fun update(transform: (FewShotSettings) -> FewShotSettings) {
        _settings.update(transform)
        prefs.edit().putString(KEY_SETTINGS, toJson(_settings.value).toString()).apply()
    }

    private fun load(): FewShotSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return FewShotSettings()
        // 저장된 JSON이 깨져 있어도 앱이 죽지 않고 빈 설정으로 시작하게 한다.
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            FewShotSettings()
        }
    }

    companion object {
        private const val PREFS_NAME = "few_shot_prefs"
        private const val KEY_SETTINGS = "settings_json"

        @Volatile
        private var instance: FewShotStore? = null

        fun getInstance(context: Context): FewShotStore =
            instance ?: synchronized(this) {
                instance ?: FewShotStore(context).also { instance = it }
            }

        // ── JSON 변환 ─────────────────────────────────────────────────────────
        // 저장 형식:
        // { "builtin": { "fact": { "useCustom": true, "examples": [...] }, ... },
        //   "customTypes": [ { "id", "name", "instruction", "examples": [...] } ] }
        // example: { "source": "...", "cards": [ { "front", "back", "style"(선택, CardStyle 이름) } ] }

        private fun toJson(settings: FewShotSettings): JSONObject {
            val builtin = JSONObject()
            settings.builtin.forEach { (type, setting) ->
                builtin.put(
                    type.id,
                    JSONObject()
                        .put("useCustom", setting.useCustom)
                        .put("examples", examplesToJson(setting.examples))
                )
            }
            val customTypes = JSONArray()
            settings.customTypes.forEach { type ->
                customTypes.put(
                    JSONObject()
                        .put("id", type.id)
                        .put("name", type.name)
                        .put("instruction", type.instruction)
                        .put("examples", examplesToJson(type.examples))
                )
            }
            return JSONObject().put("builtin", builtin).put("customTypes", customTypes)
        }

        private fun examplesToJson(examples: List<FewShotExample>): JSONArray {
            val array = JSONArray()
            examples.forEach { example ->
                val cards = JSONArray()
                example.cards.forEach { card ->
                    val obj = JSONObject().put("front", card.front).put("back", card.back)
                    card.style?.let { obj.put("style", it.name) }
                    cards.put(obj)
                }
                array.put(JSONObject().put("source", example.source).put("cards", cards))
            }
            return array
        }

        private fun fromJson(root: JSONObject): FewShotSettings {
            val builtin = mutableMapOf<CardType, BuiltinFewShotSetting>()
            root.optJSONObject("builtin")?.let { obj ->
                CardType.entries.forEach { type ->
                    obj.optJSONObject(type.id)?.let { setting ->
                        builtin[type] = BuiltinFewShotSetting(
                            useCustom = setting.optBoolean("useCustom"),
                            examples = examplesFromJson(setting.optJSONArray("examples"))
                        )
                    }
                }
            }
            val customTypes = mutableListOf<CustomCardType>()
            root.optJSONArray("customTypes")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    customTypes.add(
                        CustomCardType(
                            id = obj.optString("id").ifBlank { CustomCardType.newId() },
                            name = obj.optString("name"),
                            instruction = obj.optString("instruction"),
                            examples = examplesFromJson(obj.optJSONArray("examples"))
                        )
                    )
                }
            }
            return FewShotSettings(builtin, customTypes)
        }

        private fun examplesFromJson(array: JSONArray?): List<FewShotExample> {
            if (array == null) return emptyList()
            val result = mutableListOf<FewShotExample>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cardsArray = obj.optJSONArray("cards") ?: JSONArray()
                val cards = mutableListOf<ExampleCard>()
                for (j in 0 until cardsArray.length()) {
                    val card = cardsArray.getJSONObject(j)
                    // [문법] CardStyle.entries.firstOrNull { it.name == ... }
                    //   저장된 이름이 없거나(사용자 지정 유형) 앱 업데이트로 사라진 이름이면 null.
                    val styleName = card.optString("style")
                    cards.add(
                        ExampleCard(
                            front = card.optString("front"),
                            back = card.optString("back"),
                            style = CardStyle.entries.firstOrNull { it.name == styleName }
                        )
                    )
                }
                result.add(FewShotExample(obj.optString("source"), cards))
            }
            return result
        }
    }
}
