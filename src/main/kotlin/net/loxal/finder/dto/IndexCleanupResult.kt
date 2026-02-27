// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

/**
 * Result of an index cleanup operation.
 *
 * @property deleted Number of pages successfully deleted from the index
 * @property failed Number of pages that failed to delete
 * @property urls List of URLs that were targeted for deletion
 */
data class IndexCleanupResult(
    var deleted: Int = 0,
    var failed: Int = 0,
    var urls: List<String> = emptyList()
) {
    /** Total number of pages that were targeted for deletion */
    val total: Int
        get() = deleted + failed

    /** Whether all targeted pages were successfully deleted */
    val allDeleted: Boolean
        get() = failed == 0 && deleted > 0

    // Backwards compatibility: pageCount maps to deleted
    @Deprecated("Use 'deleted' instead", ReplaceWith("deleted"))
    val pageCount: Int
        get() = deleted
}