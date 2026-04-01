package com.example.anki_advanced

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CardAdapter(private val items: List<CardUi>) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    interface OnCardLongClickListener {// 메인액티비티에서 구현해야할 작업은 리스너를 통해 메인 액티비티와 연결
        fun onCardLongClick(card: CardUi, position: Int)
    }
    interface OnCardMenuClickListener {
        fun onCardMenuClick()
    }

    private var cardlongClickListener: OnCardLongClickListener? = null
    //private OnCardLongClickListener longClickListener = null;

    fun setOnCardLongClickListener(listener: OnCardLongClickListener) {
        this.cardlongClickListener = listener
    }

    interface OnCardMenuActionListener {
        fun onMenuAction(card: CardUi, position: Int, actionId: Int)
    }


    private var menuActionListener: OnCardMenuActionListener? = null

    fun setOnCardMenuActionListener(listener: OnCardMenuActionListener) {
        this.menuActionListener = listener
    }


    class VH(val v: android.view.View) : RecyclerView.ViewHolder(v){
        val tv: TextView = itemView.findViewById(R.id.tvCardText)
        val btnMore: android.widget.ImageButton = itemView.findViewById(R.id.btnMore)
    }

    //별도의 아이템뷰를 뷰홀더에 적용
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // position에 해당하는 데이터를 꺼내서 ViewHolder의 TextView에 표시합니다.
        val item = items[position]

        holder.tv.text = "Q: ${item.front}\nA: ${item.back}\nTags: ${item.tags}"

        if (item.state == 0) {
            holder.itemView.setBackgroundColor(0x22FF0000) // 빨강
        } else if (item.state == 1) {
            holder.itemView.setBackgroundColor(0x22FFFF00) // 노랑
        } else {
            holder.itemView.setBackgroundColor(0x2200FF00) // 초록
        }

        holder.tv.setOnLongClickListener(object : android.view.View.OnLongClickListener {
            override fun onLongClick(v: android.view.View?): Boolean {

                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return true

                val listener = cardlongClickListener
                if (listener == null) return true

                listener.onCardLongClick(items[p], p)

                return true
            }
        })

        holder.btnMore.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {

                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return

                val current = items[p]

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

