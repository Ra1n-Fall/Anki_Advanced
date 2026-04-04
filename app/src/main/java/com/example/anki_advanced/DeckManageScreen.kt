package com.example.anki_advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

private val DeckBg = Color(0xFFF6F6FA)
private val DeckSurface = Color(0xFFFFFFFF)
private val DeckSurfaceLow = Color(0xFFF0F0F5)
private val DeckPrimary = Color(0xFF4330F9)
private val DeckPrimaryDim = Color(0xFF3517EE)
private val DeckOnPrimary = Color(0xFFF1EDFF)
private val DeckOnSurface = Color(0xFF2D2F32)
private val DeckOnSurfaceMuted = Color(0xFF5A5B5F)
private val DeckOutline = Color(0xFFACADB1)
private val DeckError = Color(0xFFB41340)
private val DeckTag = Color(0xFFE8E9FF)

@Composable
fun DeckManageScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    deckName: String = "영어 단어",
    viewModel: DeckManageViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(deckId, deckName) {
        viewModel.initialize(deckId = deckId, deckName = deckName)
    }

    DeckManageContent(
        uiState = uiState,
        onBack = { navController?.popBackStack() },
        onFrontChange = viewModel::onFrontTextChange,
        onBackChange = viewModel::onBackTextChange,
        onTagsChange = viewModel::onTagsTextChange,
        onSearchChange = viewModel::onSearchQueryChange,
        onAdd = viewModel::onAddCard,
        onEditRequest = viewModel::onEditRequest,
        onDeleteRequest = viewModel::onDeleteRequest,
        onDeleteConfirm = viewModel::confirmDeleteCard,
        onDeleteDismiss = viewModel::dismissDeleteDialog,
        onEditFrontChange = viewModel::onEditFrontChange,
        onEditBackChange = viewModel::onEditBackChange,
        onEditTagsChange = viewModel::onEditTagsChange,
        onEditConfirm = viewModel::confirmEditCard,
        onEditDismiss = viewModel::dismissEditDialog
    )
}

@Composable
private fun DeckManageContent(
    uiState: DeckManageUiState,
    onBack: () -> Unit,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEditRequest: (CardUi) -> Unit,
    onDeleteRequest: (CardUi) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteDismiss: () -> Unit,
    onEditFrontChange: (String) -> Unit,
    onEditBackChange: (String) -> Unit,
    onEditTagsChange: (String) -> Unit,
    onEditConfirm: () -> Unit,
    onEditDismiss: () -> Unit
) {
    Scaffold(
        containerColor = DeckBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = DeckPrimary,
                contentColor = DeckOnPrimary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "카드 추가")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                DeckManageTopBar(
                    deckName = uiState.deckName,
                    searchQuery = uiState.searchQuery,
                    onSearchChange = onSearchChange,
                    onBack = onBack,
                    onAdd = onAdd
                )
            }

            item {
                QuickAddCard(
                    frontText = uiState.frontText,
                    backText = uiState.backText,
                    tagsText = uiState.tagsText,
                    onFrontChange = onFrontChange,
                    onBackChange = onBackChange,
                    onTagsChange = onTagsChange,
                    onAdd = onAdd
                )
            }

            item {
                SectionHeader(
                    title = "최근 추가된 카드",
                    subtitle = "${uiState.allCards.size}장의 카드가 이 덱에 들어 있습니다."
                )
            }

            items(uiState.visibleCards, key = { it.id }) { card ->
                DeckCardRow(
                    card = card,
                    onEdit = { onEditRequest(card) },
                    onDelete = { onDeleteRequest(card) }
                )
            }

            item {
                BottomHint()
            }
        }
    }

    if (uiState.showDeleteDialog && uiState.cardToDelete != null) {
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
            containerColor = DeckSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "카드를 삭제할까요?",
                    color = DeckOnSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "\"${uiState.cardToDelete.front}\" 카드를 삭제하면 되돌릴 수 없습니다.",
                    color = DeckOnSurfaceMuted
                )
            },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) {
                    Text("삭제", color = DeckError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismiss) {
                    Text("취소", color = DeckOnSurfaceMuted)
                }
            }
        )
    }

    if (uiState.showEditDialog) {
        AlertDialog(
            onDismissRequest = onEditDismiss,
            containerColor = DeckSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "카드 수정",
                    color = DeckOnSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeckInputField(
                        value = uiState.editFront,
                        placeholder = "앞면",
                        onValueChange = onEditFrontChange
                    )
                    DeckInputField(
                        value = uiState.editBack,
                        placeholder = "뒷면",
                        onValueChange = onEditBackChange
                    )
                    DeckInputField(
                        value = uiState.editTags,
                        placeholder = "태그",
                        onValueChange = onEditTagsChange
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onEditConfirm) {
                    Text("저장", color = DeckPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onEditDismiss) {
                    Text("취소", color = DeckOnSurfaceMuted)
                }
            }
        )
    }
}

@Composable
private fun DeckManageTopBar(
    deckName: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DeckSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "뒤로 가기",
                    tint = DeckOnSurface
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "덱 카드 추가",
                    color = DeckOnSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = deckName,
                    color = DeckOnSurfaceMuted,
                    fontSize = 13.sp
                )
            }

            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DeckSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "카드 추가",
                    tint = DeckPrimary
                )
            }
        }

        SearchField(
            value = searchQuery,
            onValueChange = onSearchChange
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text("카드 앞면, 뒷면, 태그 검색", color = DeckOnSurfaceMuted)
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = DeckOnSurfaceMuted)
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DeckSurface,
            unfocusedContainerColor = DeckSurface,
            focusedBorderColor = DeckOutline.copy(alpha = 0.4f),
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = DeckOnSurface,
            unfocusedTextColor = DeckOnSurface,
            cursorColor = DeckPrimary
        ),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun QuickAddCard(
    frontText: String,
    backText: String,
    tagsText: String,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DeckSurface)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "새 카드를 빠르게 추가하세요.",
            color = DeckOnSurfaceMuted,
            fontSize = 13.sp
        )

        DeckInputLine(
            value = frontText,
            placeholder = "앞면 텍스트",
            onValueChange = onFrontChange
        )

        DeckInputLine(
            value = backText,
            placeholder = "뒷면 텍스트",
            onValueChange = onBackChange
        )

        DeckInputLine(
            value = tagsText,
            placeholder = "태그",
            onValueChange = onTagsChange
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(DeckPrimary, DeckPrimaryDim)
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAdd
                    )
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "추가",
                    color = DeckOnPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun DeckInputLine(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(placeholder, color = DeckOnSurfaceMuted.copy(alpha = 0.75f))
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                cursorColor = DeckPrimary,
                focusedTextColor = DeckOnSurface,
                unfocusedTextColor = DeckOnSurface
            ),
            shape = RoundedCornerShape(0.dp)
        )
        HorizontalDivider(color = DeckOutline.copy(alpha = 0.35f))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = DeckOnSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = DeckOnSurfaceMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DeckCardRow(
    card: CardUi,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(DeckSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = card.front,
                    color = DeckOnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLabel(card.status),
                    color = statusColor(card.status),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "수정",
                        tint = DeckOnSurfaceMuted
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "삭제",
                        tint = DeckOnSurfaceMuted
                    )
                }
            }
        }

        Text(
            text = card.back,
            color = DeckOnSurfaceMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        if (card.tags.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(DeckTag)
                    .border(
                        width = 1.dp,
                        color = DeckOutline.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = card.tags,
                    color = DeckPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BottomHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(DeckSurfaceLow),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = DeckOnSurfaceMuted
            )
        }

        Text(
            text = "학습 카드 추가 버튼을 눌러 새 카드를 덱에 더해보세요.",
            color = DeckOnSurfaceMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DeckInputField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DeckSurfaceLow,
            unfocusedContainerColor = DeckSurfaceLow,
            focusedBorderColor = DeckPrimary.copy(alpha = 0.35f),
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = DeckOnSurface,
            unfocusedTextColor = DeckOnSurface,
            cursorColor = DeckPrimary
        ),
        shape = RoundedCornerShape(18.dp)
    )
}

private fun statusLabel(status: Int): String = when (status) {
    CARD_NEW -> "NEW"
    CARD_LEARNING -> "LEARNING"
    CARD_REVIEW -> "REVIEW"
    else -> "CARD"
}

private fun statusColor(status: Int): Color = when (status) {
    CARD_NEW -> DeckPrimary
    CARD_LEARNING -> Color(0xFFF97316)
    CARD_REVIEW -> Color(0xFF22C55E)
    else -> DeckOnSurfaceMuted
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F6FA, widthDp = 390, heightDp = 844)
@Composable
private fun DeckManagePreview() {
    MaterialTheme {
        DeckManageContent(
            uiState = DeckManageUiState(
                deckName = "영어 단어",
                allCards = listOf(
                    CardUi(1L, "Virtual Memory", "가상 메모리", "OS", 0, CARD_NEW),
                    CardUi(2L, "Race Condition", "동시 실행 환경의 경쟁 상태", "CS", 0, CARD_LEARNING),
                    CardUi(3L, "TCP vs UDP", "연결 지향과 비연결 지향 전송 비교", "NETWORK", 0, CARD_REVIEW)
                ),
                visibleCards = listOf(
                    CardUi(1L, "Virtual Memory", "가상 메모리", "OS", 0, CARD_NEW),
                    CardUi(2L, "Race Condition", "동시 실행 환경의 경쟁 상태", "CS", 0, CARD_LEARNING),
                    CardUi(3L, "TCP vs UDP", "연결 지향과 비연결 지향 전송 비교", "NETWORK", 0, CARD_REVIEW)
                )
            ),
            onBack = {},
            onFrontChange = {},
            onBackChange = {},
            onTagsChange = {},
            onSearchChange = {},
            onAdd = {},
            onEditRequest = {},
            onDeleteRequest = {},
            onDeleteConfirm = {},
            onDeleteDismiss = {},
            onEditFrontChange = {},
            onEditBackChange = {},
            onEditTagsChange = {},
            onEditConfirm = {},
            onEditDismiss = {}
        )
    }
}
