// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.controller

import net.loxal.finder.dto.FetchedPage
import net.loxal.finder.service.SiteService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(PageController.ENDPOINT)
class PageController(
    private val service: SiteService
) {

    @GetMapping("{id}")
    fun fetchById(@PathVariable("id") id: String): ResponseEntity<FetchedPage> =
        service.fetchById(id)
            .map { ResponseEntity.ok(it) }
            .orElseGet { ResponseEntity.notFound().build() }

    companion object {
        const val ENDPOINT = "/pages"
        private val LOG = LoggerFactory.getLogger(PageController::class.java)
    }
}