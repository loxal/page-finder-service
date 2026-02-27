// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import java.util.*

// TODO remove successCount as it's an implicit part of documents(.size)
//@JsonIgnoreProperties("successCount")
data class SiteIndexSummary(
    var siteId: UUID = UUID.fromString("0-0-0-0-0"), // siteId used to be null-safe
    var siteSecret: UUID = UUID.fromString("0-0-0-0-0"), // siteSecret used to be null-safe
//    val successCount: Int = -1,
    var documents: List<String> = emptyList(), // siteId used to be null-safe
    var failed: List<String> = emptyList() // siteId used to be null-safe
)
