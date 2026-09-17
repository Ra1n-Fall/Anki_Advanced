package com.example.anki_advanced

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.anki_advanced.databinding.ActivitySettingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View

class DeckSettingActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingBinding
    private lateinit var db: AppDatabase
    private var deckId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deckId = intent.getLongExtra("deck_id", -1L)
        if (deckId == -1L) {
            finish()
            return
        }

        db = AppDatabase.getInstance(applicationContext)

        // 현재 저장된 한도값 불러와서 표시
        lifecycleScope.launch {
            val limits = withContext(Dispatchers.IO) {
                db.deckDao().getStudyLimits(deckId)
            }
            binding.etNewLimit.setText(limits.dailyNewLimit.toString())
            binding.etReviewLimit.setText(limits.dailyReviewLimit.toString())
        }

        binding.btnSave.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val newLimitStr = binding.etNewLimit.text.toString()
                val newLimit = newLimitStr.toIntOrNull()
                if (newLimit == null) return  // 변환 실패 시 종료

                val reviewLimitStr = binding.etReviewLimit.text.toString()
                val reviewLimit = reviewLimitStr.toIntOrNull()
                if (reviewLimit == null) return  // 변환 실패 시 종료

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.deckDao().updateStudyLimits(deckId, newLimit, reviewLimit)
                    }
                    finish()
                }
            }
        })
    }
}

