package com.kira.tts

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val entries = ArrayList<LogStore.Entry>()

    fun submit(list: List<LogStore.Entry>) {
        entries.clear()
        entries.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val tvSrc: TextView = v.findViewById(R.id.tvSrc)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvDetail: TextView = v.findViewById(R.id.tvDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = entries[position]
        holder.tvTime.text = DateFormat.format("HH:mm:ss", Date(e.tsMs))
        holder.tvSrc.text = "${e.frame.sysId}.${e.frame.compId} v${e.frame.version}"
        holder.tvName.text = e.decoded.name
        holder.tvDetail.text = e.decoded.detail

        val ctx = holder.itemView.context
        val colorRes = when (e.decoded.severity) {
            MessageCatalog.Severity.CRITICAL -> R.color.severity_critical
            MessageCatalog.Severity.WARNING -> R.color.severity_warning
            MessageCatalog.Severity.INFO -> R.color.severity_info
            MessageCatalog.Severity.OK -> R.color.severity_ok
        }
        holder.tvName.setTextColor(ContextCompat.getColor(ctx, colorRes))
    }

    override fun getItemCount(): Int = entries.size
}
