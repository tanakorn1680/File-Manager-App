package com.releasemanager.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.releasemanager.R
import com.releasemanager.data.WorkflowRun
import com.releasemanager.util.formatTimeAgo

/** One row per workflow run (GitHub's own list: status icon, commit title, workflow · branch · event). */
class RunAdapter(
    private val onClick: (WorkflowRun) -> Unit
) : RecyclerView.Adapter<RunAdapter.ViewHolder>() {

    private val items = mutableListOf<WorkflowRun>()

    fun submitList(newItems: List<WorkflowRun>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgStatus: ImageView = view.findViewById(R.id.imgRunStatus)
        val textTitle: TextView = view.findViewById(R.id.textRunTitle)
        val textMeta: TextView = view.findViewById(R.id.textRunMeta)
        val textInfo: TextView = view.findViewById(R.id.textRunInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_run, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val run = items[position]
        val ctx = holder.itemView.context

        val (iconRes, colorRes) = statusIcon(run)
        holder.imgStatus.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(holder.imgStatus, ColorStateList.valueOf(ctx.color(colorRes)))

        holder.textTitle.text = run.title.ifBlank { run.workflowName }
        holder.textMeta.text = listOf("${run.workflowName} #${run.runNumber}", run.branch, run.event)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        holder.textInfo.text = listOf(statusLabel(run), formatTimeAgo(run.createdAt), run.actor)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        holder.itemView.setOnClickListener { onClick(run) }
    }

    override fun getItemCount() = items.size

    private companion object {
        /** (icon, tint colour) — running = amber, success = green, failed = red, anything else = grey. */
        fun statusIcon(run: WorkflowRun): Pair<Int, Int> = when {
            run.status != "completed" -> R.drawable.ic_status_running to R.color.warning
            run.conclusion == "success" -> R.drawable.ic_status_success to R.color.success
            run.conclusion == "failure" || run.conclusion == "timed_out" || run.conclusion == "startup_failure" ->
                R.drawable.ic_status_failure to R.color.danger
            else -> R.drawable.ic_status_neutral to R.color.text_dim
        }

        fun statusLabel(run: WorkflowRun): String = when {
            run.status == "queued" || run.status == "waiting" || run.status == "pending" || run.status == "requested" -> "Queued"
            run.status != "completed" -> "In progress"
            run.conclusion == "success" -> "Success"
            run.conclusion == "failure" -> "Failed"
            run.conclusion == "timed_out" -> "Timed out"
            run.conclusion == "startup_failure" -> "Failed to start"
            run.conclusion == "cancelled" -> "Cancelled"
            run.conclusion == "skipped" -> "Skipped"
            run.conclusion == "action_required" -> "Action required"
            else -> run.conclusion.orEmpty().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
