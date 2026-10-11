package com.releasemanager.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.releasemanager.R
import com.releasemanager.data.GitHubApiException
import com.releasemanager.data.RepoRef
import com.releasemanager.data.TokenStore
import com.releasemanager.network.GitHubApi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tokenStore: TokenStore
    private var api: GitHubApi? = null
    private var currentRepo: String? = null
    private var repos: List<RepoRef> = emptyList()

    private lateinit var scrollConnection: androidx.core.widget.NestedScrollView
    private lateinit var barConnectedStatus: View
    private lateinit var textConnectedRepo: android.widget.TextView
    private lateinit var inputToken: TextInputEditText
    private lateinit var textConnectStatus: android.widget.TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var layoutRepo: TextInputLayout
    private lateinit var dropdownRepo: AutoCompleteTextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerReleases: RecyclerView
    private lateinit var textEmptyReleases: android.widget.TextView
    private lateinit var fabNewRelease: FloatingActionButton

    private lateinit var releaseAdapter: ReleaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenStore = TokenStore(this)

        scrollConnection = findViewById(R.id.scrollConnection)
        barConnectedStatus = findViewById(R.id.barConnectedStatus)
        textConnectedRepo = findViewById(R.id.textConnectedRepo)
        inputToken = findViewById(R.id.inputToken)
        textConnectStatus = findViewById(R.id.textConnectStatus)
        btnConnect = findViewById(R.id.btnConnect)
        layoutRepo = findViewById(R.id.layoutRepo)
        dropdownRepo = findViewById(R.id.dropdownRepo)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerReleases = findViewById(R.id.recyclerReleases)
        textEmptyReleases = findViewById(R.id.textEmptyReleases)
        fabNewRelease = findViewById(R.id.fabNewRelease)

        releaseAdapter = ReleaseAdapter { release ->
            val intent = Intent(this, ReleaseDetailActivity::class.java)
            intent.putExtra(ReleaseDetailActivity.EXTRA_REPO, currentRepo)
            intent.putExtra(ReleaseDetailActivity.EXTRA_RELEASE_ID, release.id)
            intent.putExtra(ReleaseDetailActivity.EXTRA_TAG_NAME, release.tagName)
            intent.putExtra(ReleaseDetailActivity.EXTRA_NAME, release.name)
            intent.putExtra(ReleaseDetailActivity.EXTRA_BODY, release.body)
            startActivity(intent)
        }
        recyclerReleases.layoutManager = LinearLayoutManager(this)
        recyclerReleases.adapter = releaseAdapter

        btnConnect.setOnClickListener { connect() }
        swipeRefresh.setOnRefreshListener { loadReleases() }
        barConnectedStatus.setOnClickListener { showConnectionScreen() }
        fabNewRelease.setOnClickListener { showNewReleaseDialog() }

        dropdownRepo.setOnItemClickListener { _, _, position, _ ->
            val repo = repos[position]
            currentRepo = repo.fullName
            tokenStore.saveLastRepo(repo.fullName)
            loadReleases()
        }

        // Auto-fill a previously saved token so the user isn't forced to
        // retype it every launch — this is the "keep it saved" tradeoff
        // the user explicitly chose over re-entering it each time.
        tokenStore.getToken()?.let { saved ->
            inputToken.setText(saved)
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from ReleaseDetailActivity after a delete/upload —
        // refresh so the list reflects whatever changed there.
        if (currentRepo != null) loadReleases()
    }

    private fun connect() {
        val token = inputToken.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            showConnectStatus("Enter a token first", isError = true)
            return
        }

        setConnecting(true)
        val client = GitHubApi(token)
        lifecycleScope.launch {
            try {
                val user = client.verifyTokenAndGetUser()
                api = client
                tokenStore.saveToken(token)
                showConnectStatus("Connected: ${user.login}", isError = false)
                loadRepoList()
            } catch (e: GitHubApiException) {
                showConnectStatus("Connection failed: ${e.message}", isError = true)
            } catch (e: Exception) {
                showConnectStatus("Connection failed: ${e.message}", isError = true)
            } finally {
                setConnecting(false)
            }
        }
    }

    private fun loadRepoList() {
        val client = api ?: return
        lifecycleScope.launch {
            try {
                repos = client.listRepos()
                if (repos.isEmpty()) {
                    showConnectStatus("No repositories accessible with this token", isError = true)
                    return@launch
                }
                val names = repos.map { if (it.private) "${it.fullName} \uD83D\uDD12" else it.fullName }
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, names)
                dropdownRepo.setAdapter(adapter)
                layoutRepo.visibility = View.VISIBLE

                // Prefer the last-used repo if it's still in the list, else
                // just pick the first one — one less tap for the common
                // case of managing a single project repeatedly.
                val lastRepo = tokenStore.getLastRepo()
                val preselect = repos.firstOrNull { it.fullName == lastRepo } ?: repos.first()
                val preselectIndex = repos.indexOf(preselect)
                dropdownRepo.setText(names[preselectIndex], false)
                currentRepo = preselect.fullName
                tokenStore.saveLastRepo(preselect.fullName)
                loadReleases()
            } catch (e: GitHubApiException) {
                showConnectStatus("Could not load repos: ${e.message}", isError = true)
            } catch (e: Exception) {
                showConnectStatus("Could not load repos: ${e.message}", isError = true)
            }
        }
    }

    private fun loadReleases() {
        val client = api ?: return
        val repo = currentRepo ?: return
        // Switching to the releases screen here (not right after connect())
        // means a failed repo/release load leaves the connection card
        // visible with its error message, instead of hiding it behind an
        // empty releases screen the user would have no way to explain.
        showReleasesScreen(repo)
        swipeRefresh.visibility = View.VISIBLE
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val releases = client.listReleases(repo)
                releaseAdapter.submitList(releases)
                textEmptyReleases.visibility = if (releases.isEmpty()) View.VISIBLE else View.GONE
                fabNewRelease.visibility = View.VISIBLE
            } catch (e: GitHubApiException) {
                toast("Could not load releases: ${e.message}")
            } catch (e: Exception) {
                toast("Could not load releases: ${e.message}")
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showNewReleaseDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_release, null)
        val inputTag = view.findViewById<TextInputEditText>(R.id.inputTag)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputName)
        val inputBody = view.findViewById<TextInputEditText>(R.id.inputBody)
        val checkDraft = view.findViewById<android.widget.CheckBox>(R.id.checkDraft)
        val checkPrerelease = view.findViewById<android.widget.CheckBox>(R.id.checkPrerelease)

        MaterialAlertDialogBuilder(this)
            .setTitle("New release")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val tag = inputTag.text?.toString()?.trim().orEmpty()
                if (tag.isEmpty()) {
                    toast("Tag is required")
                    return@setPositiveButton
                }
                createRelease(
                    tag = tag,
                    name = inputName.text?.toString()?.trim().orEmpty(),
                    body = inputBody.text?.toString().orEmpty(),
                    draft = checkDraft.isChecked,
                    prerelease = checkPrerelease.isChecked
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRelease(tag: String, name: String, body: String, draft: Boolean, prerelease: Boolean) {
        val client = api ?: return
        val repo = currentRepo ?: return
        lifecycleScope.launch {
            try {
                client.createRelease(repo, tag, name, body, draft, prerelease)
                toast("Release created")
                loadReleases()
            } catch (e: GitHubApiException) {
                toast("Could not create release: ${e.message}")
            } catch (e: Exception) {
                toast("Could not create release: ${e.message}")
            }
        }
    }

    // Swaps the full token/repo entry card for a compact "connected to
    // X" strip plus the releases list. Without this the connection card
    // stayed on screen permanently even after a successful connect — the
    // bug that prompted this: "Connected: tanakorn1680" showing right
    // above a Connect button that never went away.
    private fun showReleasesScreen(repo: String) {
        scrollConnection.visibility = View.GONE
        barConnectedStatus.visibility = View.VISIBLE
        textConnectedRepo.text = repo
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun showConnectionScreen() {
        barConnectedStatus.visibility = View.GONE
        scrollConnection.visibility = View.VISIBLE
        swipeRefresh.visibility = View.GONE
        fabNewRelease.visibility = View.GONE
        textEmptyReleases.visibility = View.GONE
    }

    private fun setConnecting(connecting: Boolean) {
        btnConnect.isEnabled = !connecting
        btnConnect.text = if (connecting) "Connecting…" else "Connect"
    }

    private fun showConnectStatus(message: String, isError: Boolean) {
        textConnectStatus.text = message
        textConnectStatus.visibility = View.VISIBLE
        textConnectStatus.setTextColor(
            getColor(if (isError) R.color.danger else R.color.success)
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
