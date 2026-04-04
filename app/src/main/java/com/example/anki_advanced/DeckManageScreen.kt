package com.example.anki_advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

// ── 색상 ──
private val DmBg            = Color(0xFFF6F6FA)
private val DmSurfaceLow    = Color(0xFFF0F0F5)
private val DmSurfaceLowest = Color(0xFFFFFFFF)
private val DmPrimary       = Color(0xFF4330F9)
private val DmOnPrimary     = Color(0xFFF1EDFF)
private val DmOnSurface     = Color(0xFF2D2F32)
private val DmOnSurfaceVar  = Color(0xFF5A5B5F)
private val DmOutlineVar    = Color(0xFFACADB1)
private val DmError         = Color(0xFFB41340)

// ─────────────────────────────────────────────
// ViewModel 진입점 — NavController, ViewModel 여기서만
// [변경] DeckManageActivity → DeckManageScreen + DeckManageContent 분리
// 기존: Activity에서 adapter 콜백 + AlertDialog.Builder로 다이얼로그 직접 생성
// 변경: ViewModel 진입점(DeckManageScreen)과 순수 UI(DeckManageContent)로 분리
// 이유: DeckManageContent는 ViewModel 없이 Preview 가능
// ─────────────────────────────────────────────
@Composable
fun DeckManageScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    deckName: String = "덱 이름",
    viewModel: DeckManageViewModel = viewModel()
) {
    val cards by viewModel.cards.collectAsState()

    var frontText by remember { mutableStateOf("") }
    var backText  by remember { mutableStateOf("") }
    var tagsText  by remember { mutableStateOf("") }

    // [변경] AlertDialog.Builder → Compose AlertDialog 조건부 렌더링
    // 기존: showDeleteDialog(), showEditDialog()에서 AlertDialog.Builder로 직접 생성
    // 변경: showDeleteDialog / showEditDialog 상태 플래그로 Compose AlertDialog 조건부 렌더링
    var showDeleteDialog by remember { mutableStateOf(false) }
    var cardToDelete     by remember { mutableStateOf<CardUi?>(null) }
    var showEditDialog   by remember { mutableStateOf(false) }
    var cardToEdit       by remember { mutableStateOf<CardUi?>(null) }
    var editFront        by remember { mutableStateOf("") }
    var editBack         by remember { mutableStateOf("") }
    var editTags         by remember { mutableStateOf("") }

    // [변경] Activity onCreate의 initialLoadFromDb() → LaunchedEffect(deckId)
    // 기존: Activity onCreate에서 initialLoadFromDb() 직접 호출
    // 변경: Compose 생명주기에 맞게 LaunchedEffect로 최초 1회 로딩
    LaunchedEffect(deckId) { viewModel.loadCards(deckId) }

    DeckManageContent(
        deckName         = deckName,
        cards            = cards,
        frontText        = frontText,
        backText         = backText,
        tagsText         = tagsText,
        showDeleteDialog = showDeleteDialog,
        cardToDelete     = cardToDelete,
        showEditDialog   = showEditDialog,
        cardToEdit       = cardToEdit,
        editFront        = editFront,
        editBack         = editBack,
        editTags         = editTags,
        onFrontChange    = { frontText = it },
        onBackChange     = { backText = it },
        onTagsChange     = { tagsText = it },
        onAdd = {
            if (frontText.isNotBlank() && backText.isNotBlank()) {
                viewModel.addCard(deckId, frontText.trim(), backText.trim(), tagsText.trim())
                frontText = ""; backText = ""; tagsText = ""
            }
        },
        onEditRequest = { card ->
            cardToEdit = card; editFront = card.front; editBack = card.back
            editTags = card.tags; showEditDialog = true
        },
        onDeleteRequest = { card ->
            cardToDelete = card; showDeleteDialog = true
        },
        onDeleteConfirm = {
            viewModel.deleteCard(cardToDelete!!)
            showDeleteDialog = false; cardToDelete = null
        },
        onDeleteDismiss  = { showDeleteDialog = false },
        onEditFrontChange = { editFront = it },
        onEditBackChange  = { editBack = it },
        onEditTagsChange  = { editTags = it },
        onEditConfirm = {
            if (editFront.isNotBlank() && editBack.isNotBlank()) {
                viewModel.updateCard(deckId, cardToEdit!!, editFront.trim(), editBack.trim(), editTags.trim())
                showEditDialog = false; cardToEdit = null
            }
        },
        onEditDismiss = { showEditDialog = false },
        onBack        = { navController?.popBackStack() }
    )
}

// ─────────────────────────────────────────────
// 순수 UI — ViewModel 없음, Preview 가능
// [변경] RecyclerView + CardAdapter → LazyColumn + items()
// 기존: RecyclerView.layoutManager + adapter.setOnCardMenuActionListener + adapter.notifyItem~
// 변경: LazyColumn + items(cards, key = { it.id })로 선언적 UI 구성
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckManageContent(
    deckName: String,
    cards: List<CardUi>,
    frontText: String,
    backText: String,
    tagsText: String,
    showDeleteDialog: Boolean,
    cardToDelete: CardUi?,
    showEditDialog: Boolean,
    cardToEdit: CardUi?,
    editFront: String,
    editBack: String,
    editTags: String,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEditRequest: (CardUi) -> Unit,
    onDeleteRequest: (CardUi) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteDismiss: () -> Unit,
    onEditFrontChange: (String) -> Unit,
    onEditBackChange: (String) -> Unit,
    onEditTagsChange: (String) -> Unit,
    onEditConfirm: () -> Unit,
    onEditDismiss: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deckName, color = DmOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기", tint = DmPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DmBg)
            )
        },
        containerColor = DmBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                AddCardForm(
                    frontText = frontText, backText = backText, tagsText = tagsText,
                    onFrontChange = onFrontChange, onBackChange = onBackChange,
                    onTagsChange = onTagsChange, onAdd = onAdd
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("카드 목록", color = DmOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${cards.size}장", color = DmOnSurfaceVar, fontSize = 13.sp)
                }
            }
            items(cards, key = { it.id }) { card ->
                CardManageItem(
                    card = card,
                    onEdit = { onEditRequest(card) },
                    onDelete = { onDeleteRequest(card) }
                )
            }
        }
    }

    // 삭제 다이얼로그
    if (showDeleteDialog && cardToDelete != null) {
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
            title = { Text("카드 삭제", color = DmOnSurface, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "이 카드를 삭제하시겠습니까?\n\nQ: ${cardToDelete.front}\nA: ${cardToDelete.back}",
                    color = DmOnSurfaceVar, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) {
                    Text("삭제", color = DmError, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismiss) { Text("취소", color = DmOnSurfaceVar) }
            },
            containerColor = DmSurfaceLowest,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 수정 다이얼로그
    if (showEditDialog && cardToEdit != null) {
        AlertDialog(
            onDismissRequest = onEditDismiss,
            title = { Text("카드 수정", color = DmOnSurface, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DmTextField(editFront, "앞면", onEditFrontChange)
                    DmTextField(editBack,  "뒷면", onEditBackChange)
                    DmTextField(editTags,  "태그 (선택)", onEditTagsChange)
                }
            },
            confirmButton = {
                TextButton(onClick = onEditConfirm) {
                    Text("저장", color = DmPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onEditDismiss) { Text("취소", color = DmOnSurfaceVar) }
            },
            containerColor = DmSurfaceLowest,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// ── 카드 추가 폼 ──
// [변경] binding.btnAdd.setOnClickListener → onAdd 람다 콜백
// 기존: Activity에서 btnAdd.setOnClickListener 직접 등록 후 insertCardAndUpdateUi() 호출
// 변경: 순수 UI 함수에 onAdd 콜백 주입, 실제 처리는 DeckManageScreen에서 ViewModel에 위임
@Composable
private fun AddCardForm(
    frontText: String, backText: String, tagsText: String,
    onFrontChange: (String) -> Unit, onBackChange: (String) -> Unit,
    onTagsChange: (String) -> Unit, onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp),
                ambientColor = Color(0xFF2D2F32).copy(alpha = 0.05f))
            .background(DmSurfaceLowest, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("새 카드 추가", color = DmPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("FRONT", color = DmOnSurfaceVar, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                DmTextField(frontText, "앞면", onFrontChange)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("BACK", color = DmOnSurfaceVar, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                DmTextField(backText, "뒷면", onBackChange)
            }
        }
        DmTextField(tagsText, "태그 (선택)", onTagsChange)
        Button(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.buttonColors(containerColor = DmPrimary, contentColor = DmOnPrimary),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
        ) { Text("추가", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ── 텍스트 필드 ──
@Composable
private fun DmTextField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DmPrimary, unfocusedBorderColor = DmOutlineVar,
            focusedLabelColor = DmPrimary, unfocusedLabelColor = DmOnSurfaceVar,
            focusedTextColor = DmOnSurface, unfocusedTextColor = DmOnSurface,
            cursorColor = DmPrimary,
            focusedContainerColor = DmSurfaceLowest, unfocusedContainerColor = DmSurfaceLowest
        ),
        shape = RoundedCornerShape(10.dp),
        singleLine = true
    )
}

// ── 카드 아이템 ──
// [변경] CardAdapter.OnCardMenuActionListener → onEdit / onDelete 람다
// 기존: CardAdapter에 OnCardMenuActionListener 인터페이스 등록,
//       onMenuAction에서 actionId로 edit/delete 분기
// 변경: 각 카드 아이템에 onEdit / onDelete 람다 직접 전달
@Composable
private fun CardManageItem(card: CardUi, onEdit: () -> Unit, onDelete: () -> Unit) {
    val statusLabel = when (card.status) {
        CARD_NEW      -> "New"
        CARD_LEARNING -> "Learning"
        CARD_REVIEW   -> "Review"
        else -> "—"
    }
    val statusColor = when (card.status) {
        CARD_NEW      -> DmPrimary
        CARD_LEARNING -> Color(0xFFF97316)
        CARD_REVIEW   -> Color(0xFF22C55E)
        else          -> DmOnSurfaceVar
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DmSurfaceLow, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("FRONT", color = DmOnSurfaceVar, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(card.front, color = DmOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("BACK", color = DmOnSurfaceVar, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(card.back, color = DmOnSurfaceVar, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "수정", tint = DmPrimary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "삭제", tint = DmError.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────
// Preview — ViewModel 없음
// ─────────────────────────────────────────────
@Preview(showBackground = true, backgroundColor = 0xFFF6F6FA)
@Composable
fun DeckManageContentPreview() {
    val sampleCards = listOf(
        CardUi(1L, "Serendipity", "뜻밖의 행운", "VOCAB", 0, CARD_NEW),
        CardUi(2L, "Ephemeral",   "덧없는, 단명하는", "VOCAB", 0, CARD_REVIEW),
        CardUi(3L, "Ubiquitous",  "어디에나 있는", "VOCAB", 0, CARD_LEARNING)
    )
    DeckManageContent(
        deckName = "영어 단어",
        cards = sampleCards,
        frontText = "", backText = "", tagsText = "",
        showDeleteDialog = false, cardToDelete = null,
        showEditDialog = false, cardToEdit = null,
        editFront = "", editBack = "", editTags = "",
        onFrontChange = {}, onBackChange = {}, onTagsChange = {},
        onAdd = {}, onEditRequest = {}, onDeleteRequest = {},
        onDeleteConfirm = {}, onDeleteDismiss = {},
        onEditFrontChange = {}, onEditBackChange = {}, onEditTagsChange = {},
        onEditConfirm = {}, onEditDismiss = {}, onBack = {}
    )
}
