package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.Release

class ReleaseAdapter(
    private val onClick: (Release) -> Unit
) : RecyclerView.Adapter<ReleaseAdapter.ViewHolder>() {

    private val items = mutableListOf<Release>()

    fun submitList(newItems: List<Release>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textTag: TextView = view.findViewById(R.id.textTag)
        val textName: TextView = view.findViewById(R.id.textName)
        val textDraft: TextView = view.findViewById(R.id.textDraft)
        val textPrerelease: TextView = view.findViewById(R.id.textPrerelease)
        val textAssetCount: TextView = view.findViewById(R.id.textAssetCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_release, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val release = items[position]
        holder.textTag.text = release.tagName
        holder.textName.text = release.name?.ifBlank { release.tagName } ?: release.tagName
        holder.textDraft.visibility = if (release.draft) View.VISIBLE else View.GONE
        holder.textPrerelease.visibility = if (release.prerelease) View.VISIBLE else View.GONE
        val count = release.assets.size
        holder.textAssetCount.text = I18n.tr(if (count == 1) "1 asset" else "$count assets")
        holder.itemView.setOnClickListener { onClick(release) }
    }

    override fun getItemCount() = items.size
}
