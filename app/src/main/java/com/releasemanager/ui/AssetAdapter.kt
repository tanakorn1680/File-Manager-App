package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.ReleaseAsset
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Job
import java.io.OutputStream

class AssetAdapter(
    private val onOpen: (ReleaseAsset) -> Unit,
    private val onDelete: (ReleaseAsset, Int) -> Unit,
    /** Gives picture rows a small preview in place of the icon; [thumbSource] says how to fetch a file's bytes. */
    private val thumbnails: Thumbnails? = null,
    private val thumbSource: ((ReleaseAsset) -> (suspend (OutputStream) -> Unit)?)? = null
) : RecyclerView.Adapter<AssetAdapter.ViewHolder>() {

    private val items = mutableListOf<ReleaseAsset>()

    /** The page's "Assets" header is folded: the files stay loaded, the rows are not shown. */
    var collapsed = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<ReleaseAsset>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            if (!collapsed) notifyItemRemoved(position)
        }
    }

    fun addItem(asset: ReleaseAsset) {
        items.add(asset)
        if (!collapsed) notifyItemInserted(items.size - 1)
    }

    fun isEmpty() = items.isEmpty()

    fun count() = items.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgAssetIcon)
        var thumbJob: Job? = null
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
        holder.thumbJob?.cancel()
        holder.thumbJob = null
        Thumbnails.showIcon(holder.imgIcon, R.drawable.ic_file)
        if (thumbnails != null && thumbSource != null && ImageTypes.canThumbnail(asset.name)) {
            val fetch = thumbSource.invoke(asset)
            if (fetch != null) {
                holder.thumbJob = thumbnails.bind("asset:${asset.id}", asset.size, fetch) {
                    Thumbnails.showThumbnail(holder.imgIcon, it)
                }
            }
        }
        holder.textName.text = asset.name
        holder.textSize.text = formatSize(asset.size)
        holder.itemView.setOnClickListener { onOpen(asset) }
        holder.btnDelete.setOnClickListener {
            onDelete(asset, holder.bindingAdapterPosition)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbJob?.cancel()
        holder.thumbJob = null
    }

    override fun getItemCount() = if (collapsed) 0 else items.size
}
