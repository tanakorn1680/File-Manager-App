package com.releasemanager.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.releasemanager.R
import com.releasemanager.data.RepoDetails
import com.releasemanager.data.RepoRef
import com.releasemanager.util.formatSize
import com.releasemanager.util.formatTimeAgo
import java.util.Locale

/**
 * Bottom sheet with a repository's details, laid out like the sidebar of its GitHub page:
 * stars / forks / size, latest release, latest deployment, contributors and a languages bar.
 * The header comes from the repo list at once; the rest loads when the sheet opens.
 */
object RepoDetailsSheet {

    fun show(ctx: AppContext, repo: RepoRef) {
        val api = ctx.api ?: return
        val activity = ctx.activity
        val dialog = BottomSheetDialog(activity, R.style.Theme_App_BottomSheetDialog)
        val view = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_repo_details, null)
        I18n.translateTree(view)

        view.findViewById<TextView>(R.id.detailsTitle).text = repo.fullName.repoShortName()
        val badge = view.findViewById<TextView>(R.id.detailsBadge)
        if (repo.private) {
            badge.text = I18n.tr("Private")
            badge.setBackgroundResource(R.drawable.bg_badge_private)
            badge.setTextColor(activity.color(R.color.danger))
        } else {
            badge.text = I18n.tr("Public")
            badge.setBackgroundResource(R.drawable.bg_badge_public)
            badge.setTextColor(activity.color(R.color.success))
        }
        val desc = view.findViewById<TextView>(R.id.detailsDesc)
        if (!repo.description.isNullOrBlank()) {
            desc.text = repo.description
            desc.visibility = View.VISIBLE
        }
        view.findViewById<TextView>(R.id.statStars).text = repo.stars.toString()
        view.findViewById<TextView>(R.id.statForks).text = repo.forks.toString()
        view.findViewById<TextView>(R.id.statSize).text = formatSize(repo.sizeKb * 1024)
        val ago = formatTimeAgo(repo.updatedAt)
        view.findViewById<TextView>(R.id.detailsInfo).text = listOf(
            repo.defaultBranch,
            if (ago.isNotEmpty()) "${I18n.tr("Updated")} $ago" else ""
        ).filter { it.isNotEmpty() }.joinToString(" · ")

        val progress = view.findViewById<View>(R.id.detailsProgress)
        val error = view.findViewById<TextView>(R.id.detailsError)
        val body = view.findViewById<View>(R.id.detailsBody)

        val job = ctx.launchSafe(onError = {
            progress.visibility = View.GONE
            error.text = it.userMessage()
            error.visibility = View.VISIBLE
        }) {
            val details = api.getRepoDetails(repo.fullName)
            bind(view, details)
            progress.visibility = View.GONE
            body.visibility = View.VISIBLE
        }

        dialog.setContentView(view)
        dialog.setOnDismissListener { job.cancel() }
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun bind(view: View, d: RepoDetails) {
        val c = view.context

        // Release (always shown, like GitHub's "No releases published")
        val relTitle = view.findViewById<TextView>(R.id.textReleaseTitle)
        val relSub = view.findViewById<TextView>(R.id.textReleaseSub)
        val rel = d.release
        if (rel == null) {
            relTitle.text = I18n.tr("No releases published")
            relTitle.setTextColor(c.color(R.color.text_muted))
            relTitle.textSize = 13f
            relSub.visibility = View.GONE
        } else {
            relTitle.text = rel.tag
            relTitle.setTextColor(c.color(R.color.accent))
            val ago = formatTimeAgo(rel.publishedAt)
            relSub.text = listOf(
                rel.name?.takeIf { it.isNotBlank() && it != rel.tag } ?: "",
                if (rel.prerelease) "Pre-release" else "",
                ago
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            relSub.visibility = if (relSub.text.isEmpty()) View.GONE else View.VISIBLE
        }

        // Deployment (hidden when the repo has none)
        val dep = d.deployment
        val cardDeploy = view.findViewById<View>(R.id.cardDeploy)
        if (dep == null) {
            cardDeploy.visibility = View.GONE
        } else {
            val (colorRes, label) = when (dep.state) {
                "success" -> R.color.success to "Success"
                "failure", "error" -> R.color.danger to "Failed"
                "in_progress", "queued", "pending" -> R.color.warning to "In progress"
                "inactive" -> R.color.text_dim to "Inactive"
                else -> R.color.text_dim to ""
            }
            view.findViewById<View>(R.id.dotDeploy).background = ovalDrawable(c.color(colorRes))
            view.findViewById<TextView>(R.id.textDeployTitle).text = dep.environment.ifBlank { "—" }
            val sub = view.findViewById<TextView>(R.id.textDeploySub)
            sub.text = listOf(I18n.tr(label), formatTimeAgo(dep.createdAt))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
        }

        // Contributors (hidden when there are none)
        val cardContrib = view.findViewById<View>(R.id.cardContrib)
        val contribList = view.findViewById<LinearLayout>(R.id.contribList)
        contribList.removeAllViews()
        if (d.contributors.isEmpty()) {
            cardContrib.visibility = View.GONE
        } else {
            d.contributors.forEach { contribList.addView(contributorRow(c, it.login, it.contributions)) }
        }

        // Languages (hidden when GitHub detected none)
        val cardLang = view.findViewById<View>(R.id.cardLang)
        val bar = view.findViewById<LinearLayout>(R.id.langBar)
        val legend = view.findViewById<LinearLayout>(R.id.langLegend)
        bar.removeAllViews()
        legend.removeAllViews()
        val total = d.languages.sumOf { it.bytes }
        if (total <= 0L) {
            cardLang.visibility = View.GONE
        } else {
            bar.background = GradientDrawable().apply {
                cornerRadius = c.dp(4).toFloat()
                setColor(c.color(R.color.border))
            }
            bar.clipToOutline = true
            d.languages.forEachIndexed { i, lang ->
                val seg = View(c)
                seg.setBackgroundColor(languageColor(lang.name))
                seg.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, lang.bytes.toFloat()).apply {
                    if (i < d.languages.size - 1) marginEnd = c.dp(2)
                }
                bar.addView(seg)

                val pct = lang.bytes * 100.0 / total
                legend.addView(languageRow(c, lang.name, languageColor(lang.name),
                    if (pct < 0.1) "<0.1%" else String.format(Locale.US, "%.1f%%", pct)))
            }
        }
    }

    private fun contributorRow(c: Context, login: String, commits: Int): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(6), 0, c.dp(6))
        }
        val initial = TextView(c).apply {
            text = login.take(1).uppercase()
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(c.color(R.color.accent))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ovalDrawable(c.color(R.color.accent_light))
            layoutParams = LinearLayout.LayoutParams(c.dp(28), c.dp(28))
        }
        val name = TextView(c).apply {
            text = login
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(c.color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(12) }
        }
        val count = TextView(c).apply {
            text = I18n.tr(if (commits == 1) "1 commit" else "$commits commits")
            textSize = 12f
            setTextColor(c.color(R.color.text_muted))
        }
        row.addView(initial)
        row.addView(name)
        row.addView(count)
        return row
    }

    private fun languageRow(c: Context, name: String, color: Int, percent: String): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(5), 0, c.dp(5))
        }
        val dot = View(c).apply {
            background = ovalDrawable(color)
            layoutParams = LinearLayout.LayoutParams(c.dp(10), c.dp(10))
        }
        val label = TextView(c).apply {
            text = name
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(c.color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(8) }
        }
        val pct = TextView(c).apply {
            text = percent
            textSize = 13f
            setTextColor(c.color(R.color.text_muted))
        }
        row.addView(dot)
        row.addView(label)
        row.addView(pct)
        return row
    }
}
