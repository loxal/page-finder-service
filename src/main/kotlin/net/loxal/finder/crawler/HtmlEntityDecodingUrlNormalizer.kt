// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.crawler

import crawlercommons.filters.basic.BasicURLNormalizer
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.MalformedURLException

/**
 * Custom URL normalizer that extends BasicURLNormalizer to handle HTML-encoded entities
 * and malformed URLs that appear in web pages.
 *
 * This normalizer performs the following operations:
 * 1. Decodes HTML entities (e.g., &amp; -> &, &lt; -> <, &gt; -> >)
 * 2. Removes trailing XML tags (e.g., </link>, </guid>)
 * 3. Applies standard URL normalization via BasicURLNormalizer
 *
 * Example transformations:
 * - "https://example.com/?foo=1&amp;bar=2</link>" -> "https://example.com/?foo=1&bar=2"
 * - "https://example.com/?a=b&amp;c=d</guid>" -> "https://example.com/?a=b&c=d"
 */
class HtmlEntityDecodingUrlNormalizer : BasicURLNormalizer() {

    override fun filter(url: String?): String? {
        if (url.isNullOrBlank()) {
            return null
        }

        return try {
            // Step 1: Decode HTML entities and clean up XML tags
            val cleanedUrl = sanitizeUrl(url)

            if (cleanedUrl.isBlank()) {
                LOG.debug("URL became empty after sanitization: $url")
                return null
            }

            // Step 2: Apply standard URL normalization
            super.filter(cleanedUrl)
        } catch (e: MalformedURLException) {
            LOG.warn("Malformed URL after normalization: $url", e)
            null
        } catch (e: Exception) {
            LOG.warn("Failed to normalize URL: $url - ${e.message}")
            null
        }
    }

    /**
     * Sanitizes a URL by:
     * 1. Decoding HTML entities using Jsoup (handles &amp;, &lt;, &gt;, &quot;, etc.)
     * 2. Removing trailing XML/HTML tags (</link>, </guid>, etc.)
     * 3. Trimming whitespace
     */
    private fun sanitizeUrl(url: String): String {
        return Jsoup.parse(url).text()
            .replace(TRAILING_XML_TAG_REGEX, "")
            .trim()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(HtmlEntityDecodingUrlNormalizer::class.java)

        /**
         * Regex pattern to match trailing XML/HTML tags like </link>, </guid>, etc.
         * This pattern matches:
         * - "</" followed by one or more non-">" characters, followed by ">"
         * - At the end of the string ($)
         */
        private val TRAILING_XML_TAG_REGEX = Regex("</[^>]+>$")
    }
}