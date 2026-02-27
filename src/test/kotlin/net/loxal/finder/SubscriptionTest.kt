// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.*
import kotlin.test.assertNotNull

@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.MethodName::class)
class SubscriptionTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun subscribeViaGitHub() {
        val gitHubEventPayload = "{\"github-data\": \"Here is some salt to make a rainbow attack harder.\"}"

        val response = webTestClient.post()
            .uri("/subscriptions/github")
            .header("X-Hub-Signature", "sha1=778a6bcb65bc5ff6d62ed91c4be70058d7f99a6a")
            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
            .header("X-GitHub-Event", "ping")
            .bodyValue(gitHubEventPayload)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody

        assertNotNull(response)
    }
}