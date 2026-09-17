package com.example.anki_advanced

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.anki_advanced.databinding.ActivitySettingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View

// 덱 하나의 "하루 학습 한도(새 카드/복습 카드 몇 장)"를 수정하는 화면.
// 이 파일은 Jetpack Compose가 아니라 옛날 방식인 XML 레이아웃 + Activity로 만들어져 있다.
//
// [문법] class X : AppCompatActivity()
//   화면 하나(Activity)를 정의할 때 상속받는 기본 클래스. onCreate()에서 화면을 그리고 초기화한다.
class DeckSettingActivity : AppCompatActivity() {

    // [문법] lateinit var
    //   "나중에 초기화하겠다"는 약속. var인데 처음엔 값이 없어도 되고(null이 아니라 "아직 안 채움" 상태),
    //   onCreate()에서 반드시 값을 넣어줘야 한다. 안 넣고 쓰면 런타임 에러(UninitializedPropertyAccessException).
    private lateinit var binding: ActivitySettingBinding  // XML 레이아웃의 뷰들(버튼, 입력창 등)에 접근하는 객체
    private lateinit var db: AppDatabase
    private var deckId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [문법] ActivitySettingBinding.inflate(layoutInflater)
        //   "뷰 바인딩(View Binding)": activity_setting.xml에 있는 뷰들을 코드에서
        //   findViewById() 없이 binding.etNewLimit 처럼 바로 꺼내 쓸 수 있게 자동 생성된 클래스.
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 이 화면을 연 쪽(HomeScreen 등)에서 "deck_id"라는 이름으로 넘겨준 값을 꺼낸다.
        // 값이 없으면 기본값 -1L이 들어오고, 그러면 잘못된 진입이므로 화면을 바로 닫는다.
        deckId = intent.getLongExtra("deck_id", -1L)
        if (deckId == -1L) {
            finish()
            return
        }

        db = AppDatabase.getInstance(applicationContext)

        // [문법] lifecycleScope.launch { ... }
        //   viewModelScope와 비슷하지만 Activity/Fragment의 생명주기에 묶인 코루틴 범위.
        //   화면이 종료되면 이 코루틴도 자동으로 취소된다.
        // 현재 저장된 한도값을 DB에서 불러와 입력창에 채워 넣는다.
        lifecycleScope.launch {
            val limits = withContext(Dispatchers.IO) {
                db.deckDao().getStudyLimits(deckId)
            }
            binding.etNewLimit.setText(limits.dailyNewLimit.toString())
            binding.etReviewLimit.setText(limits.dailyReviewLimit.toString())
        }

        // [문법] object : View.OnClickListener { override fun onClick(...) { ... } }
        //   "익명 객체(anonymous object)" 문법. View.OnClickListener라는 인터페이스를
        //   그 자리에서 즉석으로 구현해서 넘겨주는 방식. 요즘은 setOnClickListener { ... } 람다로
        //   더 짧게 쓰는 게 보통이지만, 옛날 스타일 코드에서는 이렇게 object 표현식을 자주 본다.
        binding.btnSave.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val newLimitStr = binding.etNewLimit.text.toString()
                // [문법] "abc".toIntOrNull()
                //   문자열을 숫자로 바꾸되, 변환이 불가능하면(사용자가 숫자 아닌 글자를 입력 등)
                //   예외를 던지는 대신 null을 돌려주는 안전한 변환 함수.
                val newLimit = newLimitStr.toIntOrNull()
                if (newLimit == null) return  // 숫자로 변환 안 되면 저장하지 않고 종료

                val reviewLimitStr = binding.etReviewLimit.text.toString()
                val reviewLimit = reviewLimitStr.toIntOrNull()
                if (reviewLimit == null) return

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.deckDao().updateStudyLimits(deckId, newLimit, reviewLimit)
                    }
                    finish() // 저장 끝나면 이 화면을 닫고 이전 화면으로 돌아감
                }
            }
        })
    }
}
