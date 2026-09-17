package com.example.anki_advanced.gemini

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anki_advanced.AppDatabase
import com.example.anki_advanced.CARD_NEW
import com.example.anki_advanced.CardEntity
import com.example.anki_advanced.DeckEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 미리보기 목록에서 카드 한 장. selected로 "이 카드는 저장할지 뺄지"를 사용자가 고를 수 있게 한다.
data class GeneratedCardUi(
    val front: String,
    val back: String,
    val cardType: String,
    val selected: Boolean = true
)

// AI 덱 생성 화면 전체 상태.
data class DeckGenerationUiState(
    val hasApiKey: Boolean = false,
    val apiKeyInput: String = "",
    val topic: String = "",
    val cardCount: Int = 10,
    val language: String = "한국어",
    val imagePreview: Bitmap? = null,      // 첨부된 이미지 미리보기 (없으면 null)
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val detectedCardType: CardType? = null, // 생성 직후 Gemini가 분류한 콘텐츠 유형
    val generatedCards: List<GeneratedCardUi> = emptyList(),
    val deckName: String = "",
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false
) {
    val selectedCount: Int get() = generatedCards.count { it.selected }
    val canGenerate: Boolean get() = !isLoading && (topic.isNotBlank() || imagePreview != null)
}

class DeckGenerationViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val apiKeyStore = GeminiApiKeyStore(application)
    private val generator = GeminiDeckGenerator()

    // Bitmap은 미리보기 겸 상태에 두지만, Gemini 전송용 압축 바이트는 별도로 들고 있는다.
    // (StateFlow 값 자체에 큰 ByteArray를 반복 복사해 넣지 않기 위해 분리)
    private var pendingImageBytes: ByteArray? = null

    private val _uiState = MutableStateFlow(
        DeckGenerationUiState(hasApiKey = apiKeyStore.getApiKey() != null)
    )
    val uiState: StateFlow<DeckGenerationUiState> = _uiState.asStateFlow()

    fun onApiKeyInputChange(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun saveApiKey() {
        val key = _uiState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        apiKeyStore.saveApiKey(key)
        _uiState.update { it.copy(hasApiKey = true, apiKeyInput = "") }
    }

    fun clearApiKey() {
        apiKeyStore.clearApiKey()
        _uiState.update { it.copy(hasApiKey = false) }
    }

    fun onTopicChange(value: String) {
        _uiState.update { it.copy(topic = value) }
    }

    fun onCardCountChange(value: Int) {
        _uiState.update { it.copy(cardCount = value.coerceIn(1, 30)) }
    }

    fun onLanguageChange(value: String) {
        _uiState.update { it.copy(language = value) }
    }

    fun onDeckNameChange(value: String) {
        _uiState.update { it.copy(deckName = value) }
    }

    // 카메라 촬영/갤러리 선택으로 얻은 Uri를 읽어서 미리보기 + 전송용 바이트로 준비.
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeAndCompressImage(getApplication(), uri)
            }
            if (decoded == null) {
                _uiState.update { it.copy(errorMessage = "이미지를 불러오지 못했습니다.") }
                return@launch
            }
            val (bitmap, bytes) = decoded
            pendingImageBytes = bytes
            _uiState.update { it.copy(imagePreview = bitmap, errorMessage = null) }
        }
    }

    fun onImageRemoved() {
        pendingImageBytes = null
        _uiState.update { it.copy(imagePreview = null) }
    }

    // "생성하기" 버튼: Gemini를 2단계(유형 분류 → 유형별 생성)로 호출해서 카드 후보 목록을 만든다.
    // 아직 DB에는 아무것도 안 쓴다.
    fun onGenerateClick() {
        val state = _uiState.value
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null) {
            _uiState.update { it.copy(errorMessage = "먼저 Gemini API 키를 등록해주세요.") }
            return
        }
        if (state.topic.isBlank() && state.imagePreview == null) {
            _uiState.update { it.copy(errorMessage = "주제를 입력하거나 이미지를 첨부해주세요.") }
            return
        }

        val image = pendingImageBytes?.let { ImageInput(bytes = it) }

        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, generatedCards = emptyList(), detectedCardType = null)
        }

        viewModelScope.launch {
            try {
                val result = generator.generateCards(
                    apiKey = apiKey,
                    topic = state.topic.trim(),
                    image = image,
                    count = state.cardCount,
                    language = state.language
                )
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        detectedCardType = result.cardType,
                        generatedCards = result.cards.map { GeneratedCardUi(it.front, it.back, it.cardType) },
                        deckName = current.deckName.ifBlank { state.topic.trim().ifBlank { result.cardType.label } }
                    )
                }
            } catch (e: GeminiApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "카드 생성 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    fun onCardToggle(index: Int) {
        _uiState.update { state ->
            val updated = state.generatedCards.toMutableList()
            updated[index] = updated[index].let { it.copy(selected = !it.selected) }
            state.copy(generatedCards = updated)
        }
    }

    fun onCardRemove(index: Int) {
        _uiState.update { state ->
            val updated = state.generatedCards.toMutableList()
            updated.removeAt(index)
            state.copy(generatedCards = updated)
        }
    }

    // "덱으로 저장" 버튼: 선택된 카드들로 새 덱 + 카드들을 DB에 실제로 만든다.
    fun onSaveDeck() {
        val state = _uiState.value
        val selectedCards = state.generatedCards.filter { it.selected }
        if (state.deckName.isBlank() || selectedCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "덱 이름과 카드 1장 이상이 필요합니다.") }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val deckId = db.deckDao().insert(DeckEntity(name = state.deckName.trim()))
                selectedCards.forEach { card ->
                    db.cardDao().insert(
                        CardEntity(
                            deckId = deckId,
                            front = card.front,
                            back = card.back,
                            tags = "",
                            status = CARD_NEW,
                            cardType = card.cardType
                        )
                    )
                }
            }
            _uiState.update { it.copy(isSaving = false, saveCompleted = true) }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
