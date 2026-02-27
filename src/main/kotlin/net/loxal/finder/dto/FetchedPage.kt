// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import java.util.*

data class FetchedPage(
        var siteId: UUID? = null,
        var id: String = "",
        var title: String = "",
        var body: String = "",
        var url: String = "",
        var updated: String = "",
        var labels: List<String> = emptyList(),
        var thumbnail: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as FetchedPage?
        return title == that!!.title &&
                body == that.body &&
                url == that.url &&
                labels == that.labels
    }

    override fun hashCode(): Int {
        return Objects.hash(title, body, url, labels)
    }
}
