package com.releasemanager.data

/**
 * In-memory UI state shared by every screen of MainActivity.
 * Mirrors `const state = {...}` at the top of the web app's app.js, field for field,
 * so the screen controllers can be read side by side with the web code.
 */
class AppState {
    var repos: List<RepoRef> = emptyList()
    var repoSortKey: RepoSortKey = RepoSortKey.UPDATED   // same as GitHub's sort=updated
    var repoSortAsc: Boolean = false                      // false = high → low
    var currentRepo: String? = null
    var currentBranch: String = "main"
    var currentPath: String = ""
    var files: List<FileEntry> = emptyList()
    var branches: List<String> = emptyList()

    var selectMode: Boolean = false
    val selected: LinkedHashSet<String> = LinkedHashSet()   // file paths

    var editingFile: EditingFile? = null
    var editorDirty: Boolean = false
    var editorOriginal: String = ""

    /** Stage Mode queue: path → staged change. Insertion order is kept for the commit dialog. */
    val stagedFiles: LinkedHashMap<String, StagedFile> = LinkedHashMap()
    var editMode: EditMode = EditMode.QUICK

    var releases: List<Release> = emptyList()

    /** Back to the just-launched state (used on Disconnect). The sets are cleared, not replaced, because adapters hold them. */
    fun reset() {
        repos = emptyList()
        currentRepo = null
        currentBranch = "main"
        currentPath = ""
        files = emptyList()
        branches = emptyList()
        selectMode = false
        selected.clear()
        editingFile = null
        editorDirty = false
        editorOriginal = ""
        stagedFiles.clear()
        editMode = EditMode.QUICK
        releases = emptyList()
    }
}
