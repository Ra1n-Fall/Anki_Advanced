package com.example.anki_advanced

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import com.example.anki_advanced.databinding.ActivityDeckManageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CardUi(
    val id: Long,
    val front: String,
    val back: String,
    val tags: String,
    var state: Int,
    var status: Int
)

data class DeckStudyLimit(
    val dailyNewLimit: Int,
    val dailyReviewLimit: Int
)

class DeckManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckManageBinding
    private var deckId: Long = -1L

    private val items = mutableListOf<CardUi>()
    private lateinit var adapter: CardAdapter

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 덱 아이디를 바탕으로 선택된 덱을 구분
        deckId = intent.getLongExtra("deck_id", -1L)
        if (deckId == -1L) {
            finish()
            return
        }


        // activity_main.xml을 실제 View로 만들고, binding으로 id 뷰들에 접근할 준비를 합니다.
        binding = ActivityDeckManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Room으로 DB 인스턴스를 만들고, cardDao()를 통해 조회/삽입을 수행할 수 있게 합니다.
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "anki.db"
        )
            .build()

        // RecyclerView가 세로 목록으로 동작하도록 LayoutManager를 설정하고, items를 표시할 Adapter를 연결합니다.
        adapter = CardAdapter(items)
        adapter.setOnCardLongClickListener(object : CardAdapter.OnCardLongClickListener {
            override fun onCardLongClick(card: CardUi, position: Int) {
                showDeleteDialog(card, position)
            }
        })
        adapter.setOnCardMenuActionListener(object :
            CardAdapter.OnCardMenuActionListener {

            override fun onMenuAction(card: CardUi, position: Int, actionId: Int) {

                if (actionId == R.id.action_edit) {
                    showEditDialog(card, position)
                } else if (actionId == R.id.action_delete) {
                    showDeleteDialog(card, position)
                }
            }
        })

        binding.rvCards.layoutManager = LinearLayoutManager(this)
        binding.rvCards.adapter = adapter

        // 앱 시작 시 DB에 저장된 전체 카드를 읽어 화면 목록을 한 번 세팅합니다.
        initialLoadFromDb()

        // 입력값을 DB에 저장하고, 화면에는 새로 추가된 한 줄만 삽입 방식으로 반영합니다.
        binding.btnAdd.setOnClickListener(object : android.view.View.OnClickListener {
            override fun onClick(v: android.view.View?) {
                val front = binding.etFront.text.toString().trim()
                val back = binding.etBack.text.toString().trim()
                val tags = binding.etTags.text.toString().trim()

                if (front.isNotEmpty() && back.isNotEmpty()) {
                    insertCardAndUpdateUi(front, back, tags)

                    binding.etFront.setText("")
                    binding.etBack.setText("")
                    binding.etTags.setText("")
                }
            }
        })
    }

    // 앱 시작 시: DB 전체를 읽어 items를 구성하고 화면을 전체 갱신합니다.
    private fun initialLoadFromDb() {
        lifecycleScope.launch {//메인스레드에서 시작(작업 스레드 속 객체를 메인스레드에서도 사용하기 위해 전체를 감쌈)
            val all = withContext(Dispatchers.IO) {//작업스레드 작업 수행
                db.cardDao().getByDeck(deckId)
            }
            //메인스레드 작업 다시 시작

            // 중복 표시를 막기 위해 현재 items를 비우고, DB 내용으로 다시 채웁니다.
            items.clear()

            // DB 엔티티를 화면 표시용 모델(CardUi)로 바꿔서 리스트에 넣습니다.
            for (c in all) {
                items.add(CardUi(c.id, c.front, c.back, c.tags, c.state, c.status))
            }

            // 초기 로딩은 전체 내용이 한 번에 바뀌므로 전체 갱신을 사용합니다.
            adapter.notifyDataSetChanged()
        }
    }

    // 추가 동작: DB에 저장한 뒤, 화면에는 맨 위(0번)에 한 줄만 삽입 반영합니다.
    private fun insertCardAndUpdateUi(front: String, back: String, tags: String) {
        lifecycleScope.launch {//메인스레드에서 시작
            val entity = CardEntity(
                deckId = deckId,
                front = front,
                back = back,
                tags = tags,
                state = 0, //카드 점수와 학습 상태는 초기에 0, new임
                status = CARD_NEW
            )

            // DB에 insert
            val newId = withContext(Dispatchers.IO) {
                db.cardDao().insert(entity)
            }

            // 메인스레드 작업 수행(액티비티 리스트 작업) 메인화면 목록(items)의 0번 위치에 새 항목을 추가
            items.add(0, CardUi(newId, entity.front, entity.back, entity.tags, entity.state, entity.status))

            // 0번 위치에 새 아이템이 들어왔음을 RecyclerView에 알려 삽입 애니메이션과 부분 갱신을 사용합니다.
            adapter.notifyItemInserted(0)

            // 새로 추가된 항목이 화면 상단에 있으므로 바로 보이도록 스크롤합니다.
            binding.rvCards.scrollToPosition(0)
        }

    }

    private fun deleteCardAndUpdateUi(card: CardUi, position: Int) {

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.cardDao().deleteById(card.id)
            }

            val idx = items.indexOfFirst { it.id == card.id }
            if (idx != -1) {
                items.removeAt(idx)
                adapter.notifyItemRemoved(idx)
            }
        }
    }

    private fun updateCardAndUpdateUi(
        old: CardUi,
        position: Int,
        front: String,
        back: String,
        tags: String
    ) {
        val entity = CardEntity(//카드를 수정할 때 아이디와 학습 상태는 유지
            id = old.id,
            deckId = deckId,
            front = front,
            back = back,
            tags = tags,
            state = old.state,
            status = old.status
        )
        lifecycleScope.launch {

            withContext(Dispatchers.IO) {
                db.cardDao().update(entity)
            }

            if (position >= 0 && position < items.size) {
                items[position] = CardUi(old.id, front, back, tags, old.state, old.status)
                adapter.notifyItemChanged(position)
            }
        }
    }


    //다이얼로그를 띄우고 DB에서 삭제
    private fun showDeleteDialog(card: CardUi, position: Int) {

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("카드 삭제")
        builder.setMessage(
            "이 카드를 삭제하시겠습니까?\n\n" +
                    "Q: ${card.front}\n" +
                    "A: ${card.back}\n" +
                    "Tags: ${card.tags}"
        )

        builder.setPositiveButton("삭제", object : android.content.DialogInterface.OnClickListener {
            override fun onClick(dialog: android.content.DialogInterface?, which: Int) {
                deleteCardAndUpdateUi(card, position)   // 기존 삭제 함수 호출
            }
        })

        builder.setNegativeButton("취소", object : android.content.DialogInterface.OnClickListener {
            override fun onClick(dialog: android.content.DialogInterface?, which: Int) {
                dialog?.dismiss()// 다이얼로그 객체를 따로 선언하지 않아도 됨
            }
        })

        builder.show()
    }

    //다이얼로그를 띄우고 DB에서 수정
    private fun showEditDialog(card: CardUi, position: Int) {

        val view = layoutInflater.inflate(R.layout.dialog_edit_card, null)

        //view.findViewById를 사용해서 전개한 뷰에 속한 자식 뷰를 찾음
        val etFront = view.findViewById<android.widget.EditText>(R.id.etEditFront)
        val etBack = view.findViewById<android.widget.EditText>(R.id.etEditBack)
        val etTags = view.findViewById<android.widget.EditText>(R.id.etEditTags)

        etFront.setText(card.front)
        etBack.setText(card.back)
        etTags.setText(card.tags)

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)

        builder.setTitle("카드 수정")
        builder.setView(view)

        builder.setPositiveButton("저장", object : android.content.DialogInterface.OnClickListener {
            override fun onClick(dialog: android.content.DialogInterface?, which: Int) {

                val newFront = etFront.text.toString().trim()
                val newBack = etBack.text.toString().trim()
                val newTags = etTags.text.toString().trim()

                if (newFront.isEmpty() || newBack.isEmpty()) {
                    etFront.error = if (newFront.isEmpty()) "필수입니다" else null
                    etBack.error = if (newBack.isEmpty()) "필수입니다" else null
                    return
                }

                updateCardAndUpdateUi(card, position, newFront, newBack, newTags)
            }
        })



        builder.show()
    }



}

