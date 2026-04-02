package com.example.anki_advanced

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



data class DeckUi(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int
)


/*
class HomeActivity : AppCompatActivity() {

    private val items = mutableListOf<DeckUi>()   // 화면에 표시될 덱 목록(UI 모델)
    private lateinit var adapter: DeckAdapter     // RecyclerView와 items를 연결하는 어댑터

    private lateinit var rvDecks: RecyclerView    // 덱 목록을 보여줄 RecyclerView
    private lateinit var btnAddDeck: Button       // 덱 추가 버튼

    private lateinit var db: AppDatabase          // Room DB 인스턴스

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // XML에 있는 RecyclerView와 버튼을 코드 객체로 연결
        rvDecks = findViewById(R.id.rvDecks)
        btnAddDeck = findViewById(R.id.btnAddDeck)

        // DB 생성
        // "anki.db"라는 이름의 실제 DB 파일을 열거나, 없으면 새로 생성
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "anki.db"
        ).fallbackToDestructiveMigration().build()

        // RecyclerView에 연결할 어댑터 생성 및 덱 목록 연결
        adapter = DeckAdapter(items)

        // 일반 클릭 => 학습 화면
        adapter.setOnDeckClickListener(object : DeckAdapter.OnDeckClickListener {
            override fun onDeckClick(deck: DeckUi, position: Int) {
                val intent = Intent(this@HomeActivity, StudyActivity::class.java)

                // 선택된 덱의 id를 StudyActivity로 전달
                intent.putExtra("deck_id", deck.id)      // id 기반
                intent.putExtra("deckName", deck.name)   // (선택) 화면 타이틀 표시용
                startActivity(intent)
            }
        })

        // 점 3개(더보기) 클릭 시 호출되는 콜백
        adapter.setOnDeckMoreClickListener(object : DeckAdapter.OnDeckMoreClickListener {
            override fun onDeckMoreClick(anchor: View, deck: DeckUi) {
                // 어떤 덱의 메뉴인지 deck 객체로 구분
                showDeckMoreMenu(anchor, deck)
            }
        })

        // RecyclerView를 세로 리스트 형태로 설정
        rvDecks.layoutManager = LinearLayoutManager(this)
        rvDecks.adapter = adapter

        // 덱 추가 버튼 클릭 시 다이얼로그 표시
        btnAddDeck.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                showAddDeckDialog()
            }
        })

        // 앱 시작 시 DB에 저장된 덱 목록을 불러와 화면에 표시
        LoadFromDbAndRefresh()
    }
    private var isFirstLoad = true
    override fun onResume() {
        super.onResume()//명시적으로 부모 함수 실행

        if (isFirstLoad) {
            isFirstLoad = false
            return  // onCreate에서 이미 호출했으니 스킵
        }

        // 학습 화면에서 돌아올 때마다 카운트 갱신
        LoadFromDbAndRefresh()
    }



    private fun LoadFromDbAndRefresh() {
        lifecycleScope.launch {

            val now = System.currentTimeMillis()
            val todayStart = startOfTodayMillis(now)

            // DB 조회는 IO 스레드에서 수행
            val all = withContext(Dispatchers.IO) {
                db.deckDao().getAll()
            }

            // 메인 스레드로 돌아와 화면 데이터 갱신
            items.clear()   // 기존 목록 제거

            // DB 엔티티를 UI용 DeckUi로 변환하여 리스트에 추가
            for (e in all) {
                val newCount = withContext(Dispatchers.IO) {
                    db.cardDao().countNewCards(e.id)
                }
                val learnCount = withContext(Dispatchers.IO) {
                    db.cardDao().countLearningCards(e.id)
                }
                val reviewCount = withContext(Dispatchers.IO) {
                    db.cardDao().countReviewCards(e.id, todayStart)
                }
                items.add(DeckUi(e.id, e.name, newCount, learnCount, reviewCount))
            }

            adapter.notifyDataSetChanged()
        }
    }

    private fun showAddDeckDialog() {
        val et = EditText(this)
        et.hint = "덱 이름"

        val dialog = AlertDialog.Builder(this)
            .setTitle("덱 추가")
            .setView(et)
            .setPositiveButton("추가", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.show()

        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val name = et.text.toString().trim()
                if (name.isEmpty()) return

                lifecycleScope.launch {
                    // DB insert는 IO 스레드에서 실행
                    val newId = withContext(Dispatchers.IO) {
                        db.deckDao().insert(DeckEntity(name = name))
                    }

                    // 새로 생성된 덱을 화면 리스트 맨 위에 추가
                    items.add(0, DeckUi(newId, name, 0, 0, 0))
                    adapter.notifyItemInserted(0)
                    rvDecks.scrollToPosition(0)

                    dialog.dismiss()
                }
            }
        })
    }

    private fun showDeckMoreMenu(anchor: View, deck: DeckUi) {
            val popup = PopupMenu(this, anchor)// 앵커에 붙어서 메뉴 팝업을 띄움( 앵커는 점 세개 )
            popup.menuInflater.inflate(R.menu.menu_deck_more, popup.menu)

        popup.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem): Boolean {

                if (item.itemId == R.id.action_manage) {   // 카드 관리로 이동
                    val intent = Intent(this@HomeActivity, DeckManageActivity::class.java)
                    intent.putExtra("deck_id", deck.id)    // deck_id 넘김
                    intent.putExtra("deckName", deck.name) // (선택) 타이틀 표시용
                    startActivity(intent)
                    return true
                }
                if (item.itemId == R.id.action_delete) {    // 덱 삭제 알림 띄우기
                    showDeleteDeckDialog(deck)
                    return true
                }
                if (item.itemId == R.id.action_settings) {  // 덱 설정으로 이동
                    val intent = Intent(this@HomeActivity, DeckSettingActivity::class.java)
                    intent.putExtra("deck_id", deck.id)
                    intent.putExtra("deckName", deck.name)
                    startActivity(intent)
                    return true
                }

                return false
            }
        })

        popup.show()
    }

    private fun showDeleteDeckDialog(deck: DeckUi) {      // ★ 변경: id 기반 삭제
        val builder = AlertDialog.Builder(this)
            .setTitle("덱 삭제")
            .setMessage("정말 삭제하시겠습니까?\n\n${deck.name}")
            .setPositiveButton("삭제", object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface?, which: Int) {

                    lifecycleScope.launch{
                        // DB에서 해당 덱 삭제
                        withContext(Dispatchers.IO) {
                            db.deckDao().deleteById(deck.id)
                            Unit
                        }

                        // 현재 화면 리스트에서 동일한 id를 가진 덱의 위치를 다시 찾음
                        val idx = items.indexOfFirst { it.id == deck.id }
                        // decks 리스트를 하나씩 순회하여 id가 같은 덱 하나의 위치를 반환
                        if (idx != -1) {
                            items.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                    }
                }
            })
            .setNegativeButton("취소", null)

        builder.show()
    }

    private fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
*/


