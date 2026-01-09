package com.pasiflonet.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.databinding.ItemMessageRowBinding
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

class ChatAdapter(
    private var messages: List<TdApi.Message>,
    private val onDetailsClick: (TdApi.Message) -> Unit
) : RecyclerView.Adapter<ChatAdapter.RowHolder>() {

    fun updateList(newMessages: List<TdApi.Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    class RowHolder(val b: ItemMessageRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        return RowHolder(ItemMessageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val msg = messages[position]
        
        holder.b.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.date.toLong() * 1000))
        var text = ""
        var type = "Text"
        var fileIdToAutoDownload = 0
        
        when (msg.content) {
            is TdApi.MessageText -> { 
                text = (msg.content as TdApi.MessageText).text.text
                type = "📝" 
            }
            is TdApi.MessagePhoto -> { 
                val content = msg.content as TdApi.MessagePhoto
                text = content.caption.text
                type = "📷"
                // זיהוי הקובץ הכי גדול להורדה אוטומטית
                if (content.photo.sizes.isNotEmpty()) {
                    run {
                    val sizes = content.photo.sizes
                    val best = sizes.find { it.type == "x" } ?: sizes.find { it.type == "m" } ?: sizes.last()
                    fileIdToAutoDownload = best.photo.id
                }
                }
            }
            is TdApi.MessageVideo -> { 
                val content = msg.content as TdApi.MessageVideo
                text = content.caption.text
                type = "🎥"
                // זיהוי הוידאו להורדה אוטומטית
                fileIdToAutoDownload = content.video.video.id
            }
        }
        
        holder.b.tvMsgText.text = if (text.isEmpty()) "No Caption" else text
        holder.b.tvMediaType.text = type
        
        // --- זה השינוי הגדול: הורדה אוטומטית ---
        // ברגע שהשורה מופיעה על המסך, אנחנו מבקשים מטלגרם להוריד את הקובץ
        if (fileIdToAutoDownload != 0) {
            TdLibManager.downloadFile(fileIdToAutoDownload)
        }

        holder.b.btnDetails.setOnClickListener { onDetailsClick(msg) }
        
        // ביטול לחיצה על השורה עצמה (רק הכפתור פעיל)
        holder.itemView.setOnClickListener { null }
    }

    override fun getItemCount() = messages.size
}
