// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import net.loxal.finder.Application
import net.loxal.finder.dto.IndexResult
import net.loxal.finder.dto.*
import net.loxal.finder.service.AutocompleteService
import net.loxal.finder.service.SearchService
import net.loxal.finder.service.SimpleIndexClient
import net.loxal.finder.service.SiteService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.*

@RestController
@RequestMapping(SiteController.ENDPOINT)
class SiteController(
    private val siteService: SiteService,
    private val searchService: SearchService,
    private val autocompleteService: AutocompleteService
) {

    @PutMapping("flush")
    private fun flush(@RequestParam("serviceSecret") serviceSecret: UUID): ResponseEntity<String> {
        val svcIndexResult = siteService.flush(serviceSecret, SimpleIndexClient.SVC_SINGLETONS)
        val pageIndexResult = siteService.flush(serviceSecret, SimpleIndexClient.SITE_PAGE)

        return when {
            pageIndexResult == 200 && svcIndexResult == 200 -> ResponseEntity.noContent().build()
            pageIndexResult == svcIndexResult -> ResponseEntity.status(pageIndexResult).build()
            else -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("pageIndexResult: $pageIndexResult - svcIndexResult: $svcIndexResult")
        }
    }

    @PostMapping
    private fun createNewSite(@RequestBody(required = false) siteProfileCreation: SiteProfile?): ResponseEntity<SiteCreation> {
        val newlyCreatedSite = siteProfileCreation?.let {
            siteService.createSite(it.email, it.configs)
        } ?: siteService.createSite()

        return ResponseEntity
            .created(URI.create("https://search.${Application.APEX_DOMAIN}/sites/${newlyCreatedSite.siteId}"))
            .body(newlyCreatedSite)
    }

    @GetMapping("{siteId}/profile")
    private fun fetchSiteProfile(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID
    ): ResponseEntity<SiteProfile> =
        siteService.fetchSiteProfile(siteId, siteSecret)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }

    @PutMapping("{siteId}/profile")
    private fun updateSiteProfile(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestBody siteProfileUpdate: SiteProfileUpdate
    ): ResponseEntity<SiteProfile> =
        siteService.updateSiteProfile(siteId, siteSecret, siteProfileUpdate)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }

    @GetMapping("{siteId}/pages")
    private fun fetchViaUrl(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("url") url: String
    ): ResponseEntity<FetchedPage> {
        val pageId = SitePage.hashPageId(siteId, url)
        return siteService.fetchById(pageId)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }
    }

    @PutMapping("{siteId}/pages")
    private fun addPageToSiteIndex(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestBody page: SitePage
    ): ResponseEntity<FetchedPage> {
        val url = page.url
        val pageId = SitePage.hashPageId(siteId, url)

        return siteService.indexExistingPage(pageId, siteId, siteSecret, page)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }
    }

    /**
     * Only for internal use or service-layer use. Should not be exposed externally.
     * Client-side ID determination bears inconsistency.
     */
    @PutMapping("{siteId}/pages/{pageId}")
    private fun updateExistingPageInSiteIndex(
        @PathVariable("siteId") siteId: UUID,
        @PathVariable("pageId") pageId: String,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestBody page: SitePage
    ): ResponseEntity<FetchedPage> {
        // Validate pageId length (SHA-256 hex = 64 characters)
        if (pageId.length != PAGE_ID_LENGTH) {
            return ResponseEntity.badRequest().build()
        }

        return siteService.indexExistingPage(pageId, siteId, siteSecret, page)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }
    }

    @GetMapping("{siteId}")
    private fun fetchAll(@PathVariable("siteId") siteId: UUID): ResponseEntity<List<String>> =
        siteService.fetchAllDocuments(siteId)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }

    @PutMapping("{siteId}/xml")
    private fun reimportIndex(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestParam("xmlUrl") xmlUrl: String,
        @RequestParam("stripHtmlTags", defaultValue = "false") stripHtmlTags: Boolean,
        @RequestParam("clearIndex", defaultValue = "false") clearIndex: Boolean
    ): ResponseEntity<*> =
        indexAsRssFeedWithValidation(
            siteId = siteId,
            siteSecret = siteSecret,
            feedUrl = xmlUrl,
            stripHtmlTags = stripHtmlTags,
            isGeneric = true,
            clearIndex = clearIndex
        )

    @PutMapping("{siteId}/rss")
    private fun indexRssFeed(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestParam("feedUrl") feedUrl: String,
        @RequestParam("stripHtmlTags", defaultValue = "false") stripHtmlTags: Boolean
    ): ResponseEntity<*> =
        indexAsRssFeedWithValidation(
            siteId = siteId,
            siteSecret = siteSecret,
            feedUrl = feedUrl,
            stripHtmlTags = stripHtmlTags,
            isGeneric = false,
            clearIndex = false
        )

    @PostMapping("rss")
    private fun indexNewRssFeed(
        @RequestParam("feedUrl") feedUrl: String,
        @RequestParam("stripHtmlTags", defaultValue = "false") stripHtmlTags: Boolean
    ): ResponseEntity<*> =
        indexAsRssFeedWithValidation(
            siteId = null,
            siteSecret = null,
            feedUrl = feedUrl,
            stripHtmlTags = stripHtmlTags,
            isGeneric = false,
            clearIndex = false
        )

    /**
     * Resilient feed indexing with URL validation and meaningful HTTP status codes.
     */
    private fun indexAsRssFeedWithValidation(
        siteId: UUID?,
        siteSecret: UUID?,
        feedUrl: String,
        stripHtmlTags: Boolean,
        isGeneric: Boolean,
        clearIndex: Boolean
    ): ResponseEntity<*> {
        val result = siteService.indexFeedWithValidation(
            feedUrl = feedUrl,
            siteId = siteId,
            siteSecret = siteSecret,
            stripHtmlTags = stripHtmlTags,
            isGeneric = isGeneric,
            clearIndex = clearIndex
        )

        return when (result) {
            is IndexResult.Success -> ResponseEntity.ok(result.summary)
            is IndexResult.Unauthorized -> ResponseEntity.badRequest()
                .body(mapOf("error" to "Invalid site credentials"))
            is IndexResult.InvalidUrl -> ResponseEntity.badRequest()
                .body(mapOf("error" to result.reason))
            is IndexResult.FeedFetchFailed -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(mapOf("error" to "Failed to fetch feed", "url" to result.url, "reason" to result.reason))
            is IndexResult.FeedTooLarge -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(mapOf("error" to "Feed exceeds maximum size", "sizeBytes" to result.sizeBytes, "maxBytes" to result.maxBytes))
            is IndexResult.InvalidFeed -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf("error" to "Invalid feed format", "reason" to result.reason))
            is IndexResult.EmptyFeed -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf("error" to "Feed contains no indexable content"))
        }
    }

    private fun indexAsRssFeed(
        siteId: UUID?,
        siteSecret: UUID?,
        feedUrl: URI,
        stripHtmlTags: Boolean,
        isGeneric: Boolean,
        clearIndex: Boolean
    ): ResponseEntity<SiteIndexSummary> {
        val siteCreatedInfo = siteService.indexFeed(
            feedUrl = feedUrl,
            siteId = siteId,
            siteSecret = siteSecret,
            stripHtmlTags = stripHtmlTags,
            isGeneric = isGeneric,
            clearIndex = clearIndex
        )
        return siteCreatedInfo?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.badRequest().build()
    }

    @DeleteMapping("{siteId}/pages/{pageId}")
    private fun deleteById(
        @PathVariable("siteId") siteId: UUID,
        @PathVariable("pageId") pageId: String,
        @RequestParam("siteSecret") siteSecret: UUID
    ): ResponseEntity<Void> =
        if (siteService.isDeleted(siteId, siteSecret, pageId)) {
            ResponseEntity.noContent().build()
        } else {
            // Do not return UNAUTHORIZED/FORBIDDEN as those could be misused for brute force attacks
            ResponseEntity.notFound().build()
        }

    @DeleteMapping("{siteId}/pages")
    private fun deleteViaUrl(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID,
        @RequestParam("url") url: String
    ): ResponseEntity<Void> {
        val pageId = SitePage.hashPageId(siteId, url)
        return deleteById(siteId = siteId, pageId = pageId, siteSecret = siteSecret)
    }

    @DeleteMapping("{siteId}")
    private fun clearSiteIndex(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID
    ): ResponseEntity<Void> =
        if (siteService.clearSite(siteId, siteSecret)) {
            ResponseEntity.ok().build()
        } else {
            ResponseEntity.noContent().build()
        }

    @GetMapping("{siteId}/autocomplete")
    private fun autocompleteSuggestion(
        @CookieValue("override-site", required = false) cookieSite: UUID?,
        @RequestParam("query", defaultValue = "") query: String,
        @PathVariable("siteId") siteId: UUID
    ): ResponseEntity<Autocomplete> {
        val start = Instant.now()

        // Override siteId with cookie value for debugging & speed up the getting started experience
        val usedSiteId = cookieSite ?: siteId
        val autocomplete = autocompleteService.autocomplete(query, usedSiteId)

        val duration = Duration.between(start, Instant.now())

        if (LOG.isInfoEnabled) {
            LOG.info(
                "Autocomplete - siteId: {}, query: '{}', results: {}, durationMs: {}",
                usedSiteId, query, autocomplete.results.size, duration.toMillis()
            )
        }

        return ResponseEntity.ok(autocomplete)
    }

    @GetMapping("{siteId}/search")
    private fun search(
        @CookieValue("override-site", required = false) cookieSite: UUID?,
        @RequestParam("query", defaultValue = "") query: String,
        @PathVariable("siteId") siteId: UUID
    ): ResponseEntity<Result> {
        val start = Instant.now()

        // Override siteId with cookie value for debugging & speed up the getting started experience
        val usedSiteId = cookieSite ?: siteId
        val findings = searchService.search(query, usedSiteId)

        val duration = Duration.between(start, Instant.now())

        if (LOG.isInfoEnabled) {
            LOG.info(
                "Search - siteId: {}, query: '{}', results: {}, durationMs: {}",
                usedSiteId, query, findings.results.size, duration.toMillis()
            )
        }

        return ResponseEntity.ok(findings)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SiteController::class.java)
        internal const val ENDPOINT = "/sites"
        internal val SIS_API_SERVICE_URL: String = System.getenv("SIS_API_SERVICE_URL")
        private const val PAGE_ID_LENGTH = 64 // SHA-256 hex length
    }
}