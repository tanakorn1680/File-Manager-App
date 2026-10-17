package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.RepoRef
import com.releasemanager.util.formatSize
import com.releasemanager.util.formatTimeAgo

/** Web: renderRepos() — one `.repo-item` per repository. */
class RepoAdapter(
    private val onOpen: (RepoRef) -> Unit,
    private val onEdit: (RepoRef) -> Unit,
    private val onMore: (RepoRef) -> Unit
) : RecyclerView.Adapter<RepoAdapter.ViewHolder>() {

    private val items = mutableListOf<RepoRef>()

    fun submitList(newItems: List<RepoRef>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textRepoName)
        val textDesc: TextView = view.findViewById(R.id.textRepoDesc)
        val textBadge: TextView = view.findViewById(R.id.textBadge)
        val textMeta: TextView = view.findViewById(R.id.textRepoMeta)
        val dotLang: View = view.findViewById(R.id.viewLangDot)
        val textLang: TextView = view.findViewById(R.id.textRepoLang)
        val btnEdit: ImageButton = view.findViewById(R.id.btnRepoEdit)
        val btnMore: ImageButton = view.findViewById(R.id.btnRepoMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_repo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val repo = items[position]
        val ctx = holder.itemView.context

        holder.textName.text = repo.fullName.repoShortName()
        if (repo.description.isNullOrBlank()) {
            holder.textDesc.visibility = View.GONE
        } else {
            holder.textDesc.text = repo.description
            holder.textDesc.visibility = View.VISIBLE
        }

        holder.textMeta.text = listOf(
            if (repo.stars > 0) "★ ${repo.stars}" else "",
            formatSize(repo.sizeKb * 1024),
            formatTimeAgo(repo.updatedAt)
        ).filter { it.isNotEmpty() }.joinToString(" · ")

        val lang = repo.language
        if (lang.isNullOrBlank()) {
            holder.dotLang.visibility = View.GONE
            holder.textLang.visibility = View.GONE
        } else {
            holder.dotLang.background = ovalDrawable(languageColor(lang))
            holder.textLang.text = lang
            holder.dotLang.visibility = View.VISIBLE
            holder.textLang.visibility = View.VISIBLE
        }

        if (repo.private) {
            holder.textBadge.text = I18n.tr("Private")
            holder.textBadge.setBackgroundResource(R.drawable.bg_badge_private)
            holder.textBadge.setTextColor(ctx.color(R.color.danger))
        } else {
            holder.textBadge.text = I18n.tr("Public")
            holder.textBadge.setBackgroundResource(R.drawable.bg_badge_public)
            holder.textBadge.setTextColor(ctx.color(R.color.success))
        }

        holder.itemView.setOnClickListener { onOpen(repo) }
        // Web: long-press / right-click on the row opens the same sheet as the three-dot button
        holder.itemView.setOnLongClickListener { onMore(repo); true }
        holder.btnEdit.setOnClickListener { onEdit(repo) }
        holder.btnMore.setOnClickListener { onMore(repo) }
    }

    override fun getItemCount() = items.size
}
