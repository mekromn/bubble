package com.mekromn.bubble

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Separate opaque overlay. RecyclerView recycles cards; no live browser view is transformed. */
internal class TabTray(c: Context, private val select: (String)->Unit, private val close: (String)->Unit,
    newChat: ()->Unit, dismiss: ()->Unit) : LinearLayout(c) {
    private data class Card(val id: String,val title: String,val host: String,val state: String,val selected: Boolean)
    private var all = emptyList<Card>()
    private var query=""
    private val adapter = Cards()
    private val countText = Ui.text(c,"",12f,Ui.MUTED)
    init {
        orientation=VERTICAL; setBackgroundColor(Ui.BG); isClickable=true; isFocusable=true
        setPadding(d(14),d(8),d(14),d(12))
        val head=LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL }
        val text=LinearLayout(c).apply { orientation=VERTICAL; setPadding(d(6),d(8),0,d(8)) }
        text.addView(Ui.text(c,"Your workspace",26f,Ui.TEXT,true)); countText.setPadding(0,d(6),0,0);text.addView(countText)
        head.addView(text,LayoutParams(0,-2,1f))
        head.addView(GlyphView(c,"close","Close workspace").apply { setOnClickListener { dismiss() } },LayoutParams(d(48),d(48)))
        addView(head)
        val search=EditText(c).apply {
            hint="Find a conversation"; contentDescription="Find a conversation";setSingleLine(true);textSize=15f
            setTextColor(Ui.TEXT);setHintTextColor(Ui.MUTED);background=Ui.shape(c,Ui.SURFACE,22f)
            setPadding(d(18),0,d(18),0)
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString();filter() }
                override fun afterTextChanged(s:Editable?)=Unit
            })
        }
        addView(search,LayoutParams(-1,d(50)).apply{setMargins(0,d(14),0,d(10))})
        val recycler=RecyclerView(c).apply {
            layoutManager=GridLayoutManager(c,2);adapter=this@TabTray.adapter
            clipToPadding=false;setPadding(0,d(4),0,d(12));itemAnimator=null
            addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
                val manager=layoutManager as GridLayoutManager
                val span=((right-left)/d(175)).coerceAtLeast(1)
                if(manager.spanCount!=span)manager.spanCount=span
            }
        }
        addView(recycler,LayoutParams(-1,0,1f))
        addView(Ui.text(c,"＋  New ChatGPT chat",16f,Ui.BG,true).apply {
            gravity=Gravity.CENTER;background=Ui.ripple(c,Ui.BLUE,25f);setOnClickListener{newChat()}
        },LayoutParams(-1,d(52)))
    }
    fun refresh(workspace: Workspace) {
        val next=workspace.tabs.map { tab -> Card(tab.id,tab.title.ifBlank{"New chat"},Policy.host(tab.url),
            when { tab.error!=null->"Needs attention";tab.generating->"Generating";tab.unread->"New reply";tab.loading->"Loading";Policy.isChat(tab.url)->"Live chat";else->"Web tab" }, tab.id==workspace.selectedId) }
        if(all==next)return
        all=next;countText.text="${next.size} tabs · one bubble";filter()
    }
    private fun filter() { adapter.submitList(all.filter{it.title.contains(query,true)||it.host.contains(query,true)}) }
    private fun d(n:Int)=Ui.dp(context,n.toFloat())
    private inner class Holder(val box:LinearLayout,val title:TextView,val host:TextView,val state:TextView,val icon:GlyphView,val close:GlyphView):RecyclerView.ViewHolder(box)
    private inner class Cards:ListAdapter<Card,Holder>(object:DiffUtil.ItemCallback<Card>(){
        override fun areItemsTheSame(a:Card,b:Card)=a.id==b.id
        override fun areContentsTheSame(a:Card,b:Card)=a==b
    }) {
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val c=parent.context
            val box=LinearLayout(c).apply {
                orientation=VERTICAL;setPadding(d(14),d(10),d(10),d(16))
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply{setMargins(d(5),d(5),d(5),d(5))}
                minimumHeight=d(180)
            }
            val row=LinearLayout(c).apply{gravity=Gravity.CENTER_VERTICAL}
            val icon=GlyphView(c,"bubble","Conversation",true).apply{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            val close=GlyphView(c,"close","Close tab")
            row.addView(icon,LayoutParams(d(40),d(40)));row.addView(Space(c),LayoutParams(0,1,1f));row.addView(close,LayoutParams(d(48),d(48)))
            box.addView(row)
            val title=Ui.text(c,"",16f,Ui.TEXT,true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPadding(0,d(16),d(4),0) }
            val host=Ui.text(c,"",12f,Ui.MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(0,d(8),0,0) }
            val state=Ui.text(c,"",11f,Ui.MINT).apply { setPadding(0,d(14),0,0) }
            box.addView(title);box.addView(host);box.addView(state)
            return Holder(box,title,host,state,icon,close)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val card=getItem(position)
            holder.box.background=Ui.shape(context,if(card.selected)Ui.SURFACE_HIGH else Ui.SURFACE,22f,if(card.selected)Ui.BLUE else Ui.LINE)
            holder.title.text=card.title;holder.host.text=card.host;holder.state.text=card.state
            holder.box.contentDescription="${card.title}, ${card.state}${if(card.selected)", selected" else ""}"
            holder.box.setOnClickListener{select(card.id)};holder.close.contentDescription="Close ${card.title}"
            holder.close.setOnClickListener{close(card.id)}
        }
    }
}
