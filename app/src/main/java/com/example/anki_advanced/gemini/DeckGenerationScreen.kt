package com.example.anki_advanced.gemini

// Gemini API로 주제(텍스트/이미지)만 입력하면 카드 여러 장을 자동으로 만들어주는 화면.
// 흐름: API 키 등록 → 주제/이미지/장수 입력 → "생성하기" → 미리보기(체크박스로 선택) → "덱으로 저장"

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

private val GenBg = Color(0xFFF6F6FA)
private val GenSurface = Color(0xFFFFFFFF)
private val GenSurfaceLow = Color(0xFFF0F0F5)
private val GenPrimary = Color(0xFF4330F9)
private val GenOnSurface = Color(0xFF2D2F32)
private val GenOnSurfaceMuted = Color(0xFF5A5B5F)
private val GenError = Color(0xFFB41340)

// ── ViewModel과 연결되는 진입점 ──────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckGenerationScreen(
    navController: NavController? = null,
    viewModel: DeckGenerationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 카메라로 찍을 사진을 어디에 저장할지(임시 파일 Uri)는 촬영 버튼을 누른 시점에 정해서 기억해둔다.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCameraUri?.let { viewModel.onImagePicked(it) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onImagePicked(it) } }

    // 저장이 끝나면 자동으로 이전 화면(홈)으로 돌아간다.
    LaunchedEffect(uiState.saveCompleted) {
        if (uiState.saveCompleted) {
            navController?.popBackStack()
        }
    }

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
                .verticalScroll(rememberScrollState())  // 내용이 길면(주제 텍스트, 카드 목록 등) 세로 스크롤
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    onGalleryClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = GenError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

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

        OutlinedTextField(
            value = uiState.deckName,
            onValueChange = onDeckNameChange,
            label = { Text("덱 제목 (선택 — 비워두면 내용으로 자동 생성)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.content,
            onValueChange = onContentChange,
            label = { Text("내용 (필수 — 카드로 만들 주제나 텍스트)") },
            modifier = Modifier.fillMaxWidth()
        )

        // ── 이미지 첨부: 카메라 촬영 / 갤러리 선택 / 첨부된 이미지 미리보기 ──
        if (uiState.imagePreview != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = uiState.imagePreview.asImageBitmap(),
                    contentDescription = "첨부된 이미지",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
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
            enabled = uiState.canGenerate,
            modifier = Modifier.fillMaxWidth()
        ) {
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
                modifier = Modifier.weight(1f)
            )
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

        OutlinedTextField(
            value = uiState.deckName,
            onValueChange = onDeckNameChange,
            label = { Text("덱 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

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
                        .clickable { onCardToggle(index) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = card.selected, onCheckedChange = { onCardToggle(index) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.front, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = GenOnSurface)
                        Text(card.back, fontSize = 13.sp, color = GenOnSurfaceMuted)
                    }
                    IconButton(onClick = { onCardRemove(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "제외", tint = GenOnSurfaceMuted)
                    }
                }
            }
        }

        Button(
            onClick = onSaveDeck,
            enabled = !uiState.isSaving && uiState.selectedCount > 0 && uiState.deckName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isSaving) "저장 중..." else "덱으로 저장 (${uiState.selectedCount}장)")
        }
    }
}
