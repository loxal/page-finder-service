// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import net.loxal.finder.BaseConfig
import net.loxal.finder.dto.*
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

@Service
class SiteService(
    private val searchService: SimpleSearchClient,
    private val indexService: SimpleIndexClient
) {

    /**
     * Validates a URL for safe external fetching.
     * Returns an IndexResult error if validation fails, null if valid.
     */
    fun validateFeedUrl(urlString: String): IndexResult? {
        val uri = try {
            URI.create(urlString)
        } catch (e: IllegalArgumentException) {
            return IndexResult.InvalidUrl("Malformed URL: ${e.message}")
        }

        // Validate scheme
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return IndexResult.InvalidUrl("Invalid URL scheme: $scheme. Only HTTP(S) is allowed.")
        }

        // Validate host presence
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return IndexResult.InvalidUrl("URL must contain a valid host")
        }

        return null // Valid
    }

    /**
     * Indexes a feed with full validation and rich error reporting.
     */
    fun indexFeedWithValidation(
        feedUrl: String,
        siteId: UUID?,
        siteSecret: UUID?,
        stripHtmlTags: Boolean,
        isGeneric: Boolean,
        clearIndex: Boolean
    ): IndexResult {
        // Validate URL first
        validateFeedUrl(feedUrl)?.let { return it }

        val uri = URI.create(feedUrl)

        return when {
            // Both credentials provided
            siteId != null && siteSecret != null -> {
                val fetchedSecret = fetchSiteSecret(siteId).orElse(null)
                    ?: return IndexResult.Unauthorized
                if (siteSecret != fetchedSecret) return IndexResult.Unauthorized

                if (clearIndex) {
                    indexService.deleteAllSitePages(siteId)
                }

                if (isGeneric) {
                    updateIndexGenericallyWithValidation(uri, siteId)
                } else {
                    updateIndexWithValidation(uri, siteId, siteSecret, stripHtmlTags)
                }
            }
            // Only one credential provided (invalid)
            (siteId == null) xor (siteSecret == null) -> IndexResult.Unauthorized
            // No credentials - create new site
            else -> {
                val newSiteId = UUID.randomUUID()
                val newSiteSecret = UUID.randomUUID()
                initSite(newSiteId, newSiteSecret)

                if (isGeneric) {
                    IndexResult.InvalidFeed("Generic XML import requires existing site credentials")
                } else {
                    updateIndexWithValidation(uri, newSiteId, newSiteSecret, stripHtmlTags)
                }
            }
        }
    }

    private fun updateIndexGenericallyWithValidation(feedUrl: URI, siteId: UUID): IndexResult {
        LOG.info("Starting generic XML feed indexing from: {}", feedUrl)

        val successfullyIndexed = AtomicInteger(0)
        val documents = mutableListOf<String>()
        val failedToIndex = mutableListOf<String>()

        return try {
            val fetchResult = fetchFeedWithValidation(feedUrl.toString())
            if (fetchResult is FetchResult.Error) {
                return when {
                    fetchResult.isTooLarge -> IndexResult.FeedTooLarge(fetchResult.sizeBytes ?: 0, MAX_FEED_SIZE_BYTES)
                    else -> IndexResult.FeedFetchFailed(feedUrl.toString(), fetchResult.reason)
                }
            }

            val content = (fetchResult as FetchResult.Success).content
            if (content.isBlank()) {
                LOG.warn("Feed content is empty for: {}", feedUrl)
                return IndexResult.EmptyFeed
            }

            val dBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val feed = dBuilder.parse(content.byteInputStream())

            if (feed.hasChildNodes()) {
                val result = readXml(feed.childNodes, successfullyIndexed, documents, failedToIndex, siteId)
                LOG.info("Completed generic XML indexing: {} documents indexed, {} failed", documents.size, failedToIndex.size)
                IndexResult.Success(result)
            } else {
                IndexResult.EmptyFeed
            }
        } catch (e: Exception) {
            LOG.error("Failed to process generic XML feed from {}: {}", feedUrl, e.message, e)
            IndexResult.InvalidFeed("Failed to parse XML: ${e.message}")
        }
    }

    private fun updateIndexWithValidation(
        feedUrl: URI,
        siteId: UUID,
        siteSecret: UUID,
        stripHtmlTags: Boolean
    ): IndexResult {
        LOG.info("Starting RSS feed indexing from: {}", feedUrl)

        return try {
            val fetchResult = fetchFeedWithValidation(feedUrl.toString())
            if (fetchResult is FetchResult.Error) {
                return when {
                    fetchResult.isTooLarge -> IndexResult.FeedTooLarge(fetchResult.sizeBytes ?: 0, MAX_FEED_SIZE_BYTES)
                    else -> IndexResult.FeedFetchFailed(feedUrl.toString(), fetchResult.reason)
                }
            }

            val content = (fetchResult as FetchResult.Success).content
            if (content.isBlank()) {
                return IndexResult.EmptyFeed
            }

            val syndFeed = SyndFeedInput().build(XmlReader(content.byteInputStream()))
            val successfullyIndexed = AtomicInteger(0)
            val documents = mutableListOf<String>()
            val failedToIndex = mutableListOf<String>()

            syndFeed.entries.forEach { entry ->
                try {
                    val title = entry.title ?: ""
                    var body = entry.description?.value ?: entry.contents.firstOrNull()?.value ?: ""
                    if (stripHtmlTags) {
                        body = body.replace(Regex("<[^>]*>"), " ").trim()
                    }
                    val url = entry.link ?: entry.uri ?: ""

                    if (body.isNotBlank() && url.isNotBlank()) {
                        val toIndex = SitePage(title = title, body = body, url = url)
                        val pageId = SitePage.hashPageId(siteId, url)
                        indexDocument(pageId, siteId, toIndex)
                        successfullyIndexed.incrementAndGet()
                        documents.add(pageId)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to index RSS entry: {}", e.message)
                    failedToIndex.add(entry.link ?: "unknown")
                }
            }

            LOG.info("Completed RSS indexing: {} documents indexed, {} failed", documents.size, failedToIndex.size)
            IndexResult.Success(SiteIndexSummary(siteId, siteSecret, documents, failedToIndex))
        } catch (e: Exception) {
            LOG.error("Failed to process RSS feed from {}: {}", feedUrl, e.message, e)
            IndexResult.InvalidFeed("Failed to parse RSS feed: ${e.message}")
        }
    }

    /**
     * Sealed class for feed fetch results with size validation.
     */
    private sealed class FetchResult {
        data class Success(val content: String) : FetchResult()
        data class Error(val reason: String, val isTooLarge: Boolean = false, val sizeBytes: Long? = null) : FetchResult()
    }

    /**
     * Fetches a feed with content size validation.
     */
    private fun fetchFeedWithValidation(feedUrl: String): FetchResult {
        return try {
            val request = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "PageFinderBot/1.0")
                .get()
                .build()

            SiteCrawler.HTTP_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FetchResult.Error("HTTP ${response.code}: ${response.message}")
                }

                // Check Content-Length header first (if available)
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                if (contentLength != null && contentLength > MAX_FEED_SIZE_BYTES) {
                    LOG.warn("Feed too large (Content-Length): {} bytes from {}", contentLength, feedUrl)
                    return FetchResult.Error("Feed exceeds maximum size", isTooLarge = true, sizeBytes = contentLength)
                }

                val body = response.body

                // For streaming responses without Content-Length, read with limit
                val source = body.source()
                source.request(MAX_FEED_SIZE_BYTES + 1)

                val bufferedSize = source.buffer.size
                if (bufferedSize > MAX_FEED_SIZE_BYTES) {
                    LOG.warn("Feed too large (streamed): {} bytes from {}", bufferedSize, feedUrl)
                    return FetchResult.Error("Feed exceeds maximum size", isTooLarge = true, sizeBytes = bufferedSize)
                }

                val content = body.string()
                if (LOG.isDebugEnabled) {
                    LOG.debug("Successfully fetched feed from {} - size: {} bytes", feedUrl, content.length)
                }
                FetchResult.Success(content)
            }
        } catch (e: Exception) {
            LOG.error("Exception while fetching feed from {}: {}", feedUrl, e.message, e)
            FetchResult.Error("Failed to fetch: ${e.message}")
        }
    }


    fun flush(serviceSecret: UUID, indexType: String): Int {
        if (ADMIN_SITE_SECRET != serviceSecret) {
            return HttpStatus.FORBIDDEN.value()
        }

        val refresh = Request.Builder()
            .header(HttpHeaders.AUTHORIZATION, SimpleIndexClient.BASIC_AUTH_HEADER)
            .url("${SimpleIndexClient.ELASTICSEARCH_SERVICE}/$indexType/_refresh")
            .get()
            .build()

        return SiteCrawler.HTTP_CLIENT.newCall(refresh).execute().use { it.code }
    }

    fun indexExistingPage(id: String, siteId: UUID?, siteSecret: UUID?, page: SitePage): Optional<FetchedPage> {
        return when {
            // Both credentials provided
            siteId != null && siteSecret != null -> {
                fetchSiteSecret(siteId)
                    .filter { it == siteSecret }
                    .flatMap { indexDocument(id, siteId, page) }
            }
            // Only one credential provided (invalid)
            (siteId == null) xor (siteSecret == null) -> Optional.empty()
            // No credentials - create new site with ownership
            else -> {
                val newSiteId = UUID.randomUUID()
                indexDocument(SitePage.hashPageId(newSiteId, page.url), newSiteId, page)
            }
        }
    }

    fun isAllowedToModify(siteId: UUID, siteSecret: UUID): Boolean =
        fetchSiteSecret(siteId)
            .map { it == siteSecret }
            .orElse(false)

    private fun indexDocument(id: String, siteId: UUID, page: SitePage): Optional<FetchedPage> {
        val body = page.body

        val doc = Page(_id = id).apply {
            setItem(Fields.BODY, body)
            setItem(Fields.TITLE, page.title)
            setItem(Fields.URL, page.url)
            setItem(Fields.TENANT, siteId.toString())
            setItem(PAGE_UPDATED, Instant.now().toString())
            setLabels(PAGE_LABELS, page.labels)
            setItem(PAGE_THUMBNAIL, page.thumbnail)
        }

        indexService.index(SimpleIndexClient.SITE_PAGE, doc)

        if (LOG.isInfoEnabled) {
            LOG.info(
                "Indexed page - siteId: {}, bodySize: {}, titleSize: {}, url: {}",
                siteId, body.length, page.title.length, page.url
            )
        }

        return fetchById(id)
    }

    fun fetchSiteProfile(siteId: UUID, siteSecret: UUID): Optional<SiteProfile> =
        when {
            ADMIN_SITE_SECRET == siteSecret -> fetchSiteProfile(siteId)
            else -> fetchSiteSecret(siteId)
                .filter { it == siteSecret }
                .flatMap { fetchSiteProfile(siteId) }
        }

    fun fetchSiteProfile(siteId: UUID): Optional<SiteProfile> =
        Optional.ofNullable(indexService.fetchSiteProfile(siteId))

    fun fetchSiteSecret(siteId: UUID): Optional<UUID> =
        Optional.ofNullable(indexService.fetchSiteProfile(siteId)?.secret)

    fun fetchAllDocuments(siteId: UUID): Optional<List<String>> {
        val documentWithSiteSecret = searchService.fetchAllSitePages(siteId)
        val hits = documentWithSiteSecret.hits

        return if (hits.isEmpty()) {
            Optional.empty()
        } else {
            Optional.of(hits.map { it._id })
        }
    }

    private fun initSite(siteId: UUID, siteSecret: UUID) {
        val siteConfiguration = SiteProfile(id = siteId, secret = siteSecret)
        indexService.indexSiteProfile(siteConfiguration)
    }

    private fun storeSite(siteId: UUID, siteSecret: UUID, email: String, configs: List<SiteProfile.Config>) {
        val siteProfile = SiteProfile(id = siteId, secret = siteSecret, email = email, configs = configs)
        indexService.indexSiteProfile(siteProfile)
    }

    fun updateCrawlStatusInSchedule(siteId: UUID, pageCount: Long): Optional<SitesCrawlStatus> =
        fetchSitesCrawlStatus().map { fetchedStatus ->
            fetchedStatus.sites.forEach { crawlStatus ->
                if (siteId == crawlStatus.siteId) {
                    crawlStatus.crawled = Instant.now().toString()
                    crawlStatus.pageCount = pageCount
                }
            }
            val updateSitesCrawlStatus = SitesCrawlStatus(fetchedStatus.sites)
            indexService.indexCrawlStatus(updateSitesCrawlStatus)
            updateSitesCrawlStatus
        }

    fun fetchById(id: String): Optional<FetchedPage> {
        val foundPage = indexService.fetch(SimpleIndexClient.SITE_PAGE, id).firstOrNull()
            ?: return Optional.empty()

        return Optional.of(
            FetchedPage(
                siteId = UUID.fromString(foundPage[Fields.TENANT]),
                id = foundPage._id,
                title = foundPage[Fields.TITLE],
                body = foundPage[Fields.BODY],
                url = foundPage[Fields.URL],
                updated = foundPage[PAGE_UPDATED],
                labels = foundPage.getLabels(PAGE_LABELS),
                thumbnail = foundPage[PAGE_THUMBNAIL]
            )
        )
    }

    fun indexFeed(
        feedUrl: URI,
        siteId: UUID?,
        siteSecret: UUID?,
        stripHtmlTags: Boolean,
        isGeneric: Boolean,
        clearIndex: Boolean
    ): SiteIndexSummary? {
        return when {
            // Both credentials provided
            siteId != null && siteSecret != null -> {
                val fetchedSecret = fetchSiteSecret(siteId).orElse(null) ?: return null
                if (siteSecret != fetchedSecret) return null

                if (clearIndex) {
                    indexService.deleteAllSitePages(siteId)
                }

                if (isGeneric) {
                    updateIndexGenerically(feedUrl, siteId)
                } else {
                    updateIndex(feedUrl, siteId, siteSecret, stripHtmlTags)
                }
            }
            // Only one credential provided (invalid)
            (siteId == null) xor (siteSecret == null) -> null
            // No credentials - create new site
            else -> {
                val newSiteId = UUID.randomUUID()
                val newSiteSecret = UUID.randomUUID()
                initSite(newSiteId, newSiteSecret)

                if (isGeneric) {
                    null
                } else {
                    updateIndex(feedUrl, newSiteId, newSiteSecret, stripHtmlTags)
                }
            }
        }
    }

    fun createSite(): SiteCreation {
        val siteId = UUID.randomUUID()
        val siteSecret = UUID.randomUUID()
        initSite(siteId, siteSecret)
        return SiteCreation(siteId, siteSecret)
    }

    fun createSite(email: String, configs: List<SiteProfile.Config>): SiteCreation {
        val siteId = UUID.randomUUID()
        val siteSecret = UUID.randomUUID()
        storeSite(siteId, siteSecret, email, configs)
        return SiteCreation(siteId, siteSecret)
    }

    private fun readXml(
        nodeList: NodeList,
        successfullyIndexed: AtomicInteger,
        documents: MutableList<String>,
        failedToIndex: MutableList<String>,
        siteId: UUID
    ): SiteIndexSummary {
        var title = ""
        var body = ""
        var url = ""

        for (count in 0 until nodeList.length) {
            val tempNode = nodeList.item(count)

            if (tempNode.nodeType == Node.ELEMENT_NODE) {
                when (tempNode.nodeName) {
                    "title" -> title = tempNode.textContent
                    "body" -> body = tempNode.textContent
                    "url" -> url = tempNode.textContent
                }

                if (tempNode.hasChildNodes()) {
                    if (body.isNotBlank()) {
                        val toIndex = SitePage(title = title, body = body, url = url)
                        val pageId = SitePage.hashPageId(siteId, url)

                        try {
                            indexDocument(pageId, siteId, toIndex)
                                .ifPresentOrElse(
                                    {
                                        successfullyIndexed.incrementAndGet()
                                        documents.add(pageId)
                                    },
                                    {
                                        failedToIndex.add(url)
                                        LOG.warn("Failed to index document: {}", url)
                                    }
                                )
                        } catch (e: Exception) {
                            failedToIndex.add(url)
                            LOG.error("Exception while indexing document {}: {}", url, e.message, e)
                        }
                    }
                    readXml(tempNode.childNodes, successfullyIndexed, documents, failedToIndex, siteId)
                }

                if ("Document" == tempNode.nodeName) {
                    title = ""
                    body = ""
                    url = ""
                }
            }
        }

        return SiteIndexSummary(
            siteId = siteId,
            siteSecret = PLACEHOLDER_UUID,
            documents = documents,
            failed = failedToIndex
        )
    }

    private fun updateIndexGenerically(feedUrl: URI, siteId: UUID): SiteIndexSummary? {
        LOG.info("Starting generic XML feed indexing from: {}", feedUrl)

        val successfullyIndexed = AtomicInteger(0)
        val documents = mutableListOf<String>()
        val failedToIndex = mutableListOf<String>()

        return try {
            val dBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val fetchedFeed = fetchFeed(feedUrl.toString())

            if (fetchedFeed.isBlank()) {
                LOG.warn("Feed content is empty for: {}", feedUrl)
                return null
            }

            val feed = dBuilder.parse(fetchedFeed.byteInputStream())

            if (feed.hasChildNodes()) {
                val result = readXml(feed.childNodes, successfullyIndexed, documents, failedToIndex, siteId)
                LOG.info("Completed generic XML indexing: {} documents indexed, {} failed", documents.size, failedToIndex.size)
                result
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.error("Failed to process generic XML feed from {}: {}", feedUrl, e.message, e)
            null
        }
    }

    private fun fetchFeed(feedUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(feedUrl)
                .get()
                .build()

            SiteCrawler.HTTP_CLIENT.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val content = response.body.string()
                        if (LOG.isDebugEnabled) {
                            LOG.debug("Successfully fetched feed from {} - size: {} bytes", feedUrl, content.length)
                        }
                        content
                    }
                    else -> {
                        LOG.error("Failed to fetch feed: {} | {} | {}", response.code, feedUrl, response.body.string())
                        ""
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Exception while fetching feed from {}: {}", feedUrl, e.message, e)
            ""
        }
    }

    private fun updateIndex(
        feedUrl: URI,
        siteId: UUID,
        siteSecret: UUID,
        stripHtmlTags: Boolean
    ): SiteIndexSummary? {
        LOG.info("Starting RSS feed indexing from: {}", feedUrl)

        val successfullyIndexed = AtomicInteger(0)
        val documents = mutableListOf<String>()
        val failedToIndex = mutableListOf<String>()

        return try {
            val fetchedFeed = fetchFeed(feedUrl.toString())
            if (fetchedFeed.isBlank()) {
                LOG.warn("Feed content is empty for: {}", feedUrl)
                return null
            }

            val feed = SyndFeedInput().build(XmlReader(fetchedFeed.byteInputStream()))

            feed.entries.forEach { entry ->
                val body = entry.description?.value?.let { value ->
                    if (stripHtmlTags) {
                        value.replace(HTML_TAG_REGEX, "")
                    } else {
                        value
                    }
                } ?: ""

                val url = entry.link ?: return@forEach
                val toIndex = SitePage(
                    title = entry.title,
                    body = body,
                    url = url,
                    thumbnail = ""
                )

                val pageId = SitePage.hashPageId(siteId, url)

                try {
                    indexDocument(pageId, siteId, toIndex)
                        .ifPresentOrElse(
                            {
                                successfullyIndexed.incrementAndGet()
                                documents.add(pageId)
                            },
                            {
                                failedToIndex.add(url)
                                LOG.warn("Failed to index RSS entry: {}", url)
                            }
                        )
                } catch (e: Exception) {
                    failedToIndex.add(url)
                    LOG.error("Exception while indexing RSS entry {}: {}", url, e.message, e)
                }
            }

            LOG.info("Completed RSS feed indexing: {} documents indexed, {} failed", documents.size, failedToIndex.size)
            SiteIndexSummary(siteId, siteSecret, documents, failedToIndex)
        } catch (e: Exception) {
            LOG.error("Failed to process RSS feed from {}: {}", feedUrl, e.message, e)
            null
        }
    }

    fun clearSite(siteId: UUID, siteSecret: UUID): Boolean =
        if (isAllowedToModify(siteId, siteSecret)) {
            val deletion = indexService.deleteAllSitePages(siteId)
            deletion.success && deletion.deleted > 0
        } else {
            false
        }

    fun isDeleted(siteId: UUID, siteSecret: UUID, pageId: String): Boolean {
        if (pageId.isBlank() || !isAllowedToModify(siteId, siteSecret)) {
            return false
        }

        val deletion = indexService.deletePages(listOf(pageId))
        return deletion.success && deletion.deleted > 0
    }

    fun storeCrawlStatus(serviceSecret: UUID, sitesCrawlStatus: SitesCrawlStatus): Optional<SitesCrawlStatus> {
        if (ADMIN_SITE_SECRET != serviceSecret) {
            LOG.warn("Unauthorized crawl status storage attempt with invalid secret")
            return Optional.empty()
        }

        return try {
            val response = indexService.indexCrawlStatus(sitesCrawlStatus)

            when (response.statusCode()) {
                in 200..299 -> {
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Successfully stored crawl status for {} sites", sitesCrawlStatus.sites.size)
                    }
                    Optional.of(sitesCrawlStatus)
                }
                else -> {
                    LOG.error("Failed to store crawl status. HTTP {}: {}", response.statusCode(), response.body())
                    Optional.empty()
                }
            }
        } catch (e: Exception) {
            LOG.error("Exception while storing crawl status: {}", e.message, e)
            Optional.empty()
        }
    }

    fun fetchCrawlStatus(serviceSecret: UUID): Optional<SitesCrawlStatus> =
        if (ADMIN_SITE_SECRET == serviceSecret) {
            fetchSitesCrawlStatus()
        } else {
            Optional.empty()
        }

    private fun fetchSitesCrawlStatus(): Optional<SitesCrawlStatus> =
        Optional.of(indexService.fetchSiteCrawlStatus())

    fun updateSiteProfile(siteId: UUID, siteSecret: UUID, siteProfileUpdate: SiteProfileUpdate): Optional<SiteProfile> =
        fetchSiteSecret(siteId)
            .filter { it == siteSecret }
            .flatMap {
                storeSite(siteId, siteProfileUpdate.secret, siteProfileUpdate.email, siteProfileUpdate.configs)
                fetchSiteProfile(siteId)
            }

    /**
     * Removes pages from the index that haven't been updated within the retention period.
     * Uses a single delete-by-query operation for maximum performance.
     *
     * @param siteId The site to clean up
     * @param retentionDays Number of days to retain pages (default: 2)
     * @param includeUrls If true, fetches URLs before deletion for logging (adds one extra query)
     * @return Cleanup result with deletion counts, or null if no pages existed
     */
    fun removeOldSiteIndexPages(
        siteId: UUID,
        retentionDays: Long = 2,
        includeUrls: Boolean = false
    ): IndexCleanupResult? {
        val obsoletePageThreshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS)

        // Optionally fetch URLs for logging before deletion
        val urls: List<String> = if (includeUrls) {
            searchService.fetchObsoleteSitePages(siteId, obsoletePageThreshold)
                .hits
                .mapNotNull { it[Fields.URL] }
        } else {
            emptyList()
        }

        // Single delete-by-query operation - no need to fetch IDs first
        val deleteResult = indexService.deleteObsoleteSitePages(siteId, obsoletePageThreshold)

        return if (deleteResult.deleted == 0 && deleteResult.failed == 0) {
            null
        } else {
            IndexCleanupResult(
                deleted = deleteResult.deleted,
                failed = deleteResult.failed,
                urls = urls
            )
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SiteService::class.java)
        private val HTML_TAG_REGEX = "<[^>]*>".toRegex()
        private val PLACEHOLDER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        /**
         * This env initialization is just broken on Windows.
         */
        internal val ADMIN_SITE_SECRET: UUID = UUID.fromString(System.getenv("ADMIN_SITE_SECRET"))

        internal const val SITE_CONFIGURATION_DOCUMENT_PREFIX = "site-configuration-"
        internal const val CRAWL_STATUS_SINGLETON_DOCUMENT = "crawl-status"
        internal const val MAX_TOTAL_QUERY_PAGE_SIZE = 10_000

        /**
         * Field is updated whenever a document is (re-)indexed.
         */
        private const val PAGE_UPDATED = "updated"
        internal const val PAGE_LABELS = "labels"
        internal const val PAGE_LABELS_META_NAME = "sis-labels"
        internal const val PAGE_THUMBNAIL = "thumbnail"

        // Maximum feed size: 10 MB
        const val MAX_FEED_SIZE_BYTES = 10L * 1024 * 1024

        // Allowed URL schemes for feed fetching
        private val ALLOWED_SCHEMES = setOf("http", "https")

        init {
            BaseConfig.initializeTrustAllCertificates()
        }
    }
}