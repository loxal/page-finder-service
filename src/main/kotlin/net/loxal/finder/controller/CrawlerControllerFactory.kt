// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import crawlercommons.robots.BaseRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import edu.uci.ics.crawler4j.crawler.CrawlController
import edu.uci.ics.crawler4j.crawler.WebCrawler
import net.loxal.finder.service.CrawlerService
import net.loxal.finder.service.SiteCrawler
import net.loxal.finder.service.SiteService
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import java.io.IOException
import java.net.URI
import java.util.*

class CrawlerControllerFactory(
    private val siteService: SiteService,
    private val siteId: UUID,
    private val siteSecret: UUID,
    private val url: URI,
    private val pageBodyCssSelector: String,
    private val allowUrlWithQuery: Boolean
) : CrawlController.WebCrawlerFactory<WebCrawler> {

    private val robotRules: BaseRobotRules = initRobotRules()

    private fun fetchRobotsTxt(): ByteArray {
        val request = Request.Builder()
            .url("$url/robots.txt")
            .build()

        return try {
            SiteCrawler.HTTP_CLIENT.newCall(request).execute().use { response ->
                if (response.code == HttpStatus.OK.value()) {
                    response.body.bytes()
                } else {
                    if (LOG.isWarnEnabled) {
                        LOG.warn("robots.txt not found or empty for siteId: {}", siteId)
                    }
                    EMPTY_BYTE_ARRAY
                }
            }
        } catch (e: IOException) {
            LOG.warn("robots.txt could not be fetched for siteId: {} - possibly invalid SSL certificate: {}",
                siteId, e.message)
            EMPTY_BYTE_ARRAY
        }
    }

    private fun initRobotRules(): BaseRobotRules {
        val parser = SimpleRobotRulesParser()
        val robotsTxt = fetchRobotsTxt()

        return parser.parseContent(
            url.toString(),
            robotsTxt,
            ROBOTS_CONTENT_TYPE,
            listOf(CrawlerService.SITE_SEARCH_USER_AGENT)
        )
    }

    override fun newInstance(): SiteCrawler =
        SiteCrawler(
            siteService = siteService,
            siteId = siteId,
            siteSecret = siteSecret,
            baseUrl = url,
            pageBodyCssSelector = pageBodyCssSelector,
            robotRules = robotRules,
            allowUrlWithQuery = allowUrlWithQuery
        )

    companion object {
        private val LOG = LoggerFactory.getLogger(CrawlerControllerFactory::class.java)
        private val EMPTY_BYTE_ARRAY = byteArrayOf()
        private const val ROBOTS_CONTENT_TYPE = "text/plain"
    }
}