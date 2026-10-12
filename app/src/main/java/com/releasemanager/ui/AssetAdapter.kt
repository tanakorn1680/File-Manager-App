package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.util.formatSize

class AssetAdapter(
    private val onOpen: (ReleaseAsset) -> Unit,
    private val onDelete: (ReleaseAsset, Int) -> Unit
) : RecyclerView.Adapter<AssetAdapter.ViewHolder>() {

    private val items = mutableListOf<ReleaseAsset>()

    fun submitList(newItems: List<ReleaseAsset>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun addItem(asset: ReleaseAsset) {
        items.add(asset)
        notifyItemInserted(items.size - 1)
    }

    fun isEmpty() = items.isEmpty()

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textAssetName)
        val textSize: TextView = view.findViewById(R.id.textAssetSize)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAsset)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val asset = items[position]
        holder.textName.text = asset.name
        holder.textSize.text = formatSize(asset.size)
        holder.textName.setOnClickListener { onOpen(asset) }
        holder.btnDelete.setOnClickListener {
            onDelete(asset, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = items.size
}
