// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class CrawlStatus(
    var siteId: UUID? = null,
    var crawled: String = Instant.now().toString(),
    var pageCount: Long = 0,
    var siteProfile: SiteProfile? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as CrawlStatus?
        return siteId == that!!.siteId
    }

    override fun hashCode(): Int {
        return Objects.hash(siteId)
    }
}
