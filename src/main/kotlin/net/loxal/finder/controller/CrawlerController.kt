// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import net.loxal.finder.Application
import net.loxal.finder.dto.CrawlStatus
import net.loxal.finder.dto.CrawlerJobResult
import net.loxal.finder.dto.SiteProfile
import net.loxal.finder.dto.SitesCrawlStatus
import net.loxal.finder.service.CrawlerService
import net.loxal.finder.service.SiteService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RestController
@RequestMapping(SiteController.ENDPOINT)
class CrawlerController(
    private val siteService: SiteService,
    private val crawlerService: CrawlerService
) {

    @PostMapping("crawl")
    fun recrawl(
        @RequestParam("serviceSecret") serviceSecret: UUID,
        @RequestBody sitesCrawlStatusUpdate: SitesCrawlStatus,
        @RequestParam("allSitesCrawl", defaultValue = "false") allSitesCrawl: Boolean,
        @RequestParam("isThrottled", defaultValue = "true") isThrottled: Boolean,
        @RequestParam("clearIndex", defaultValue = "false") clearIndex: Boolean
    ): ResponseEntity<SitesCrawlStatus> =
        crawlSite(serviceSecret, sitesCrawlStatusUpdate, allSitesCrawl, isThrottled, clearIndex)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.badRequest().build() }

    /**
     * Crawls multiple sites with optimized parallel processing.
     *
     * Optimizations applied:
     * - Parallel site processing using a bounded thread pool
     * - Removed redundant O(n²) config lookup loop
     * - Single cleanup call per site (not per config)
     * - Single status update per site (after all configs processed)
     * - Thread-safe result aggregation with ConcurrentHashMap
     */
    private fun crawlSite(
        serviceSecret: UUID,
        sitesCrawlStatusUpdate: SitesCrawlStatus,
        allSiteCrawl: Boolean,
        isThrottled: Boolean,
        clearIndex: Boolean
    ): Optional<SitesCrawlStatus> {
        if (SiteService.ADMIN_SITE_SECRET != serviceSecret) {
            LOG.warn("Unauthorized crawl attempt with invalid service secret")
            return Optional.empty()
        }

        val collectedStatuses = ConcurrentHashMap.newKeySet<CrawlStatus>()
        val halfDayAgo = Instant.now().minus(1, ChronoUnit.HALF_DAYS)

        // Filter sites that need crawling
        val sitesToCrawl = sitesCrawlStatusUpdate.sites.filter { crawlStatus ->
            allSiteCrawl || Instant.parse(crawlStatus.crawled).isBefore(halfDayAgo)
        }

        if (sitesToCrawl.isEmpty()) {
            LOG.info("No sites need crawling")
            return Optional.of(SitesCrawlStatus(mutableSetOf()))
        }

        LOG.info("Starting crawl for {} sites", sitesToCrawl.size)

        // Process sites in parallel with bounded thread pool
        val executor = Executors.newFixedThreadPool(minOf(sitesToCrawl.size, MAX_PARALLEL_CRAWLS))

        try {
            val futures = sitesToCrawl.map { crawlStatus ->
                executor.submit {
                    crawlSingleSite(crawlStatus, clearIndex, isThrottled, collectedStatuses)
                }
            }

            // Wait for all crawls to complete with timeout
            futures.forEach { future ->
                try {
                    future.get(CRAWL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                } catch (e: Exception) {
                    LOG.error("Crawl task failed: {}", e.message, e)
                }
            }
        } finally {
            executor.shutdown()
            try {
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        LOG.info("Completed crawl for {} sites, collected {} statuses", sitesToCrawl.size, collectedStatuses.size)
        return Optional.of(SitesCrawlStatus(collectedStatuses.toMutableSet()))
    }

    /**
     * Crawls a single site with all its configurations.
     * Aggregates results and updates status atomically.
     */
    private fun crawlSingleSite(
        crawlStatus: CrawlStatus,
        clearIndex: Boolean,
        isThrottled: Boolean,
        collectedStatuses: MutableSet<CrawlStatus>
    ) {
        val siteId = crawlStatus.siteId ?: run {
            LOG.warn("Skipping crawl status with null siteId")
            return
        }

        val siteSecret = siteService.fetchSiteSecret(siteId).orElse(null) ?: run {
            LOG.warn("Site secret not found for siteId: {}", siteId)
            return
        }

        val profile = siteService.fetchSiteProfile(siteId).orElse(null) ?: run {
            LOG.warn("Site profile not found for siteId: {}", siteId)
            return
        }

        // Clear index once per site if requested
        if (clearIndex && !siteService.clearSite(profile.id, profile.secret)) {
            LOG.warn("Failed to clear index for siteId: {}, skipping crawl", siteId)
            return
        }

        val totalPageCount = AtomicLong(0)

        // Process each config directly - no redundant lookup
        profile.configs.forEach { config ->
            val configUrl = config.url ?: return@forEach

            try {
                val result = crawlerService.crawl(
                    url = configUrl.toString(),
                    siteId = siteId,
                    siteSecret = siteSecret,
                    isThrottled = isThrottled,
                    sitemapsOnly = config.sitemapsOnly,
                    pageBodyCssSelector = config.pageBodyCssSelectorOrDefault(),
                    allowUrlWithQuery = config.allowUrlWithQuery
                )
                totalPageCount.addAndGet(result.pageCount.toLong())

                if (LOG.isInfoEnabled) {
                    LOG.info("Crawled siteId: {}, url: {}, pages: {}", siteId, configUrl, result.pageCount)
                }
            } catch (e: Exception) {
                LOG.error("Crawl failed for siteId: {}, url: {} - {}", siteId, configUrl, e.message, e)
            }
        }

        // Single status update after all configs are processed
        siteService.updateCrawlStatusInSchedule(siteId, totalPageCount.get()).ifPresent { status ->
            collectedStatuses.addAll(status.sites)
        }

        // Single cleanup call per site (not per config)
        siteService.removeOldSiteIndexPages(siteId)?.let { cleanup ->
            LOG.info("Cleanup for siteId: {} - deleted: {}, failed: {}", siteId, cleanup.deleted, cleanup.failed)
        }

        collectedStatuses.add(
            CrawlStatus(
                siteId = profile.id,
                crawled = Instant.now().toString(),
                pageCount = totalPageCount.get()
            )
        )
    }

    @PutMapping("crawl/status")
    fun updateCrawlStatus(
        @RequestParam("serviceSecret") serviceSecret: UUID,
        @RequestBody sitesCrawlStatusUpdate: SitesCrawlStatus
    ): ResponseEntity<SitesCrawlStatus> =
        siteService.storeCrawlStatus(serviceSecret, sitesCrawlStatusUpdate)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.badRequest().build() }

    @GetMapping("crawl/status")
    fun fetchCrawlStatus(
        @RequestParam("serviceSecret") serviceSecret: UUID
    ): ResponseEntity<SitesCrawlStatus> =
        siteService.fetchCrawlStatus(serviceSecret)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.badRequest().build() }

    @PostMapping("{siteId}/recrawl")
    fun recrawl(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestParam("clearIndex", defaultValue = "false") clearIndex: Boolean
    ): ResponseEntity<CrawlerJobResult> {
        // fetchSiteProfile already validates siteSecret internally
        val profile = siteService.fetchSiteProfile(siteId, siteSecret).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (clearIndex) {
            siteService.clearSite(siteId, siteSecret)
        }

        val crawlerJobResult = crawlerService.recrawl(siteId, siteSecret, profile)

        siteService.removeOldSiteIndexPages(siteId)?.let { cleanup ->
            LOG.info("Cleanup for siteId: {} - deleted: {}, failed: {}", siteId, cleanup.deleted, cleanup.failed)
        }

        if (LOG.isInfoEnabled) {
            LOG.info("Recrawled siteId: {}, siteSecret: {}, pages: {}", siteId, siteSecret, crawlerJobResult.pageCount)
        }

        return ResponseEntity.ok(crawlerJobResult)
    }

    @PostMapping("{siteId}/crawl")
    fun crawl(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestParam("url") url: URI,
        @RequestParam("email") email: String,
        @RequestParam("token", defaultValue = "empty") captchaToken: String,
        @RequestParam("sitemapsOnly", defaultValue = "false") sitemapsOnly: Boolean,
        @RequestParam("allowUrlWithQuery", defaultValue = "false") allowUrlWithQuery: Boolean,
        @RequestParam(
            "pageBodyCssSelector",
            defaultValue = SiteProfile.Config.DEFAULT_PAGE_BODY_CSS_SELECTOR
        ) pageBodyCssSelector: String
    ): ResponseEntity<CrawlerJobResult> {
        if (!siteService.isAllowedToModify(siteId, siteSecret)) {
            return ResponseEntity.notFound().build()
        }

        // Temporarily allow pseudo-abuse-protection using a fixed token
        val alwaysPassDisableCaptcha = true

        return if (alwaysPassDisableCaptcha) {
            val crawlerJobResult = crawlerService.crawl(
                url = url.toString(),
                siteId = siteId,
                siteSecret = siteSecret,
                isThrottled = true,
                sitemapsOnly = sitemapsOnly,
                pageBodyCssSelector = pageBodyCssSelector,
                allowUrlWithQuery = allowUrlWithQuery
            )

            val emailAddress = determineEmailAddress(email)

            try {
                sendSetupInfoEmail(siteId, siteSecret, url, emailAddress, crawlerJobResult.pageCount)
            } catch (e: Exception) {
                LOG.error("Failed to send setup email: {}", e.message, e)
            }

            if (LOG.isInfoEnabled) {
                LOG.info(
                    "Crawl completed - siteId: {}, siteSecret: {}, url: {}, pages: {}, email: {}",
                    siteId, siteSecret, url, crawlerJobResult.pageCount, email
                )
            }

            ResponseEntity.ok(crawlerJobResult)
        } else {
            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build()
        }
    }

    private fun determineEmailAddress(email: String?): String =
        if (!email.isNullOrEmpty() && email.contains("@")) {
            email
        } else {
            EMAIL_SMTP_ADDRESS
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(CrawlerController::class.java)

        private const val MAX_PARALLEL_CRAWLS = 4
        private const val CRAWL_TIMEOUT_MINUTES = 30L
        private const val EMAIL_SMTP_ADDRESS = "alexander.orlov@loxal.net"
        private const val INFO_EMAIL_ADDRESS = "info@loxal.net"
        private const val SUPPORT_EMAIL_ADDRESS = "Site Search Support <support@loxal.net>"

        private val DEV_SKIP_FLAG: Boolean = System.getenv("DEV_SKIP_FLAG")?.toBoolean() ?: false
        private val EMAIL_SMTP_SECRET: String = System.getenv("EMAIL_SMTP_SECRET")

        // Email properties cached at startup for performance
        private val emailProperties = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", 587)
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }

        private fun createEmail(to: String, subject: String, bodyText: String): MimeMessage {
            val emailSession = Session.getInstance(emailProperties, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(EMAIL_SMTP_ADDRESS, EMAIL_SMTP_SECRET)
            })

            return MimeMessage(emailSession).apply {
                setFrom(InternetAddress(SUPPORT_EMAIL_ADDRESS))
                replyTo = arrayOf(InternetAddress(SUPPORT_EMAIL_ADDRESS))
                addRecipient(MimeMessage.RecipientType.TO, InternetAddress(to))
                addRecipient(MimeMessage.RecipientType.BCC, InternetAddress(INFO_EMAIL_ADDRESS))
                setSubject(subject)
                setText(bodyText, "UTF-8", "html")
            }
        }

        private fun sendMessageViaSMTP(email: MimeMessage) {
            try {
                Transport.send(email)
            } catch (e: MessagingException) {
                LOG.error("Failed to send email via SMTP: {}", e.message, e)
            }
        }

        private fun sendSetupInfoEmail(
            siteId: UUID,
            siteSecret: UUID,
            url: URI,
            toEmail: String,
            pageCount: Int
        ) {
            val mailBodyText = """
                Hello,
                <br/><br/>you are just a few steps away from adding Site Search to your website.
                <br/>Below you should find everything you need to evaluate Site Search for your website.
                <dl><dt><strong>Website URL:</strong></dt><dd>$url
                </dd><dt>Pages crawled:</dt><dd>$pageCount
                </dd><dt><strong>Site ID:</strong></dt><dd>$siteId
                </dd><dt><strong>Site Secret:</strong></dt><dd>$siteSecret
                </dd></dl>
                <a href='https://${Application.WWW_DOMAIN}/app/gadget/main.html?siteId=$siteId&siteSecret=$siteSecret&url=$url'>Try Site Search as it would look like on your site, using this evaluation link.</a>
                <br/>Please do not hesitate to ask us any questions you should encounter during your 14-day evaluation period!
                <br/>After the 14-day trial period, you can just continue using Site Search by <a href='https://${Application.WWW_DOMAIN}/pricing-site-search.html'>subscribing to one of our offerings</a>.
                <br/><br/>Using the credentials above, you agree with our <a href='https://${Application.WWW_DOMAIN}/terms-of-service-tos-sla.html'>Terms & Conditions</a>.
                <br/><br/>Kind regards,
                <br/>The Site Search team
            """.trimIndent().replace("\n", "")

            val emailSubject = "Site Search is ready for your first search \uD83D\uDD0D"

            sendMessageViaSMTP(
                createEmail(
                    to = toEmail,
                    subject = emailSubject,
                    bodyText = mailBodyText
                )
            )
        }
    }
}