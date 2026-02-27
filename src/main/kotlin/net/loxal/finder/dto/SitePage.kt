// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class SitePage(
        var title: String = "",
        var body: String = "",
        var url: String = "",
        var thumbnail: String = "",
        var labels: List<String> = emptyList()
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val sitePage = other as SitePage?
        return title == sitePage!!.title &&
                body == sitePage.body &&
                url == sitePage.url &&
                labels == sitePage.labels
    }

    override fun hashCode(): Int {
        return Objects.hash(title, body, url, labels)
    }

    companion object {
        // ThreadLocal for MessageDigest (not thread-safe)
        private val digestorThreadLocal = ThreadLocal.withInitial {
            java.security.MessageDigest.getInstance("SHA-256")
        }

        // Cached HexFormat instance (thread-safe, immutable)
        private val hexFormat = java.util.HexFormat.of()

        @JvmStatic
        fun hashPageId(siteId: UUID, siteUrl: String): String {
            val digestor = digestorThreadLocal.get()
            val pageIdBytes = "$siteId$siteUrl".toByteArray()
            val digest = digestor.digest(pageIdBytes)
            return hexFormat.formatHex(digest)
        }
    }
}
