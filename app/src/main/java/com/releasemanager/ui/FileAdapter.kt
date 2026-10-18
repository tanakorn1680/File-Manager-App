package com.releasemanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.FileEntry
import com.releasemanager.util.formatSize
import kotlinx.coroutines.Job
import java.io.OutputStream

/**
 * Web: renderFiles() — one `.file-item` per entry. [selected] and [staged] are the live sets
 * from AppState, so a row always reflects the current selection / staged queue when it binds.
 */
class FileAdapter(
    private val selected: Set<String>,
    private val staged: Set<String>,
    private val onClick: (FileEntry) -> Unit,
    private val onLongClick: (FileEntry) -> Unit,
    /** Gives picture rows a small preview in place of the icon; [thumbSource] says how to fetch a file's bytes. */
    private val thumbnails: Thumbnails? = null,
    private val thumbSource: ((FileEntry) -> (suspend (OutputStream) -> Unit)?)? = null
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val items = mutableListOf<FileEntry>()

    fun submitList(newItems: List<FileEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Re-binds every row after the selection or staged set changed. */
    fun refresh() = notifyDataSetChanged()

    fun refreshPath(path: String) {
        val index = items.indexOfFirst { it.path == path }
        if (index >= 0) notifyItemChanged(index)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgFileIcon)
        val textName: TextView = view.findViewById(R.id.textFileName)
        val stageDot: View = view.findViewById(R.id.viewStageDot)
        val textSize: TextView = view.findViewById(R.id.textFileSize)
        val checkCircle: View = view.findViewById(R.id.checkCircle)
        val imgCheck: ImageView = view.findViewById(R.id.imgCheck)
        var thumbJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = items[position]
        val isSelected = file.path in selected

        holder.thumbJob?.cancel()
        holder.thumbJob = null
        Thumbnails.showIcon(holder.imgIcon, if (file.isDir) R.drawable.ic_folder else R.drawable.ic_file)
        if (thumbnails != null && thumbSource != null && !file.isDir && !file.sha.isNullOrEmpty() &&
            ImageTypes.canThumbnail(file.name)
        ) {
            val fetch = thumbSource.invoke(file)
            if (fetch != null) {
                holder.thumbJob = thumbnails.bind("blob:${file.sha}", file.size, fetch) {
                    Thumbnails.showThumbnail(holder.imgIcon, it)
                }
            }
        }
        holder.textName.text = file.name
        val isStaged = file.path in staged
        holder.stageDot.visibility = if (isStaged) View.VISIBLE else View.GONE
        // Web: `.file-item.file-staged .file-name { color: #b36900 }`
        holder.textName.setTextColor(
            holder.itemView.context.color(if (isStaged) R.color.warning_text else R.color.text_primary)
        )

        // Web: `!isDir && f.size` — staged-new files have no size yet
        if (!file.isDir && file.size > 0) {
            holder.textSize.text = formatSize(file.size)
            holder.textSize.visibility = View.VISIBLE
        } else {
            holder.textSize.visibility = View.GONE
        }

        holder.itemView.isActivated = isSelected
        holder.checkCircle.isActivated = isSelected
        holder.imgCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(file) }
        holder.itemView.setOnLongClickListener { onLongClick(file); true }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbJob?.cancel()
        holder.thumbJob = null
    }

    override fun getItemCount() = items.size
}
