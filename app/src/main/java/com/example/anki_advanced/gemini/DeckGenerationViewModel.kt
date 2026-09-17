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

// 미리보기 목록에서 카드 한 장을 표현하는 UI 전용 모델.
// GeminiDeckGenerator.GeneratedCard와 내용은 거의 같지만, 화면에서만 필요한 selected
// 플래그(체크박스 상태)를 하나 더 들고 있다는 점이 다르다 — "생성 결과 원본"과
// "화면에서 사용자가 만지작거리는 상태"를 서로 다른 타입으로 나눠두면, 나중에
// 화면 쪽 요구사항이 바뀌어도(예: 편집 가능하게 만들기) 생성 로직 쪽 코드는 안 건드려도 된다.
data class GeneratedCardUi(
    val front: String,
    val back: String,
    val cardType: String,
    val selected: Boolean = true  // 기본은 true: 생성되면 일단 전부 선택된 상태로 보여준다
)

// AI 덱 생성 화면 전체 상태를 한 덩어리로 모아둔 데이터 클래스.
// (이 앱의 다른 ViewModel들과 동일한 "UiState 패턴" — DeckManageUiState, HomeUiState 참고)
//
// 제목(deckName)과 내용(content)은 서로 다른 역할이라는 점이 이 상태에서 가장 중요한 부분이다:
//   - content: 카드를 실제로 뽑아낼 원본 자료. 이게(혹은 이미지가) 없으면 Gemini에게
//     "무엇으로 카드를 만들라는 건지" 알려줄 방법이 없으므로 생성 자체가 불가능하다 (필수).
//   - deckName: 그냥 이름표. 비워둬도 생성엔 지장이 없고, 생성 후 content에서 자동으로
//     제목을 뽑아 채워준다(아래 deriveDeckTitle 참고). 그래서 "제목만 있고 내용이 없는"
//     상태는 막지만, "내용만 있고 제목이 없는" 상태는 허용해도 된다.
data class DeckGenerationUiState(
    val hasApiKey: Boolean = false,        // API 키가 등록돼 있는지 (없으면 등록 화면부터 보여줌)
    val apiKeyInput: String = "",          // API 키 등록 입력창에 지금 타이핑 중인 값
    val deckName: String = "",             // 덱 제목 (선택 입력 — 비워두면 자동 생성)
    val content: String = "",              // 카드로 만들 내용/텍스트 (필수)
    val cardCount: Int = 10,               // 몇 장 만들지
    val language: String = "한국어",         // 카드를 어떤 언어로 작성할지
    val imagePreview: Bitmap? = null,      // 첨부된 이미지 미리보기 (없으면 null)
    val isLoading: Boolean = false,        // Gemini 호출 진행 중인지 (버튼 로딩 스피너용)
    val errorMessage: String? = null,      // 화면에 보여줄 에러 문구 (없으면 null)
    val detectedCardType: CardType? = null, // 생성 직후 Gemini가 분류한 콘텐츠 유형 (뱃지 표시용)
    val generatedCards: List<GeneratedCardUi> = emptyList(), // 미리보기 목록
    val isSaving: Boolean = false,         // DB 저장 진행 중인지
    val saveCompleted: Boolean = false     // 저장이 끝났는지 (true가 되면 화면이 자동으로 닫힘)
) {
    // [문법] val selectedCount: Int get() = ...
    //   생성자 파라미터가 아니라 "다른 프로퍼티들로부터 매번 새로 계산되는" 읽기 전용 값.
    //   get()으로 정의하면 저장 공간을 따로 안 차지하고, 이 값을 읽을 때마다 그 순간의
    //   generatedCards 기준으로 다시 계산된다 (카드 체크박스를 켜고 끌 때마다 자동으로 최신값).
    val selectedCount: Int get() = generatedCards.count { it.selected }

    // "생성하기" 버튼을 눌러도 되는 상태인지. 로딩 중이 아니면서, content나 이미지 중
    // 최소 하나는 있어야 한다 — 제목(deckName)은 이 조건에 전혀 관여하지 않는다.
    // 그 결과: "제목만 입력하고 내용/이미지가 없는" 상태는 자동으로 버튼이 비활성화된다.
    val canGenerate: Boolean get() = !isLoading && (content.isNotBlank() || imagePreview != null)
}

class DeckGenerationViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체가 공유하는 Room DB 싱글턴. 생성된 카드를 실제로 저장할 때 이걸 통해 insert한다.
    private val db = AppDatabase.getInstance(application)
    // API 키를 암호화 저장소에서 읽고 쓰는 창구.
    private val apiKeyStore = GeminiApiKeyStore(application)
    // 실제 Gemini 네트워크 호출을 담당하는 클래스 (2단계 프롬프트 로직이 여기 안에 있음).
    private val generator = GeminiDeckGenerator()

    // 첨부한 이미지의 "전송용 압축 바이트"는 uiState(StateFlow)에 직접 넣지 않고 따로 보관한다.
    // 이유: StateFlow는 값이 바뀔 때마다 구독자(Compose 화면)에게 새 값을 통째로 흘려보내는데,
    // 이미지 바이트 배열까지 그 안에 있으면 화면이 리컴포지션될 때마다 큰 데이터가 계속
    // 비교/복사 대상이 돼서 낭비다. 화면에는 미리보기용 Bitmap(imagePreview)만 노출하고,
    // Gemini에 실제로 보낼 바이트는 이 private 변수에만 잠깐 담아뒀다가 onGenerateClick에서 꺼내 쓴다.
    private var pendingImageBytes: ByteArray? = null

    // [문법] MutableStateFlow / StateFlow 짝 패턴
    //   _uiState(밑줄 O): 이 클래스 안에서만 값을 바꿀 수 있는 쓰기용 통.
    //   uiState(밑줄 X): 화면(DeckGenerationScreen)에는 읽기 전용으로만 노출하는 통로.
    //   생성 시점에 apiKeyStore.getApiKey()로 이미 저장된 키가 있는지 확인해서 hasApiKey
    //   초기값을 채워둔다 — 화면을 처음 열었을 때부터 "키가 이미 있으면 등록 화면 건너뛰기"가 되게.
    private val _uiState = MutableStateFlow(
        DeckGenerationUiState(hasApiKey = apiKeyStore.getApiKey() != null)
    )
    val uiState: StateFlow<DeckGenerationUiState> = _uiState.asStateFlow()

    fun onApiKeyInputChange(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    // API 키 등록 화면의 "저장" 버튼. 저장에 성공하면 hasApiKey를 true로 바꿔서
    // 화면이 자동으로 "생성 폼" 쪽으로 전환되게 한다.
    fun saveApiKey() {
        val key = _uiState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        apiKeyStore.saveApiKey(key)
        _uiState.update { it.copy(hasApiKey = true, apiKeyInput = "") }
    }

    // "API 키 변경" 버튼. 저장된 키를 지우고 hasApiKey를 false로 되돌려서
    // 다시 키 등록 화면이 보이게 한다.
    fun clearApiKey() {
        apiKeyStore.clearApiKey()
        _uiState.update { it.copy(hasApiKey = false) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value) }
    }

    // 장수 입력창은 텍스트로 받지만 실제 값은 1~30 사이로 강제한다.
    // [문법] value.coerceIn(1, 30) — 범위를 벗어나면 가까운 경계값으로 잘라주는 함수.
    fun onCardCountChange(value: Int) {
        _uiState.update { it.copy(cardCount = value.coerceIn(1, 30)) }
    }

    fun onLanguageChange(value: String) {
        _uiState.update { it.copy(language = value) }
    }

    fun onDeckNameChange(value: String) {
        _uiState.update { it.copy(deckName = value) }
    }

    // 카메라 촬영/갤러리 선택 후 화면(DeckGenerationScreen)이 넘겨주는 Uri를 실제
    // 픽셀 데이터로 읽어서 (미리보기용 Bitmap + 전송용 압축 바이트)로 바꾸는 함수.
    //
    // [문법] viewModelScope.launch { val decoded = withContext(Dispatchers.IO) { ... } }
    //   Uri에서 이미지를 디코딩하는 작업(decodeAndCompressImage)은 파일 I/O + 비트맵 처리라
    //   시간이 걸릴 수 있는 무거운 작업이다. withContext(Dispatchers.IO)로 감싸서 백그라운드
    //   스레드에서 실행하고, 끝나면 자동으로 원래 컨텍스트로 돌아와 결과(decoded)를 받는다.
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeAndCompressImage(getApplication(), uri)
            }
            if (decoded == null) {
                // 파일이 깨졌거나 접근 권한이 없는 등, 디코딩 자체가 실패한 경우.
                _uiState.update { it.copy(errorMessage = "이미지를 불러오지 못했습니다.") }
                return@launch
            }
            // [문법] val (bitmap, bytes) = decoded
            //   decodeAndCompressImage가 Pair<Bitmap, ByteArray>를 반환하는데, 구조 분해
            //   선언으로 "첫 번째 값은 bitmap, 두 번째 값은 bytes"로 한 번에 풀어서 받는다.
            val (bitmap, bytes) = decoded
            pendingImageBytes = bytes                 // 전송용 바이트는 private 변수에만 보관
            _uiState.update { it.copy(imagePreview = bitmap, errorMessage = null) } // 미리보기는 상태로 노출
        }
    }

    // "이미지 제거(X)" 버튼. 미리보기와 전송용 바이트를 둘 다 지워서 완전히 없던 일로 만든다.
    fun onImageRemoved() {
        pendingImageBytes = null
        _uiState.update { it.copy(imagePreview = null) }
    }

    // "생성하기" 버튼: Gemini를 2단계(유형 분류 → 유형별 생성)로 호출해서 카드 후보 목록을 만든다.
    // 이 시점엔 아직 DB에 아무것도 쓰지 않는다 — 실제 저장은 사용자가 미리보기를 보고
    // "덱으로 저장"을 눌러야만 일어난다(onSaveDeck).
    fun onGenerateClick() {
        val state = _uiState.value

        // 방어 1: API 키가 아예 없는 경우 — 이 화면에 오면 보통 hasApiKey=false라 등록
        // 폼이 먼저 보이지만, 혹시 모를 상태 불일치에 대비해 한 번 더 확인한다.
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null) {
            _uiState.update { it.copy(errorMessage = "먼저 Gemini API 키를 등록해주세요.") }
            return
        }
        // 방어 2: 내용도 이미지도 없는 경우 — 이 조건은 uiState.canGenerate와 정확히
        // 같은 기준이라, 원래는 버튼이 비활성화돼 있어서 여기까지 오지 않는 게 정상이지만
        // (예: 프로그램적으로 이 함수를 다른 경로에서 호출할 가능성 등) 이중 안전장치로 남겨둔다.
        if (state.content.isBlank() && state.imagePreview == null) {
            _uiState.update { it.copy(errorMessage = "카드로 만들 내용을 입력하거나 이미지를 첨부해주세요.") }
            return
        }

        // ImageInput은 GeminiDeckGenerator가 요구하는 형태라서, 여기서 pendingImageBytes를
        // 감싸 변환해준다. 이미지가 없으면(null) 그대로 null을 넘겨서 "텍스트만으로 생성"이 된다.
        val image = pendingImageBytes?.let { ImageInput(bytes = it) }

        // 로딩 상태로 전환 + 이전 생성 결과/에러/유형 뱃지를 전부 초기화.
        // (재생성 버튼을 눌렀을 때 이전 결과가 잠깐이라도 남아 보이면 헷갈리므로 바로 비운다)
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, generatedCards = emptyList(), detectedCardType = null)
        }

        viewModelScope.launch {
            try {
                val result = generator.generateCards(
                    apiKey = apiKey,
                    content = state.content.trim(),
                    image = image,
                    count = state.cardCount,
                    language = state.language
                )
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        detectedCardType = result.cardType,
                        generatedCards = result.cards.map { GeneratedCardUi(it.front, it.back, it.cardType) },
                        // [문법] current.deckName.ifBlank { ... }
                        //   deckName이 비어있지 않으면(사용자가 이미 제목을 입력해뒀으면) 그 값을
                        //   그대로 유지하고, 비어있을 때만 뒤 블록(자동 제목 생성)을 실행해서 채운다.
                        //   즉 "사용자가 이미 적어둔 제목은 절대 덮어쓰지 않는다"는 보장이 이 한 줄에 있다.
                        deckName = current.deckName.ifBlank { deriveDeckTitle(state.content, result.cardType.label) }
                    )
                }
            } catch (e: GeminiApiException) {
                // 우리가 의도적으로 사용자용 메시지를 담아 던진 예외 — e.message를 그대로 보여줘도 안전.
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                // 그 외 예상 못 한 예외(예: JSON 파싱 도중 다른 이유로 터진 런타임 예외 등)까지
                // 잡아서, 앱이 죽는 대신 화면에 에러 문구만 뜨고 다시 시도할 수 있게 한다.
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "카드 생성 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    // 미리보기 목록에서 카드 하나의 체크박스를 토글(켜져 있으면 끄고, 꺼져 있으면 켠다).
    // [문법] updated[index] = updated[index].let { it.copy(selected = !it.selected) }
    //   data class는 불변(immutable)이라 기존 객체의 selected 값을 직접 못 바꾸고,
    //   copy()로 "그 필드만 반전시킨 새 객체"를 만들어 같은 자리에 갈아 끼워야 한다.
    fun onCardToggle(index: Int) {
        _uiState.update { state ->
            val updated = state.generatedCards.toMutableList()
            updated[index] = updated[index].let { it.copy(selected = !it.selected) }
            state.copy(generatedCards = updated)
        }
    }

    // 미리보기 목록에서 카드 하나를 완전히 목록에서 빼버린다(체크 해제와 달리 되돌릴 수 없음).
    fun onCardRemove(index: Int) {
        _uiState.update { state ->
            val updated = state.generatedCards.toMutableList()
            updated.removeAt(index)
            state.copy(generatedCards = updated)
        }
    }

    // "덱으로 저장" 버튼: 체크된(selected=true) 카드들만 골라서 새 덱 + 카드들을 실제 DB에 만든다.
    fun onSaveDeck() {
        val state = _uiState.value
        val selectedCards = state.generatedCards.filter { it.selected }
        if (state.deckName.isBlank() || selectedCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "덱 이름과 카드 1장 이상이 필요합니다.") }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            // DB 쓰기는 Room이 자동으로 백그라운드에서 처리해주긴 하지만(suspend fun),
            // 여러 insert를 순서대로 묶어서 "이 블록 전체가 IO 작업"이라는 걸 명시적으로
            // 표시해두는 게 읽는 사람 입장에서 더 명확하다.
            withContext(Dispatchers.IO) {
                // 덱을 먼저 만들고, insert가 반환하는 새 deckId를 카드들의 deckId로 그대로 사용.
                val deckId = db.deckDao().insert(DeckEntity(name = state.deckName.trim()))
                selectedCards.forEach { card ->
                    db.cardDao().insert(
                        CardEntity(
                            deckId = deckId,
                            front = card.front,
                            back = card.back,
                            tags = "",
                            status = CARD_NEW,       // AI로 만든 카드도 수동으로 만든 카드와 동일하게 NEW 상태로 시작
                            cardType = card.cardType  // Gemini가 분류한 콘텐츠 유형을 그대로 기록해둠
                        )
                    )
                }
            }
            // saveCompleted=true가 되면 화면(DeckGenerationScreen)의 LaunchedEffect가 감지해서
            // navController.popBackStack()으로 자동으로 이전 화면(홈)으로 돌아간다.
            _uiState.update { it.copy(isSaving = false, saveCompleted = true) }
        }
    }

    // 에러 배너를 닫았을 때 등, 에러 메시지를 화면에서 지우고 싶을 때 호출.
    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // 제목(deckName)을 안 입력했을 때 생성 완료 후 자동으로 채워줄 이름을 content에서 뽑아낸다.
    // 규칙: content의 "첫 번째 비어있지 않은 줄"을 가져와서, 30자가 넘으면 30자로 자르고
    // 말줄임표(…)를 붙인다. content가 통째로 비어있는 극단적인 경우(이미지만으로 생성한 경우)엔
    // fallback(감지된 카드 유형 이름, 예: "코드")을 대신 쓴다.
    //
    // [문법] content.trim().lineSequence().firstOrNull { it.isNotBlank() }
    //   lineSequence()는 문자열을 줄바꿈 기준으로 나눈 Sequence(지연 평가되는 리스트 비슷한 것).
    //   firstOrNull { 조건 }으로 "공백이 아닌 첫 줄"을 찾는다 — 혹시 사용자가 맨 앞에
    //   빈 줄을 몇 개 넣어놨어도 실제 내용이 있는 줄부터 제목 후보로 삼기 위함.
    private fun deriveDeckTitle(content: String, fallback: String): String {
        val firstLine = content.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isBlank()) return fallback
        return if (firstLine.length > 30) firstLine.take(30).trimEnd() + "…" else firstLine
    }
}
