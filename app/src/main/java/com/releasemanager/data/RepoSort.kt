package com.releasemanager.data

enum class RepoSortKey(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    UPDATED("Last updated"),
    TYPE("Type (Public / Private)")
}

/** [ascending] = low → high (A→Z, small→large, old→new, Public→Private). */
fun List<RepoRef>.sortedForDisplay(key: RepoSortKey, ascending: Boolean): List<RepoRef> {
    val byName = compareBy<RepoRef, String>(String.CASE_INSENSITIVE_ORDER) { it.fullName.substringAfter('/') }
    val cmp: Comparator<RepoRef> = when (key) {
        RepoSortKey.NAME -> byName
        RepoSortKey.SIZE -> compareBy<RepoRef> { it.sizeKb }.then(byName)
        RepoSortKey.UPDATED -> compareBy<RepoRef> { it.updatedAt }.then(byName)
        RepoSortKey.TYPE -> compareBy<RepoRef> { it.private }.then(byName)
    }
    return sortedWith(if (ascending) cmp else cmp.reversed())
}
