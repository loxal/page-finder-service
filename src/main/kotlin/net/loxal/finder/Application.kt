// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder

import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootApplication
@RestController
open class Application {

    @PostMapping("/subscriptions/github")
    fun subscribeViaGitHub(
        @RequestHeader("X-GitHub-Delivery") delivery: UUID,
        @RequestHeader("X-GitHub-Event") event: String,
        @RequestHeader("X-Hub-Signature") signature: String,
        @RequestBody subscription: String
    ): ResponseEntity<String> {
        val isAuthenticGitHubEvent = verifySha1Signature(subscription, signature)

        return if (isAuthenticGitHubEvent) {
            if (LOG.isInfoEnabled) {
                LOG.info(
                    "GitHub webhook - delivery: {}, event: {}, signature: {}, subscription: {}",
                    delivery, event, signature, subscription
                )
            }
            ResponseEntity.status(HttpStatus.OK).body(subscription)
        } else {
            LOG.warn("Invalid GitHub webhook signature - delivery: {}", delivery)
            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build()
        }
    }

    private fun verifySha1Signature(subscription: String, signature: String): Boolean {
        // Thread-safe: create new instance for each verification
        val mac = Mac.getInstance(HMAC_SHA1_ALGORITHM).apply {
            init(SECRET_KEY_SPEC)
        }

        val expectedSha1Hash = mac.doFinal(subscription.toByteArray())
        val expectedSignature = SHA1_PREFIX + Hex.encodeHexString(expectedSha1Hash)

        return MessageDigest.isEqual(
            expectedSignature.lowercase().toByteArray(),
            signature.lowercase().toByteArray()
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(Application::class.java)

        internal val SERVICE_SECRET: UUID = UUID.fromString(System.getenv("SERVICE_SECRET"))

        private const val HMAC_SHA1_ALGORITHM = "HmacSHA1"
        private const val SHA1_PREFIX = "sha1="

        const val APEX_DOMAIN = "loxal.net"
        const val WWW_DOMAIN = "www.loxal.net"

        // Pre-create the SecretKeySpec for reuse (thread-safe)
        private val SECRET_KEY_SPEC = SecretKeySpec(
            SERVICE_SECRET.toString().toByteArray(),
            HMAC_SHA1_ALGORITHM
        )

        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(Application::class.java, *args)
        }
    }
}