/*
 * File: IndexResult.kt
 * Author: Auto Header
 * Date: 2026-01-11
 * Copyright (c) 2026 Auto Header
 */

// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

/**
 * Sealed class representing the result of a feed indexing operation.
 * Provides rich error information for better HTTP status code mapping.
 */
sealed class IndexResult {
    /**
     * Indexing completed successfully.
     */
    data class Success(val summary: SiteIndexSummary) : IndexResult()

    /**
     * Authentication failed - invalid site credentials.
     */
    data object Unauthorized : IndexResult()

    /**
     * The provided URL is malformed or uses an invalid scheme.
     */
    data class InvalidUrl(val reason: String) : IndexResult()

    /**
     * Failed to fetch the feed from the remote URL.
     */
    data class FeedFetchFailed(val url: String, val reason: String) : IndexResult()

    /**
     * The feed content is too large to process.
     */
    data class FeedTooLarge(val sizeBytes: Long, val maxBytes: Long) : IndexResult()

    /**
     * The feed content could not be parsed as valid XML.
     */
    data class InvalidFeed(val reason: String) : IndexResult()

    /**
     * The feed was fetched but contained no indexable content.
     */
    data object EmptyFeed : IndexResult()
}