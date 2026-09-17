package com.example.anki_advanced.gemini

// Gemini API로 주제(텍스트/이미지)만 입력하면 카드 여러 장을 자동으로 만들어주는 화면.
// 흐름: API 키 등록 → 제목/내용/이미지/장수 입력 → "생성하기" → 미리보기(체크박스로 선택) → "덱으로 저장"
//
// 이 파일의 구조는 DeckManageScreen.kt와 같은 패턴을 따른다:
//   DeckGenerationScreen (진입점, ViewModel과 연결) → 하위 컴포저블들(순수 UI, uiState와
//   콜백만 받음)로 화면을 잘게 나눠서, 각 조각이 "지금 뭘 그리는지"를 한눈에 알아보기 쉽게 한다.

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

// ── 색상 팔레트: 이 화면 전용 (HomeScreen/DeckManageScreen과 같은 톤이지만 별도 이름으로 정의) ──
private val GenBg = Color(0xFFF6F6FA)
private val GenSurface = Color(0xFFFFFFFF)
private val GenSurfaceLow = Color(0xFFF0F0F5)
private val GenPrimary = Color(0xFF4330F9)
private val GenOnSurface = Color(0xFF2D2F32)
private val GenOnSurfaceMuted = Color(0xFF5A5B5F)
private val GenError = Color(0xFFB41340)

// ── ViewModel과 연결되는 진입점 ──────────────────────────────────────────────
// NavHost가 "generateDeck" 경로로 이동할 때 호출하는 함수(HomeActivity.kt 참고).
// 카메라/갤러리처럼 "다른 화면(시스템 UI)을 열고 결과를 돌려받는" 처리는 ViewModel이 아니라
// 여기(Composable)에서 담당해야 한다 — ActivityResultLauncher는 Activity/Compose 생명주기에
// 묶여있는 안드로이드 UI 계층의 개념이라, 순수 로직만 다루는 ViewModel에 두면 안 되기 때문.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckGenerationScreen(
    navController: NavController? = null,
    viewModel: DeckGenerationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 카메라 앱에게 "여기에 사진을 저장해줘"라고 넘겨준 목적지 Uri를 기억해둔다.
    // 카메라 촬영 버튼을 누른 시점에 만들어서 여기 저장해두고, 촬영이 끝나면(takePictureLauncher의
    // 콜백에서) 이 Uri로 사진이 실제로 저장됐다고 가정하고 읽어들인다.
    // [문법] var ... by remember { mutableStateOf<Uri?>(null) }
    //   Compose가 화면을 다시 그릴(recomposition) 때도 이 값이 초기화되지 않고 유지되도록
    //   remember로 감싼 상태. Uri? 타입이라 아직 촬영 전(null)일 수도 있음을 표현한다.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // ── 시스템 UI를 여는 3가지 "런처(launcher)" ──
    // [문법] rememberLauncherForActivityResult(계약, 콜백)
    //   안드로이드의 "다른 화면을 열고 결과를 콜백으로 받는" 패턴(예전의 startActivityForResult)을
    //   Compose에서 쓰는 표준 방법. 계약(ActivityResultContracts.*)마다 "무엇을 열지"와
    //   "결과가 어떤 타입으로 오는지"가 정해져 있고, remember로 감싸져 있어 화면이 다시
    //   그려져도 같은 런처 인스턴스가 재사용된다(매번 새로 등록하면 안 되므로 중요).

    // 1) TakePicture: 카메라 앱을 열어서 pendingCameraUri 위치에 사진을 저장하게 한다.
    //    콜백으로 오는 success는 "사용자가 실제로 촬영을 완료했는지"(취소하면 false).
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCameraUri?.let { viewModel.onImagePicked(it) }
    }

    // 2) RequestPermission: 카메라 권한이 없을 때 시스템 권한 다이얼로그를 띄운다.
    //    사용자가 "허용"을 누르면 granted=true로 콜백이 오고, 그 즉시 목적지 Uri를 만들어
    //    바로 카메라를 이어서 실행한다 — 사용자가 버튼을 한 번만 눌러도 되도록 흐름을 이어붙인 것.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    // 3) PickVisualMedia: 안드로이드 시스템 제공 "사진 선택기(Photo Picker)"를 연다.
    //    이 방식은(구형 갤러리 인텐트와 달리) 별도의 저장소 읽기 권한이 필요 없다는 게 장점이라,
    //    AndroidManifest에 READ_MEDIA_IMAGES 같은 권한을 추가하지 않아도 된다.
    //    사용자가 사진을 하나 고르면 그 Uri가, 취소하면 null이 콜백으로 온다.
    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onImagePicked(it) } }

    // 저장이 끝나면(uiState.saveCompleted가 true가 되면) 자동으로 이전 화면(홈)으로 돌아간다.
    // [문법] LaunchedEffect(uiState.saveCompleted) { ... }
    //   key(uiState.saveCompleted)가 바뀔 때마다 블록을 다시 실행하는 사이드 이펙트 훅.
    //   "값이 바뀌는 걸 감지해서 1회성 동작(화면 이동)을 실행"하는 전형적인 용도 —
    //   popBackStack()을 그냥 아무 데서나 부르면 리컴포지션마다 반복 호출될 위험이 있는데,
    //   LaunchedEffect는 key 값이 실제로 바뀔 때만 재실행되므로 안전하다.
    LaunchedEffect(uiState.saveCompleted) {
        if (uiState.saveCompleted) {
            navController?.popBackStack()
        }
    }

    // [문법] Scaffold(topBar = {...}) { innerPadding -> ... }
    //   상단바 + 본문을 정해진 자리에 배치해주는 Material Design 기본 뼈대 (HomeScreen과 동일 패턴).
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI로 덱 만들기", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GenSurface)
            )
        },
        containerColor = GenBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())  // 내용(주제 텍스트, 카드 목록 등)이 길어지면 세로 스크롤
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API 키가 없으면 등록 카드만, 있으면 생성 폼을 보여준다 — 두 화면을 조건부로 스위칭.
            if (!uiState.hasApiKey) {
                ApiKeySetupCard(
                    apiKeyInput = uiState.apiKeyInput,
                    onApiKeyInputChange = viewModel::onApiKeyInputChange,
                    onSave = viewModel::saveApiKey
                )
            } else {
                GenerationForm(
                    uiState = uiState,
                    onDeckNameChange = viewModel::onDeckNameChange,
                    onContentChange = viewModel::onContentChange,
                    onCardCountChange = viewModel::onCardCountChange,
                    onLanguageChange = viewModel::onLanguageChange,
                    onGenerateClick = viewModel::onGenerateClick,
                    onChangeApiKey = viewModel::clearApiKey,
                    onImageRemove = viewModel::onImageRemoved,
                    // "사진 촬영" 버튼을 눌렀을 때의 분기: 권한이 이미 있으면 바로 카메라를 열고,
                    // 없으면 먼저 권한 요청부터 한다(허용되면 위 cameraPermissionLauncher 콜백이
                    // 이어서 카메라를 연다).
                    // [문법] ContextCompat.checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED
                    //   런타임 권한(Android 6.0+)이 이미 승인돼 있는지 확인하는 표준 방법.
                    onCameraClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            val uri = createCameraCaptureUri(context)
                            pendingCameraUri = uri
                            takePictureLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    // "갤러리에서 선택" 버튼: 이미지 전용 필터로 시스템 사진 선택기를 연다.
                    onGalleryClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }

            // 에러 메시지가 있으면(null이 아니면) 그 아래에 빨간 텍스트로 표시.
            // [문법] uiState.errorMessage?.let { message -> ... }
            //   errorMessage가 null이 아닐 때만 블록을 실행하는 관용구 — if(errorMessage != null)과
            //   동일한 효과지만, 그 안에서 non-null이 확정된 message를 바로 쓸 수 있어 더 간결하다.
            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = GenError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 생성된 카드가 하나라도 있을 때만 미리보기 섹션을 보여준다.
            if (uiState.generatedCards.isNotEmpty()) {
                PreviewSection(
                    uiState = uiState,
                    onDeckNameChange = viewModel::onDeckNameChange,
                    onCardToggle = viewModel::onCardToggle,
                    onCardRemove = viewModel::onCardRemove,
                    onSaveDeck = viewModel::onSaveDeck
                )
            }
        }
    }
}

// ── API 키가 아직 없을 때 보여주는 등록 카드 ────────────────────────────────
@Composable
private fun ApiKeySetupCard(
    apiKeyInput: String,
    onApiKeyInputChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GenSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Gemini API 키 등록", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GenOnSurface)
        Text(
            "Google AI Studio에서 발급받은 API 키를 입력하면 이 기기에 암호화되어 저장됩니다.",
            fontSize = 13.sp,
            color = GenOnSurfaceMuted
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = onApiKeyInputChange,
            label = { Text("API 키") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSave,
            // 빈 문자열로는 저장 못 하게 버튼 자체를 비활성화 — ViewModel의 saveApiKey()도
            // 한 번 더 blank 체크를 하지만, 버튼 단계에서 막아두면 사용자 경험이 더 매끄럽다.
            enabled = apiKeyInput.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("저장")
        }
    }
}

// ── API 키 등록 후: 제목/내용/이미지/장수/언어 입력 폼 ──────────────────────
@Composable
private fun GenerationForm(
    uiState: DeckGenerationUiState,
    onDeckNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onCardCountChange: (Int) -> Unit,
    onLanguageChange: (String) -> Unit,
    onGenerateClick: () -> Unit,
    onChangeApiKey: () -> Unit,
    onImageRemove: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GenSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("무엇으로 만들까요?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GenOnSurface)
            TextButton(onClick = onChangeApiKey) {
                Text("API 키 변경", fontSize = 12.sp)
            }
        }

        // 덱 제목: 선택 입력. 라벨 문구로 "비워두면 자동 생성"이라는 걸 미리 알려준다.
        OutlinedTextField(
            value = uiState.deckName,
            onValueChange = onDeckNameChange,
            label = { Text("덱 제목 (선택 — 비워두면 내용으로 자동 생성)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 내용: 필수 입력. singleLine이 아니라서 길게 붙여넣은 텍스트(문단 전체 등)도
        // 여러 줄로 자연스럽게 펼쳐진다 — 위 Column에 verticalScroll이 걸려 있어서
        // 아무리 길어져도 화면 아래쪽 버튼들이 가려지지 않는다.
        OutlinedTextField(
            value = uiState.content,
            onValueChange = onContentChange,
            label = { Text("내용 (필수 — 카드로 만들 주제나 텍스트)") },
            modifier = Modifier.fillMaxWidth()
        )

        // ── 이미지 첨부: 카메라 촬영 / 갤러리 선택 / 첨부된 이미지 미리보기 ──
        // imagePreview가 있으면(이미 이미지를 골랐으면) 미리보기+제거 버튼을,
        // 없으면 "촬영/선택" 두 버튼을 보여주는 조건부 UI.
        if (uiState.imagePreview != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // [문법] uiState.imagePreview.asImageBitmap()
                //   android.graphics.Bitmap을 Compose의 Image()가 받을 수 있는
                //   androidx.compose.ui.graphics.ImageBitmap 타입으로 변환하는 확장 함수.
                Image(
                    bitmap = uiState.imagePreview.asImageBitmap(),
                    contentDescription = "첨부된 이미지",
                    contentScale = ContentScale.Crop,  // 비율 유지하며 지정 영역을 꽉 채우고 넘치는 부분은 잘라냄
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                // 미리보기 우상단에 반투명 검은 배경 원형 X 버튼을 겹쳐 그린다.
                // [문법] Modifier.align(Alignment.TopEnd)
                //   부모가 Box일 때만 쓸 수 있는 정렬 지정 — Box 안의 자식들은 서로 겹쳐 그려지므로,
                //   Image는 전체를 채우고 IconButton은 그 위 오른쪽 위 모서리에 겹쳐 놓이게 된다.
                IconButton(
                    onClick = onImageRemove,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "이미지 제거", tint = Color.White)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // weight(1f) 두 개를 나란히 둬서 폭을 정확히 절반씩 나눠 가지게 한다.
                OutlinedButton(onClick = onCameraClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("사진 촬영")
                }
                OutlinedButton(onClick = onGalleryClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("갤러리에서 선택")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 장수 입력창은 화면에는 문자열(TextField는 항상 String)로 보이지만, 실제 상태는
            // Int(cardCount)다. toIntOrNull()로 숫자가 아닌 입력(예: 지우는 중 빈 문자열)은
            // 조용히 무시하고, 파싱에 성공했을 때만 ViewModel에 새 값을 알린다.
            OutlinedTextField(
                value = uiState.cardCount.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let(onCardCountChange) },
                label = { Text("장수 (1~30)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = uiState.language,
                onValueChange = onLanguageChange,
                label = { Text("언어") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onGenerateClick,
            // uiState.canGenerate: 로딩 중이 아니고 (내용 또는 이미지)가 있어야 true.
            // 제목만 입력하고 내용/이미지가 없으면 여기서 자동으로 비활성화된다.
            enabled = uiState.canGenerate,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 로딩 중이면 스피너+문구, 아니면 아이콘+"생성하기" — 버튼 안에서 상태에 따라
            // 내용물을 완전히 바꿔치기하는 흔한 패턴 (Button의 content 람다는 RowScope라서
            // 아이콘/스피너 옆에 Spacer로 간격을 준 Text를 나란히 놓을 수 있다).
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("생성 중...")
            } else {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("생성하기")
            }
        }
    }
}

// ── 생성 결과 미리보기: 감지된 유형 + 덱 이름 입력 + 카드 체크리스트 + 저장 버튼 ──
@Composable
private fun PreviewSection(
    uiState: DeckGenerationUiState,
    onDeckNameChange: (String) -> Unit,
    onCardToggle: (Int) -> Unit,
    onCardRemove: (Int) -> Unit,
    onSaveDeck: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GenSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "생성된 카드 (${uiState.selectedCount}/${uiState.generatedCards.size} 선택됨)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = GenOnSurface,
                modifier = Modifier.weight(1f)  // 남는 공간을 다 차지해서 오른쪽 뱃지를 끝으로 밀어냄
            )
            // Gemini가 1단계에서 판단한 콘텐츠 유형을 작은 알약(pill) 모양 뱃지로 보여준다.
            // detectedCardType이 null이면(아직 생성 전이면) 아예 그려지지 않는다.
            uiState.detectedCardType?.let { type ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GenPrimary.copy(alpha = 0.1f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "감지된 유형: ${type.label}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GenPrimary
                    )
                }
            }
        }

        // GenerationForm에서 이미 제목을 입력했을 수도, 자동 생성됐을 수도 있는 deckName을
        // 여기서 다시 한번 보여준다 — 미리보기를 보고 나서 "역시 이름을 이렇게 바꿔야겠다"
        // 싶을 때 저장 직전에 마지막으로 조정할 수 있게 하기 위함.
        OutlinedTextField(
            value = uiState.deckName,
            onValueChange = onDeckNameChange,
            label = { Text("덱 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // [문법] LazyColumn(modifier = Modifier.height(320.dp)) { items(size) { index -> ... } }
        //   일반 Column과 달리, 화면에 실제로 보이는 항목만 컴포지션(그리기)해서 카드가
        //   아무리 많아져도 성능이 유지되는 스크롤 가능한 리스트. 부모가 이미
        //   verticalScroll(rememberScrollState())로 세로 스크롤 중인데 여기 또 LazyColumn을
        //   넣으면 "스크롤 안의 스크롤"이 되므로, 높이를 320.dp로 고정해서 리스트 자체는
        //   그 안에서만 스크롤되고 바깥 스크롤과 방향이 겹쳐 꼬이지 않게 했다.
        LazyColumn(
            modifier = Modifier.height(320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.generatedCards.size) { index ->
                val card = uiState.generatedCards[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GenSurfaceLow)
                        .clickable { onCardToggle(index) }  // 행 전체를 탭해도 체크박스와 동일하게 토글
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = card.selected, onCheckedChange = { onCardToggle(index) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.front, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = GenOnSurface)
                        Text(card.back, fontSize = 13.sp, color = GenOnSurfaceMuted)
                    }
                    // 체크 해제(임시로 저장 대상에서 뺌)와 달리, 이 X 버튼은 목록에서 완전히
                    // 제거한다 — 마음에 안 드는 카드를 아예 없애버리고 싶을 때 쓰는 버튼.
                    IconButton(onClick = { onCardRemove(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "제외", tint = GenOnSurfaceMuted)
                    }
                }
            }
        }

        Button(
            onClick = onSaveDeck,
            // 저장 중이 아니고, 선택된 카드가 1장 이상 있고, 덱 이름이 비어있지 않아야 활성화.
            enabled = !uiState.isSaving && uiState.selectedCount > 0 && uiState.deckName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isSaving) "저장 중..." else "덱으로 저장 (${uiState.selectedCount}장)")
        }
    }
}
