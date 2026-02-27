// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import net.loxal.finder.dto.Stats
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.util.*

@RestController
@RequestMapping(StatsController.ENDPOINT)
class StatsController {

    @GetMapping
    fun stats(
        @RequestParam("siteId", defaultValue = DEFAULT_SITE_ID) siteId: UUID
    ): Mono<Stats> {
        if (LOG.isInfoEnabled) {
            LOG.info("Stats requested for siteId: {}", siteId)
        }

        return STATS_MONO
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(StatsController::class.java)
        const val ENDPOINT = "/stats"
        private const val DEFAULT_SITE_ID = "00000000-0000-0000-0000-000000000001"

        // Cache environment variables at startup for performance
        private val BUILD_NUMBER = System.getenv("BUILD_NUMBER") ?: "0"
        private val SCM_HASH = System.getenv("SCM_HASH") ?: "ff"
        private val HOSTNAME = System.getenv("HOSTNAME").orEmpty()

        // Pre-create the Stats object and Mono for maximum performance
        private val CACHED_STATS = Stats(
            buildNumber = BUILD_NUMBER,
            scmHash = SCM_HASH,
            hostname = HOSTNAME
        )

        // Reuse the same Mono instance for all requests
        private val STATS_MONO = Mono.just(CACHED_STATS)
    }
}