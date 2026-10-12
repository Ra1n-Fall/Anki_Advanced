package com.example.anki_advanced.gemini

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// 퓨샷 예시 설정 화면들의 ViewModel 두 개.
//   FewShotSettingsViewModel: 목록 화면 — 기본 유형별 "내 예시 사용" 스위치, 사용자 지정 유형 목록/삭제.
//   FewShotEditViewModel: 편집 화면 — 기본 유형 하나 또는 사용자 지정 유형 하나의 예시를 고친다.
//
// 둘 다 FewShotStore(앱 전체 싱글턴)를 통해 읽고 쓰기 때문에, 편집 화면에서 저장하고 돌아오면
// 목록 화면과 덱 만들기 화면에 자동으로 반영된다.

class FewShotSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = FewShotStore.getInstance(application)
    val settings: StateFlow<FewShotSettings> = store.settings

    fun setUseCustom(type: CardType, enabled: Boolean) {
        store.update { settings ->
            val current = settings.builtinSetting(type)
            settings.copy(builtin = settings.builtin + (type to current.copy(useCustom = enabled)))
        }
    }

    fun deleteCustomType(id: String) {
        store.update { settings -> settings.copy(customTypes = settings.customTypes.filterNot { it.id == id }) }
    }
}

// ── 편집 화면 상태 ──────────────────────────────────────────────────────────
// 편집 중인 값은 "저장"을 누르기 전까지 저장소에 쓰지 않고 이 초안(draft)에만 담아둔다 —
// 입력 도중 뒤로 가면 아무것도 바뀌지 않게.

// 예시 카드에는 세부 형태(개념 맞히기/설명하기 등)를 붙이지 않는다 — 세부 형태는 예시가 아니라
// 유형 하나에 대한 선택이고, 덱 만들기 화면의 체크박스에서 정한다.
data class DraftCard(
    val front: String = "",
    val back: String = ""
)

data class DraftExample(
    val source: String = "",
    val cards: List<DraftCard> = emptyList()
)

data class FewShotEditUiState(
    val loaded: Boolean = false,
    val builtinType: CardType? = null,   // 기본 유형 편집이면 그 유형, 사용자 지정 유형이면 null
    val customId: String? = null,        // 사용자 지정 유형 id (새로 만드는 중이면 null)
    val useCustom: Boolean = false,      // 기본 유형 전용: "내 예시 사용" 스위치
    val name: String = "",               // 사용자 지정 유형 전용: 유형 이름
    val instruction: String = "",        // 사용자 지정 유형 전용: 작성 지침
    val examples: List<DraftExample> = emptyList(),
    val errorMessage: String? = null,
    val saved: Boolean = false           // true가 되면 화면이 자동으로 닫힌다
) {
    val isCustom: Boolean get() = builtinType == null
}

class FewShotEditViewModel(application: Application) : AndroidViewModel(application) {

    private val store = FewShotStore.getInstance(application)

    private val _uiState = MutableStateFlow(FewShotEditUiState())
    val uiState: StateFlow<FewShotEditUiState> = _uiState.asStateFlow()

    // 화면 진입 시 한 번 호출. key 형식:
    //   "builtin:code"      → 기본 유형(code)의 내 예시 편집
    //   "custom:<id>"       → 기존 사용자 지정 유형 편집
    //   "custom:new"        → 사용자 지정 유형 새로 만들기
    // 화면이 회전 등으로 다시 그려져도 편집 중인 내용이 날아가지 않게, 이미 불러왔으면 무시한다.
    fun load(key: String) {
        if (_uiState.value.loaded) return
        val settings = store.settings.value
        val (kind, id) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }

        _uiState.value = if (kind == "builtin") {
            val type = CardType.fromId(id)
            val setting = settings.builtinSetting(type)
            FewShotEditUiState(
                loaded = true,
                builtinType = type,
                useCustom = setting.useCustom,
                examples = setting.examples.map { it.toDraft() }
            )
        } else {
            val existing = settings.customType(id)
            FewShotEditUiState(
                loaded = true,
                customId = existing?.id,
                name = existing?.name.orEmpty(),
                instruction = existing?.instruction.orEmpty(),
                // 새 유형이면 빈 예시 하나를 미리 펼쳐둬서 바로 입력을 시작할 수 있게 한다.
                examples = existing?.examples?.map { it.toDraft() } ?: listOf(newExample())
            )
        }
    }

    fun setUseCustom(value: Boolean) = _uiState.update { it.copy(useCustom = value) }
    fun setName(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }
    fun setInstruction(value: String) = _uiState.update { it.copy(instruction = value) }

    fun addExample() = _uiState.update { it.copy(examples = it.examples + newExample()) }
    fun removeExample(index: Int) = updateExamples { it.toMutableList().apply { removeAt(index) } }
    fun setSource(index: Int, value: String) = updateExample(index) { it.copy(source = value) }

    fun addCard(exampleIndex: Int) = updateExample(exampleIndex) { it.copy(cards = it.cards + newCard()) }
    fun removeCard(exampleIndex: Int, cardIndex: Int) =
        updateExample(exampleIndex) { it.copy(cards = it.cards.toMutableList().apply { removeAt(cardIndex) }) }
    fun setFront(exampleIndex: Int, cardIndex: Int, value: String) = updateCard(exampleIndex, cardIndex) { it.copy(front = value) }
    fun setBack(exampleIndex: Int, cardIndex: Int, value: String) = updateCard(exampleIndex, cardIndex) { it.copy(back = value) }

    // 기본 유형 전용: 앱에 내장된 기본 예시 5개를 초안 뒤에 복사해 붙인다.
    // 처음부터 다 쓰기보다 기본 예시를 고쳐 쓰는 편이 훨씬 빠르기 때문.
    fun importDefaultExamples() {
        val type = _uiState.value.builtinType ?: return
        _uiState.update { state -> state.copy(examples = state.examples + FewShotExamples.forType(type).map { it.toDraft() }) }
    }

    // "저장": 빈 칸을 정리한 뒤 저장소에 쓴다.
    //   - 앞/뒤 중 하나라도 비어 있는 카드는 버린다.
    //   - 자료가 비었거나 카드가 하나도 안 남은 예시는 버린다.
    // 사용자 지정 유형은 이름이 필수다 (덱 만들기 화면의 칩에 표시될 이름이므로).
    fun save() {
        val state = _uiState.value
        val examples = state.examples.mapNotNull { draft ->
            val cards = draft.cards
                .map { DraftCard(it.front.trim(), it.back.trim()) }
                .filter { it.front.isNotEmpty() && it.back.isNotEmpty() }
                .map { ExampleCard(it.front, it.back, style = null) }
            if (draft.source.isBlank() || cards.isEmpty()) null else FewShotExample(draft.source.trim(), cards)
        }

        val type = state.builtinType
        if (type != null) {
            store.update { settings ->
                settings.copy(builtin = settings.builtin + (type to BuiltinFewShotSetting(state.useCustom, examples)))
            }
        } else {
            val name = state.name.trim()
            if (name.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "유형 이름을 입력해주세요.") }
                return
            }
            val custom = CustomCardType(
                id = state.customId ?: CustomCardType.newId(),
                name = name,
                instruction = state.instruction.trim(),
                examples = examples
            )
            store.update { settings ->
                // 기존 유형이면 같은 자리에서 교체(칩 순서 유지), 새 유형이면 맨 뒤에 추가.
                val exists = settings.customTypes.any { it.id == custom.id }
                settings.copy(
                    customTypes = if (exists) settings.customTypes.map { if (it.id == custom.id) custom else it }
                    else settings.customTypes + custom
                )
            }
        }
        _uiState.update { it.copy(saved = true) }
    }

    // ── 내부 헬퍼 ──
    private fun newCard() = DraftCard()
    private fun newExample() = DraftExample(cards = listOf(newCard()))

    private fun updateExamples(transform: (List<DraftExample>) -> List<DraftExample>) =
        _uiState.update { it.copy(examples = transform(it.examples)) }

    private fun updateExample(index: Int, transform: (DraftExample) -> DraftExample) =
        updateExamples { list -> list.mapIndexed { i, example -> if (i == index) transform(example) else example } }

    private fun updateCard(exampleIndex: Int, cardIndex: Int, transform: (DraftCard) -> DraftCard) =
        updateExample(exampleIndex) { example ->
            example.copy(cards = example.cards.mapIndexed { i, card -> if (i == cardIndex) transform(card) else card })
        }

    private fun FewShotExample.toDraft() = DraftExample(source, cards.map { DraftCard(it.front, it.back) })
}
