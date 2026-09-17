package com.example.anki_advanced

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 홈 화면의 덱 목록을 스크롤 리스트로 그려주는 어댑터. (옛날 방식 View 시스템, XML 레이아웃 기반)
// Compose를 쓰는 화면과 달리, 이런 RecyclerView 방식은 "몇 번째 줄에 뭘 그릴지"를 직접 코드로 지정해야 한다.
//
// [문법] class X(private val items: MutableList<DeckUi>) : RecyclerView.Adapter<DeckAdapter.VH>()
//   RecyclerView.Adapter를 상속하면 "리스트를 화면에 어떻게 그릴지" 정의하는 표준 부품이 된다.
//   <DeckAdapter.VH> 는 "이 어댑터가 다루는 줄(행) 하나의 타입은 VH다"라는 뜻 (제네릭).
class DeckAdapter(private val items: MutableList<DeckUi>) :
    RecyclerView.Adapter<DeckAdapter.VH>() {

    // [문법] interface OnDeckClickListener { fun onDeckClick(...) }
    //   "이런 모양의 함수를 가진 뭔가가 있으면, 그게 뭐든 이벤트를 전달해주겠다"는 약속(계약).
    //   실제 처리는 이 어댑터를 사용하는 쪽(HomeScreen 등)에서 구현해서 넘겨준다.
    //   → 어댑터는 "무엇을 할지"는 모르고 "클릭이 일어났다"는 사실만 알림.
    interface OnDeckClickListener {
        fun onDeckClick(deck: DeckUi, position: Int)
    }

    interface OnDeckMoreClickListener {
        fun onDeckMoreClick(anchor: View, deck: DeckUi)
    }

    // 위 인터페이스의 실제 구현체를 담아둘 자리. 아직 아무도 등록 안 했으면 null.
    private var deckClickListener: OnDeckClickListener? = null
    private var deckMoreClickListener: OnDeckMoreClickListener? = null

    fun setOnDeckClickListener(listener: OnDeckClickListener) {
        this.deckClickListener = listener
    }

    fun setOnDeckMoreClickListener(listener: OnDeckMoreClickListener) {
        this.deckMoreClickListener = listener
    }

    // [문법] class VH(view: View) : RecyclerView.ViewHolder(view)
    //   "뷰 홀더(ViewHolder)": 리스트 한 줄에 필요한 뷰들(TextView, ImageButton 등)을
    //   미리 findViewById()로 찾아서 캐싱해두는 그릇. 스크롤할 때마다 매번 다시 찾지 않아도 돼서 빠르다.
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeckName)
        val tvNew: TextView = view.findViewById(R.id.tvNewCount)
        val tvLearn: TextView = view.findViewById(R.id.tvLearnCount)
        val tvReview: TextView = view.findViewById(R.id.tvReviewCount)
        val btnMore: ImageButton = view.findViewById(R.id.btnMore)
    }

    // 한 줄(item_deck.xml)을 새로 만들어야 할 때 RecyclerView가 호출.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deck, parent, false)
        return VH(view)
    }

    // 이미 만들어진 줄(holder)에 몇 번째(position) 데이터를 채워 넣을지 RecyclerView가 호출.
    // 스크롤하면서 화면 밖 줄은 재활용되고, 이 함수가 다시 불려서 새 데이터로 내용만 바뀐다.
    override fun onBindViewHolder(holder: VH, position: Int) {
        // [문법] holder.bindingAdapterPosition
        //   position 파라미터 대신 이걸 쓰는 이유: 리스트가 빠르게 바뀌는 도중에는
        //   바깥의 position 값이 이미 낡은(틀린) 값일 수 있어서, 항상 "지금 진짜 위치"를 다시 물어보는 것.
        //   RecyclerView.NO_POSITION 이면 "지금은 유효한 위치가 아님(삭제 애니메이션 중 등)" → 그냥 무시.
        val p = holder.bindingAdapterPosition
        if (p == RecyclerView.NO_POSITION) return
        val item = items[p]

        holder.tvName.text = item.name
        holder.tvNew.text = item.newCount.toString()
        holder.tvLearn.text = item.learnCount.toString()
        holder.tvReview.text = item.reviewCount.toString()

        // 아이템 전체 클릭 => 학습
        // [문법] object : View.OnClickListener { override fun onClick(v: View?) { ... } }
        //   인터페이스를 그 자리에서 즉석으로 구현하는 "익명 객체". setOnClickListener { ... } 람다와
        //   결과는 같지만, 옛날 자바 스타일 코드에서 흔히 쓰던 표기법.
        holder.itemView.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val deck = items[pos]
                // [문법] deckClickListener?.onDeckClick(...)
                //   ?. 은 "안전 호출": deckClickListener가 null이면 아무 일도 안 일어나고 넘어감
                //   (등록된 리스너가 없으면 그냥 무시).
                deckClickListener?.onDeckClick(deck = deck, pos)
            }
        })

        // 점 세개(더보기) 클릭 => 팝업 메뉴
        holder.btnMore.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val deck = items[pos]
                deckMoreClickListener?.onDeckMoreClick(anchor = holder.btnMore, deck = deck)
            }
        })
    }

    // 총 몇 줄을 그릴지 RecyclerView에게 알려준다.
    override fun getItemCount(): Int = items.size
}
