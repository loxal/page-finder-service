// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import com.sleepycat.je.EnvironmentFailureException
import crawlercommons.sitemaps.*
import edu.uci.ics.crawler4j.crawler.CrawlConfig
import edu.uci.ics.crawler4j.crawler.CrawlController
import edu.uci.ics.crawler4j.fetcher.PageFetcher
import edu.uci.ics.crawler4j.frontier.SleepycatFrontierConfiguration
import edu.uci.ics.crawler4j.robotstxt.RobotstxtConfig
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer
import edu.uci.ics.crawler4j.url.SleepycatWebURLFactory
import net.loxal.finder.controller.CrawlerControllerFactory
import net.loxal.finder.crawler.HtmlEntityDecodingUrlNormalizer
import net.loxal.finder.dto.CrawlerJobResult
import net.loxal.finder.dto.SiteProfile
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.deleteIfExists

/**
 * Service for managing web crawling operations.
 *
 * Handles crawling configuration, execution, and sitemap processing for site indexing.
 * Uses crawler4j framework with custom URL normalization and Sleepycat JE for frontier management.
 */
@Service
class CrawlerService {

    @Autowired
    private lateinit var siteService: SiteService

    // ========== Public API ==========

    /**
     * Crawls a single site with specified configuration.
     *
     * @param url The starting URL to crawl
     * @param siteId Unique identifier for the site
     * @param siteSecret Secret key for authentication
     * @param isThrottled If true, applies rate limiting and page count restrictions
     * @param sitemapsOnly If true, only crawls URLs from sitemap.xml
     * @param pageBodyCssSelector CSS selector for extracting page content
     * @param allowUrlWithQuery Whether to crawl URLs with query parameters
     * @return CrawlerJobResult containing page count and list of crawled URLs
     */
    fun crawl(
        url: String,
        siteId: UUID,
        siteSecret: UUID,
        isThrottled: Boolean,
        sitemapsOnly: Boolean,
        pageBodyCssSelector: String,
        allowUrlWithQuery: Boolean
    ): CrawlerJobResult {
        val (config, crawlerThreads) = createCrawlConfig(siteId, isThrottled)
        val factory = createCrawlerFactory(
            siteId, siteSecret, URI.create(url),
            pageBodyCssSelector, allowUrlWithQuery
        )

        val controller = startWithFrontierRetry(siteId, config) { ctrl ->
            if (sitemapsOnly) {
                configureSitemapOnlyCrawl(config, ctrl, url)
            } else {
                ctrl.addSeed(url)
            }
            ctrl.start(factory, crawlerThreads)
        }

        return collectCrawlResults(siteId, controller)
    }

    /**
     * Re-crawls multiple sites based on site profile configuration.
     *
     * @param siteId Unique identifier for the site
     * @param siteSecret Secret key for authentication
     * @param siteProfile Profile containing multiple site configurations
     * @return CrawlerJobResult with aggregated results from all configurations
     */
    fun recrawl(siteId: UUID, siteSecret: UUID, siteProfile: SiteProfile): CrawlerJobResult {
        val allUrls = mutableListOf<String>()

        for (siteConfig in siteProfile.configs) {
            val configUrl = siteConfig.url ?: continue

            val (config, crawlerThreads) = createCrawlConfig(siteId, isThrottled = true)
            val factory = createCrawlerFactory(
                siteId, siteSecret, configUrl,
                siteConfig.pageBodyCssSelectorOrDefault(),
                siteConfig.allowUrlWithQuery
            )

            val controller = startWithFrontierRetry(siteId, config) { ctrl ->
                if (siteConfig.sitemapsOnly && hasSitemap(configUrl, siteId)) {
                    configureSitemapOnlyCrawl(config, ctrl, configUrl.toString())
                } else {
                    ctrl.addSeed(configUrl.toString())
                }
                ctrl.start(factory, crawlerThreads)
            }

            allUrls.addAll(extractCrawledUrls(controller))
        }

        SiteCrawler.PAGE_COUNT.remove(siteId)
        return CrawlerJobResult(allUrls.size, allUrls)
    }

    // ========== Configuration ==========

    private fun createCrawlConfig(siteId: UUID, isThrottled: Boolean): Pair<CrawlConfig, Int> {
        val crawlerThreads = if (isThrottled) THROTTLED_CRAWLER_THREADS else UNTHROTTLED_CRAWLER_THREADS

        val config = CrawlConfig().apply {
            crawlStorageFolder = siteStorageFolder(siteId).toString()
            applyStableShutdownTimings(this)

            if (isThrottled) {
                userAgentString = generateRandomUserAgent()
                politenessDelay = POLITENESS_DELAY_MS
                maxPagesToFetch = MAX_PAGES_THROTTLED
            } else {
                userAgentString = SITE_SEARCH_USER_AGENT
                politenessDelay = POLITENESS_DELAY_MS
            }
        }

        return config to crawlerThreads
    }

    private fun applyStableShutdownTimings(config: CrawlConfig) {
        // Conservative timings to reduce races with Sleepycat JE file handling
        config.cleanupDelaySeconds = STABLE_CLEANUP_DELAY_SECONDS
        config.threadMonitoringDelaySeconds = STABLE_THREAD_MONITORING_DELAY_SECONDS
        config.threadShutdownDelaySeconds = STABLE_THREAD_SHUTDOWN_DELAY_SECONDS
    }

    private fun createCrawlerFactory(
        siteId: UUID,
        siteSecret: UUID,
        url: URI,
        pageBodyCssSelector: String,
        allowUrlWithQuery: Boolean
    ): CrawlerControllerFactory {
        return CrawlerControllerFactory(
            siteService, siteId, siteSecret, url,
            pageBodyCssSelector, allowUrlWithQuery
        )
    }

    private fun generateRandomUserAgent(): String {
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
               "(KHTML, like Gecko) Chrome/78.0.${RANDOM_VERSION.nextInt(9999)}.70 Safari/537.36"
    }

    // ========== Controller Setup ==========

    private fun setupController(config: CrawlConfig): CrawlController {
        val urlNormalizer = HtmlEntityDecodingUrlNormalizer()
        val pageFetcher = PageFetcher(config, urlNormalizer)
        val robotsTxtServer = createRobotsTxtServer(pageFetcher)

        config.isRespectNoIndex = false
        config.isRespectNoFollow = false

        return try {
            val frontierConfig = SleepycatFrontierConfiguration(config)
            CrawlController(config, urlNormalizer, pageFetcher, robotsTxtServer, frontierConfig)
        } catch (e: EnvironmentFailureException) {
            LOG.error("Fatal Sleepycat JE environment failure during controller setup", e)
            throw Error("Fatal Sleepycat JE environment failure; force restart.", e)
        }
    }

    private fun createRobotsTxtServer(pageFetcher: PageFetcher): RobotstxtServer {
        val robotsTxtConfig = RobotstxtConfig().apply {
            // Use crawler-commons's robots.txt interpretation instead
            isEnabled = false
        }
        val webURLFactory = SleepycatWebURLFactory()
        return RobotstxtServer(robotsTxtConfig, pageFetcher, webURLFactory)
    }

    private fun startWithFrontierRetry(
        siteId: UUID,
        config: CrawlConfig,
        start: (CrawlController) -> Unit
    ): CrawlController {
        return try {
            val controller = setupController(config)
            start(controller)
            controller
        } catch (e: EnvironmentFailureException) {
            LOG.error(
                "Sleepycat JE environment failure. Deleting crawl storage and retrying. " +
                "siteId: $siteId - storage: ${config.crawlStorageFolder}",
                e
            )

            deleteRecursively(Path.of(config.crawlStorageFolder))

            try {
                val retryController = setupController(config)
                start(retryController)
                retryController
            } catch (e2: EnvironmentFailureException) {
                LOG.error("Retry after deleting crawl storage failed. siteId: $siteId", e2)
                throw Error("Fatal Sleepycat JE environment failure; force restart.", e2)
            }
        }
    }

    // ========== Sitemap Processing ==========

    private fun configureSitemapOnlyCrawl(config: CrawlConfig, controller: CrawlController, url: String) {
        config.maxOutgoingLinksToFollow = 0
        config.maxDepthOfCrawling = 0

        extractSeedUrls(url).forEach { seedUrl ->
            controller.addSeed(seedUrl.toString())
        }
    }

    private fun extractSeedUrls(url: String): List<URL> {
        val seedUrls = mutableListOf<URL>()
        val uri = URI.create(url)
        val siteRoot = "${uri.scheme}://${uri.host}${uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
        val sitemapUrl = URI.create("$siteRoot/sitemap.xml").toURL()

        return runCatching {
            val parser = SiteMapParser(false, true)
            val abstractSiteMap = parser.parseSiteMap(sitemapUrl)
            walkSiteMap(abstractSiteMap, seedUrls)
            seedUrls
        }.getOrElse { e ->
            LOG.warn("Failed to extract seed URLs from sitemap: $sitemapUrl", e)
            emptyList()
        }
    }

    @Throws(UnknownFormatException::class, IOException::class)
    private fun walkSiteMap(abstractSiteMap: AbstractSiteMap, seedUrls: MutableList<URL>) {
        when {
            abstractSiteMap.isIndex -> {
                (abstractSiteMap as SiteMapIndex).sitemaps.forEach { siteMap ->
                    walkSiteMap(siteMap, seedUrls)
                }
            }
            else -> {
                val parser = SiteMapParser(false, true)
                val siteMap = parser.parseSiteMap(abstractSiteMap.url) as SiteMap
                siteMap.siteMapUrls.forEach { siteMapUrl ->
                    seedUrls.add(siteMapUrl.url)
                }
            }
        }
    }

    private fun hasSitemap(siteRoot: URI?, siteId: UUID): Boolean {
        if (siteRoot == null) return false

        val sitemapLocation = "${siteRoot.scheme}://${siteRoot.host}/sitemap.xml"
        return if (checkUrl(sitemapLocation)) {
            true
        } else {
            LOG.warn("siteId: $siteId - sitemap not found: $sitemapLocation")
            false
        }
    }

    private fun checkUrl(url: String): Boolean {
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            SiteCrawler.HTTP_CLIENT.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrElse { e ->
            LOG.debug("URL check failed: $url - ${e.message}")
            false
        }
    }

    // ========== Result Collection ==========

    private fun collectCrawlResults(siteId: UUID, controller: CrawlController): CrawlerJobResult {
        val urls = extractCrawledUrls(controller)
        SiteCrawler.PAGE_COUNT.remove(siteId)
        return CrawlerJobResult(urls.size, urls)
    }

    private fun extractCrawledUrls(controller: CrawlController): List<String> {
        return controller.crawlersLocalData
            .filterNotNull()
            .filterIsInstance<String>()
    }

    // ========== Storage Management ==========

    private fun siteStorageFolder(siteId: UUID): Path {
        // Make the crawl storage folder unique per run to avoid cross-crawl
        // interference and Sleepycat JE file locking issues.
        // Format: crawler/siteId-{uuid}-{timestamp}
        val timestamp = Instant.now().toString()
        val folderName = "siteId-$siteId-$timestamp"
        val siteDir = Path.of(CRAWLER_STORAGE, folderName)
        Files.createDirectories(siteDir)
        return siteDir
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return

        runCatching {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { path ->
                    runCatching {
                        path.deleteIfExists()
                    }.onFailure { e ->
                        LOG.warn("Failed to delete path during cleanup: $path", e)
                    }
                }
        }.onFailure { e ->
            LOG.warn("Failed to walk directory tree for deletion: $root", e)
        }
    }

    // ========== Companion Object ==========

    companion object {
        private val LOG = LoggerFactory.getLogger(CrawlerService::class.java)

        // Storage Configuration
        private const val CRAWLER_STORAGE = "crawler"

        // User Agent Configuration
        private val RANDOM_VERSION = Random()
        val SITE_SEARCH_USER_AGENT: String = System.getenv("IS_VPN_IP")
            ?.takeIf { it.toBoolean() }
            ?.let { UUID.randomUUID().toString() }
            ?: "opty"

        // Crawler Thread Configuration
        private const val THROTTLED_CRAWLER_THREADS = 2
        private const val UNTHROTTLED_CRAWLER_THREADS = 4

        // Rate Limiting Configuration
        private const val POLITENESS_DELAY_MS = 200
        private const val MAX_PAGES_THROTTLED = 500

        // Shutdown Timing Configuration (conservative to avoid Sleepycat JE races)
        private const val STABLE_CLEANUP_DELAY_SECONDS = 4
        private const val STABLE_THREAD_MONITORING_DELAY_SECONDS = 4
        private const val STABLE_THREAD_SHUTDOWN_DELAY_SECONDS = 4
    }
}