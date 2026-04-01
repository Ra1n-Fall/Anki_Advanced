package com.example.anki_advanced

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeckAdapter(private val items: MutableList<DeckUi>) :
    RecyclerView.Adapter<DeckAdapter.VH>() {

    interface OnDeckClickListener {
        fun onDeckClick(deck: DeckUi, position: Int)
    }

    interface OnDeckMoreClickListener {
        fun onDeckMoreClick(anchor: View, deck: DeckUi)
    }

    private var deckClickListener: OnDeckClickListener? = null
    private var deckMoreClickListener: OnDeckMoreClickListener? = null

    fun setOnDeckClickListener(listener: OnDeckClickListener) {
        this.deckClickListener = listener
    }

    fun setOnDeckMoreClickListener(listener: OnDeckMoreClickListener) {
        this.deckMoreClickListener = listener
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeckName)
        val tvNew: TextView = view.findViewById(R.id.tvNewCount)
        val tvLearn: TextView = view.findViewById(R.id.tvLearnCount)
        val tvReview: TextView = view.findViewById(R.id.tvReviewCount)
        val btnMore: ImageButton = view.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deck, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = holder.bindingAdapterPosition
        if (p == RecyclerView.NO_POSITION) return
        val item = items[p]

        holder.tvName.text = item.name
        holder.tvNew.text = item.newCount.toString()
        holder.tvLearn.text = item.learnCount.toString()
        holder.tvReview.text = item.reviewCount.toString()

        // 아이템 전체 클릭 => 학습
        holder.itemView.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val deck = items[pos]
                deckClickListener?.onDeckClick(deck = deck, pos)
            }
        })

        // 점 세개 클릭 => 팝업 메뉴
        holder.btnMore.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val deck = items[pos]
                deckMoreClickListener?.onDeckMoreClick(anchor = holder.btnMore, deck = deck)
            }
        })
    }

    override fun getItemCount(): Int = items.size
}
