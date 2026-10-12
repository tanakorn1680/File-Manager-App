package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.CommitInfo
import com.releasemanager.util.formatTimeAgo

/**
 * One row per version (commit). A name the person gave is shown as the title, with the commit
 * message under it; without a name the commit message is the title. The newest row is "Current".
 */
class HistoryAdapter(
    private val nameOf: (CommitInfo) -> String?,
    private val onClick: (CommitInfo) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<CommitInfo>()

    fun submitList(newItems: List<CommitInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Names changed: redraw the rows. */
    fun refresh() = notifyDataSetChanged()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textCommitName)
        val textMessage: TextView = view.findViewById(R.id.textCommitMessage)
        val textMeta: TextView = view.findViewById(R.id.textCommitMeta)
        val textCurrent: TextView = view.findViewById(R.id.textCommitCurrent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_commit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val commit = items[position]
        val ctx = holder.itemView.context
        val name = nameOf(commit)

        if (name != null) {
            holder.textName.text = name
            holder.textName.setTextColor(ctx.color(R.color.accent))
            holder.textMessage.text = commit.title
            holder.textMessage.visibility = if (commit.title.isBlank()) View.GONE else View.VISIBLE
        } else {
            holder.textName.text = commit.title.ifBlank { "(no message)" }
            holder.textName.setTextColor(ctx.color(R.color.text_primary))
            holder.textMessage.visibility = View.GONE
        }

        holder.textMeta.text = listOf(commit.shortSha, commit.author, formatTimeAgo(commit.date))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        holder.textCurrent.visibility = if (position == 0) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(commit) }
    }

    override fun getItemCount() = items.size
}
