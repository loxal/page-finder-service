// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import java.util.*

data class FoundPage(
        var title: String = "",
        var body: String = "",
        var url: String = "",
        var urlRaw: String = "",
        var labels: List<String> = emptyList(),
        var thumbnail: String = ""
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val site = other as FoundPage?
        return title == site?.title &&
                body == site.body &&
                url == site.url
    }

    override fun hashCode(): Int {
        return Objects.hash(title, body, url)
    }
}
