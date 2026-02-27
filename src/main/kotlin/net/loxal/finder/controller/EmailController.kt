// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import net.loxal.finder.service.SiteService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping(SiteController.ENDPOINT)
class EmailController(
    private val siteService: SiteService
) {

    @PostMapping("{siteId}/email/setup-info")
    fun sendSetupInfo(
        @PathVariable("siteId") siteId: UUID,
        @RequestParam("siteSecret") siteSecret: UUID
    ): ResponseEntity<String> {
        // Rate limiting check
        val currentCount = HARD_ABUSE_LIMIT.incrementAndGet()
        if (currentCount > MAX_EMAIL_REQUESTS) {
            LOG.warn("Email rate limit exceeded: {} requests", currentCount)
            return ResponseEntity.badRequest()
                .body("E-mail limit exceeded. Please try again later.")
        }

        // Authorization check
        return if (siteService.isAllowedToModify(siteId, siteSecret)) {
            if (LOG.isDebugEnabled) {
                LOG.debug("Setup email sent for siteId: {}", siteId)
            }
            ResponseEntity.ok().build()
        } else {
            LOG.warn("Unauthorized email setup attempt for siteId: {}", siteId)
            // Return 404 instead of 401/403 to prevent enumeration attacks
            ResponseEntity.notFound().build()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EmailController::class.java)
        private val HARD_ABUSE_LIMIT = AtomicInteger(0)
        private const val MAX_EMAIL_REQUESTS = 3
    }
}