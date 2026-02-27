// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import crawlercommons.robots.BaseRobotRules
import edu.uci.ics.crawler4j.crawler.Page
import edu.uci.ics.crawler4j.crawler.WebCrawler
import edu.uci.ics.crawler4j.parser.HtmlParseData
import edu.uci.ics.crawler4j.url.WebURL
import net.loxal.finder.dto.SitePage
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.exception.WriteLimitReachedException
import org.apache.tika.parser.pdf.PDFParser
import org.apache.tika.sax.BodyContentHandler
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web crawler implementation for indexing site pages.
 *
 * Handles HTML pages and PDF documents, extracting content and metadata
 * for search indexing while respecting robots.txt rules.
 */
class SiteCrawler(
    private val siteService: SiteService,
    private val siteId: UUID,
    private val siteSecret: UUID,
    private val baseUrl: URI,
    private val pageBodyCssSelector: String,
    private val robotRules: BaseRobotRules,
    private val allowUrlWithQuery: Boolean
) : WebCrawler() {

    override fun shouldVisit(referringPage: Page?, webUrl: WebURL): Boolean {
        return runCatching {
            val cleanUrl = sanitizeUrl(webUrl.url ?: return false)

            if (cleanUrl.isEmpty()) {
                LOG.warn("Empty URL after sanitization - original: ${webUrl.url}")
                return false
            }

            val shouldCrawl = isValidUrl(cleanUrl)

            if (shouldCrawl) {
                when {
                    cleanUrl.lowercase(Locale.getDefault()).endsWith(".pdf") -> indexPdf(cleanUrl)
                    else -> LOG.debug("shouldVisit: $cleanUrl")
                }
            }

            shouldCrawl
        }.getOrElse { e ->
            LOG.warn("shouldVisit failed for URL: ${webUrl.url}", e)
            false
        }
    }

    override fun visit(page: Page) {
        runCatching {
            val url = page.webURL?.url ?: run {
                LOG.warn("visit_ERROR_nullUrl - siteId: $siteId")
                return
            }

            when (val parseData = page.parseData) {
                is HtmlParseData -> processHtmlPage(parseData, url)
                else -> LOG.debug("visit_SKIPPED_notHtml - siteId: $siteId - url: $url")
            }
        }.onFailure { e ->
            LOG.error("visit_ERROR_exception - siteId: $siteId - url: ${page.webURL?.url}", e)
        }
    }

    // ========== URL Validation & Sanitization ==========

    private fun sanitizeUrl(rawUrl: String): String {
        return Jsoup.parse(rawUrl).text()
            .replace(TRAILING_XML_TAG_REGEX, "")
            .trim()
    }

    private fun isValidUrl(cleanUrl: String): Boolean {
        val lowerUrl = cleanUrl.lowercase(Locale.getDefault())
        return !BLACKLIST_PATTERN.matcher(lowerUrl).matches() &&
                lowerUrl.startsWith(baseUrl.toString().lowercase(Locale.getDefault())) &&
                robotRules.isAllowed(cleanUrl) &&
                (allowUrlWithQuery || hasNoQueryParameters(cleanUrl))
    }

    private fun hasNoQueryParameters(url: String): Boolean {
        return runCatching {
            URI.create(url).query.isNullOrEmpty()
        }.getOrElse { e ->
            LOG.warn("Invalid URL format: $url", e)
            false
        }
    }

    // ========== HTML Processing ==========

    private fun processHtmlPage(parseData: HtmlParseData, url: String) {
        val html = parseData.html ?: ""
        val title = parseData.title ?: ""

        if (html.isEmpty()) {
            LOG.warn("visit_ERROR_emptyHtml - siteId: $siteId - url: $url")
            return
        }

        val sitePage = SitePage(
            title = title,
            body = extractTextContent(html),
            url = url,
            thumbnail = extractThumbnail(parseData),
            labels = extractLabels(parseData)
        )

        indexPage(sitePage)
        countPage(url)
    }

    private fun extractTextContent(html: String): String {
        return runCatching {
            val document = Jsoup.parse(html)
            val body = document.body()
            val selectedFragment = body.selectFirst(pageBodyCssSelector)
            val extractedText = selectedFragment?.text() ?: body.text()

            extractedText.ifEmpty { body.text() }
        }.getOrElse { e ->
            LOG.warn("extractTextContent failed: ${e.message}")
            ""
        }
    }

    private fun extractThumbnail(parseData: HtmlParseData): String {
        return runCatching {
            parseData.metaTags
                ?.get(SiteService.PAGE_THUMBNAIL)
                ?.takeIf { it.length < MAX_THUMBNAIL_SIZE }
                ?: ""
        }.getOrElse { e ->
            LOG.warn("extractThumbnail failed: ${e.message}")
            ""
        }
    }

    private fun extractLabels(parseData: HtmlParseData): List<String> {
        return runCatching {
            parseData.metaTags
                ?.get(SiteService.PAGE_LABELS_META_NAME)
                ?.takeIf { it.length < MAX_LABELS_SIZE }
                ?.split(LABEL_DELIMITER)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }.getOrElse { e ->
            LOG.warn("extractLabels failed: ${e.message}")
            emptyList()
        }
    }

    // ========== PDF Processing ==========

    private fun indexPdf(href: String) {
        runCatching {
            val pdfContent = parsePdfContent(href)
            val pdfPage = SitePage(
                title = pdfContent.title,
                body = pdfContent.body,
                url = href,
                thumbnail = ""
            )

            indexPage(pdfPage)
            countPage(href)
        }.onFailure { e ->
            LOG.warn("indexPdf failed for: $href", e)
        }
    }

    private fun parsePdfContent(href: String): PdfContent {
        val bodyHandler = BodyContentHandler(MAX_PDF_CONTENT_SIZE)
        val metadata = Metadata()
        val parser = PDFParser()

        val body = try {
            URI.create(href).toURL().openStream().use { stream ->
                parser.parse(stream, bodyHandler, metadata, ParseContext())
            }
            bodyHandler.toString()
        } catch (e: WriteLimitReachedException) {
            LOG.warn("PDF content truncated at $MAX_PDF_CONTENT_SIZE chars: $href")
            bodyHandler.toString()  // Returns content extracted up to the limit
        }

        return PdfContent(
            title = extractPdfTitle(metadata),
            body = body
        )
    }

    private fun extractPdfTitle(metadata: Metadata): String {
        return PDF_TITLE_FIELDS
            .firstNotNullOfOrNull { metadata.get(it)?.takeIf { title -> title.isNotBlank() } }
            ?: ""
    }

    // ========== Indexing ==========

    private fun indexPage(sitePage: SitePage) {
        val pageId = SitePage.hashPageId(siteId, sitePage.url)
        siteService.indexExistingPage(
            siteId = siteId,
            siteSecret = siteSecret,
            id = pageId,
            page = sitePage
        )
    }

    private fun countPage(url: String) {
        val counter = PAGE_COUNT.computeIfAbsent(siteId) { AtomicInteger(0) }
        val count = counter.incrementAndGet()
        LOG.info("siteId: $siteId - pageCount: $count")
        getMyController().crawlersLocalData.add(url)
    }

    // ========== Data Classes ==========

    private data class PdfContent(
        val title: String,
        val body: String
    )

    // ========== Companion Object ==========

    companion object {
        private val LOG = LoggerFactory.getLogger(SiteCrawler::class.java)

        // HTTP Client Configuration
        internal val HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(60))
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        internal val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

        // URL Filtering
        private val BLACKLIST_PATTERN = """.*\.(
            css|js|
            gif|jpg|jpeg|png|svg|ico|webp|
            mp3|mp4|avi|mov|wmv|flv|
            zip|gz|tar|rar|7z|
            xml|
            woff|woff2|ttf|eot|otf
        )$""".trimIndent().replace("\n", "").toRegex(RegexOption.IGNORE_CASE).toPattern()

        private val TRAILING_XML_TAG_REGEX = Regex("</[^>]+>$")

        // PDF Title Fields (in priority order)
        private val PDF_TITLE_FIELDS = listOf(
            "title",
            "pdf:docinfo:title",
            "dc:title"
        )

        // Metadata Constraints
        private const val MAX_THUMBNAIL_SIZE = 100_000
        private const val MAX_LABELS_SIZE = 100_000
        private const val MAX_PDF_CONTENT_SIZE = 100_000  // 100k characters
        private const val LABEL_DELIMITER = ","

        // Page Count Tracking (shared across crawler instances)
        internal val PAGE_COUNT: MutableMap<UUID, AtomicInteger> = ConcurrentHashMap()
    }
}