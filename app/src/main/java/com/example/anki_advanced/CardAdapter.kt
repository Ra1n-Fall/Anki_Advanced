package com.example.anki_advanced

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 덱 관리 화면(DeckManageScreen 이전 버전)에서 카드 목록을 리스트로 그려주는 어댑터.
// DeckAdapter와 같은 패턴(RecyclerView.Adapter)이니, 궁금하면 DeckAdapter.kt의 주석도 참고.
class CardAdapter(private val items: List<CardUi>) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    // 카드를 길게 누르면(롱클릭) 밖에서 처리할 수 있도록 알려주는 콜백.
    interface OnCardLongClickListener {
        fun onCardLongClick(card: CardUi, position: Int)
    }
    interface OnCardMenuClickListener {
        fun onCardMenuClick()
    }

    private var cardlongClickListener: OnCardLongClickListener? = null

    fun setOnCardLongClickListener(listener: OnCardLongClickListener) {
        this.cardlongClickListener = listener
    }

    // 카드의 "더보기(⋮)" 메뉴에서 항목(수정/삭제 등)을 눌렀을 때 알려주는 콜백.
    interface OnCardMenuActionListener {
        fun onMenuAction(card: CardUi, position: Int, actionId: Int)
    }

    private var menuActionListener: OnCardMenuActionListener? = null

    fun setOnCardMenuActionListener(listener: OnCardMenuActionListener) {
        this.menuActionListener = listener
    }

    // 리스트 한 줄에 필요한 뷰들을 미리 찾아서 담아두는 뷰 홀더.
    class VH(val v: android.view.View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = itemView.findViewById(R.id.tvCardText)
        val btnMore: android.widget.ImageButton = itemView.findViewById(R.id.btnMore)
    }

    // item_card.xml 레이아웃을 실제 뷰 객체로 부풀려서(inflate) 뷰 홀더에 담아 반환.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    // position 번째 카드 데이터를 화면 한 줄(holder)에 채워 넣는다.
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // [문법] "문자열 ${표현식}"  → 문자열 템플릿. 변수/식을 문자열 안에 바로 끼워 넣을 수 있다.
        //   \n은 줄바꿈 문자.
        holder.tv.text = "Q: ${item.front}\nA: ${item.back}\nTags: ${item.tags}"

        // 카드 상태(state)에 따라 배경색을 다르게: 0=빨강(Again), 1=노랑(Hard), 그 외=초록(Good/Easy)
        // [문법] 0x22FF0000 처럼 0x로 시작하는 숫자 → 16진수(hex) 리터럴.
        //   안드로이드 색상은 보통 0xAARRGGBB(투명도-빨강-초록-파랑) 형태로 표현한다.
        if (item.state == 0) {
            holder.itemView.setBackgroundColor(0x22FF0000) // 옅은 빨강
        } else if (item.state == 1) {
            holder.itemView.setBackgroundColor(0x22FFFF00) // 옅은 노랑
        } else {
            holder.itemView.setBackgroundColor(0x2200FF00) // 옅은 초록
        }

        // 카드를 길게 누르면 롱클릭 리스너 실행
        holder.tv.setOnLongClickListener(object : android.view.View.OnLongClickListener {
            override fun onLongClick(v: android.view.View?): Boolean {

                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return true

                // [문법] val listener = cardlongClickListener; if (listener == null) return true
                //   ?. 안전 호출 대신, 지역 변수에 먼저 담아 null 체크를 하는 방식.
                //   이렇게 담아두면 이후 코드에서는 listener가 절대 null이 아님이 보장되어
                //   (스마트 캐스트) listener.onCardLongClick(...) 처럼 ?. 없이 바로 호출 가능.
                val listener = cardlongClickListener
                if (listener == null) return true

                listener.onCardLongClick(items[p], p)

                return true // true를 반환하면 "롱클릭 이벤트를 여기서 처리 완료"라는 뜻
            }
        })

        // "더보기(⋮)" 버튼을 누르면 팝업 메뉴를 띄운다.
        holder.btnMore.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {

                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return

                val current = items[p]

                // PopupMenu: 버튼 근처에 작은 메뉴 목록을 띄워주는 안드로이드 표준 위젯.
                val popup = android.widget.PopupMenu(holder.itemView.context, holder.btnMore)
                popup.menuInflater.inflate(R.menu.menu_card_modify, popup.menu)

                popup.setOnMenuItemClickListener(object :
                    android.widget.PopupMenu.OnMenuItemClickListener {

                    override fun onMenuItemClick(menuItem: android.view.MenuItem): Boolean {

                        val listener = menuActionListener
                        if (listener == null) return true

                        listener.onMenuAction(current, p, menuItem.itemId)
                        return true
                    }
                })

                popup.show()
            }
        })
    }

    override fun getItemCount(): Int = items.size
}
