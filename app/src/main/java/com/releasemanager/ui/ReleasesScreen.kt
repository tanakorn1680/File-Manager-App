package com.releasemanager.ui

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.releasemanager.R
import com.releasemanager.data.Release
import kotlinx.coroutines.Job

/** Web: #screen-releases — the releases of the open repository. */
class ReleasesScreen(private val ctx: AppContext) {

    private val act = ctx.activity
    private val state = ctx.state
    private val root: View = act.findViewById(R.id.screenReleases)
    private val swipe: SwipeRefreshLayout = root.findViewById(R.id.swipeReleases)
    private val list: RecyclerView = root.findViewById(R.id.listReleases)
    private val stateView = StateView(root)

    private val adapter = ReleaseAdapter { openDetail(it) }

    private var loadJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        swipe.setup(list) { load(silent = true) }

        root.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { ctx.show(Screen.FILES) }
        root.findViewById<ImageButton>(R.id.btnNewRelease).setOnClickListener { ctx.releaseNew.open() }
    }

    /** Web: #btn-go-releases. */
    fun enter() {
        ctx.show(Screen.RELEASES)
        load()
    }

    /** Web: loadReleases(). */
    fun load(silent: Boolean = false) {
        val api = ctx.api ?: return
        val repo = state.currentRepo ?: return
        if (!silent) {
            swipe.isRefreshing = false
            stateView.showLoading()
            adapter.submitList(emptyList())
        }
        loadJob?.cancel()
        loadJob = ctx.launchSafe(onError = {
            swipe.isRefreshing = false
            if (silent) ctx.toast.error(it.userMessage()) else stateView.showError(it.userMessage()) { load() }
        }) {
            state.releases = api.listReleases(repo)
            render()
            swipe.isRefreshing = false
        }
    }

    private fun render() {
        adapter.submitList(state.releases)
        if (state.releases.isEmpty()) {
            stateView.showEmpty("No releases yet.\nTap + to create one.")
        } else {
            stateView.hide()
        }
    }

    /** The detail page stays its own activity (it streams large uploads); this app screen reloads when it comes back. */
    private fun openDetail(release: Release) {
        val intent = Intent(act, ReleaseDetailActivity::class.java)
            .putExtra(ReleaseDetailActivity.EXTRA_REPO, state.currentRepo)
            .putExtra(ReleaseDetailActivity.EXTRA_RELEASE_ID, release.id)
            .putExtra(ReleaseDetailActivity.EXTRA_TAG_NAME, release.tagName)
            .putExtra(ReleaseDetailActivity.EXTRA_NAME, release.name)
            .putExtra(ReleaseDetailActivity.EXTRA_BODY, release.body)
            .putExtra(ReleaseDetailActivity.EXTRA_DRAFT, release.draft)
            .putExtra(ReleaseDetailActivity.EXTRA_PRERELEASE, release.prerelease)
        act.startActivity(intent)
    }
}
