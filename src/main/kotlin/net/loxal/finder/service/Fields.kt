// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

/**
 * Field name constants for Elasticsearch documents.
 *
 * Using object instead of interface with companion for:
 * - Direct static field access (no INSTANCE indirection)
 * - Cleaner bytecode with const vals
 */
object Fields {
    const val BODY = "body"
    const val TITLE = "title"
    const val URL = "url"
    const val TENANT = "siteId"
}