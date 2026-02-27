// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

/**
 * Result of a bulk delete operation against the index.
 *
 * @property deleted Number of documents successfully deleted
 * @property failed Number of documents that failed to delete
 * @property total Total number of documents that were requested for deletion
 * @property success Whether the operation completed without errors
 * @property errorMessage Optional error message if the operation failed
 */
data class DeletePagesResult(
    val deleted: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val success: Boolean = true,
    val errorMessage: String? = null
) {
    val allDeleted: Boolean
        get() = success && deleted == total && failed == 0
}