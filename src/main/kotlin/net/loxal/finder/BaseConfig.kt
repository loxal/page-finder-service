// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.slf4j.LoggerFactory
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Configuration
open class BaseConfig : WebFluxConfigurer {

    /**
     * Creates an ObjectMapper with the Kotlin module for proper data class deserialization.
     * Jackson 3.x: KotlinModule is registered by default when using JsonMapper.builder()
     */
    @Bean
    open fun objectMapper(): JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    /**
     * Configure OpenAPI documentation for the Page Finder API.
     * Accessible at /swagger-ui.html or /v3/api-docs
     */
    @Bean
    open fun openApiInfo(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Page Finder API")
                .description("A simple website search solution")
                .version("v1")
                .contact(
                    Contact()
                        .name("loxal")
                        .url("https://${Application.APEX_DOMAIN}")
                        .email("info@loxal.net")
                )
                .license(
                    License()
                        .name("Apache License, Version 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")
                )
                .termsOfService("https://${Application.APEX_DOMAIN}/terms-of-service-tos-sla.html")
        )
        .servers(
            listOf(
                Server()
                    .url("https://search.${Application.APEX_DOMAIN}")
                    .description("Production API Server")
            )
        )

    /**
     * Configure public API group - excludes internal/admin endpoints.
     * This creates a filtered view of the API documentation.
     */
    @Bean
    open fun publicApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("public-api")
        .displayName("Public API")
        .pathsToExclude(
            "/legal/**",
            "/sites/crawl/**",
            "/sites/*/crawl",
            "/sites/*/recrawl",
            "/sites/*/crawling",
            "/sites/*/email/**",
            "/sites/flush",
            "/sites/rss",
            "/sites/*/xml",
            "/sites/*/rss",
            "/error",
            "/login/**",
            "/subscriptions/**",
            "/assignments/**",
            "/authentication-providers/**",
            "/user"
        )
        .build()

    /**
     * Configure admin API group - includes all endpoints for administrators.
     */
    @Bean
    open fun adminApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("admin-api")
        .displayName("Admin API (Internal)")
        .pathsToMatch("/**")
        .build()

    /**
     * Redirect root path to Swagger UI.
     */
    @Bean
    open fun indexRouter(): RouterFunction<ServerResponse> = router {
        GET("/") {
            ServerResponse
                .status(HttpStatus.FOUND)
                .location(URI.create("/swagger-ui/index.html"))
                .build()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(BaseConfig::class.java)

        /**
         * Trust manager that accepts all SSL certificates.
         *
         * ⚠️ WARNING: This disables SSL certificate validation!
         * Should ONLY be used in development/testing environments.
         * NEVER use this in production as it makes the application vulnerable to MITM attacks.
         */
        private class InsecureTrustManager : X509TrustManager {

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                // Accept all client certificates
            }

            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                // Accept all server certificates
            }
        }

        /**
         * Initializes an insecure SSL context that trusts all certificates for development/testing.
         *
         * ⚠️ SECURITY WARNING: This should be controlled by an environment variable
         * and NEVER enabled in production!
         */
        @JvmStatic
        fun initializeTrustAllCertificates() {
            val trustAllEnabled = System.getenv("TRUST_ALL_CERTIFICATES")?.toBoolean() ?: false
            val environment = System.getenv("ENVIRONMENT") ?: "production"

            if (trustAllEnabled) {
                if (environment.equals("production", ignoreCase = true)) {
                    LOG.error("CRITICAL SECURITY ERROR: Attempted to disable SSL verification in production!")
                    throw SecurityException("Cannot disable SSL certificate verification in production environment")
                }

                LOG.warn(
                    "⚠️  SSL CERTIFICATE VERIFICATION DISABLED ⚠️\n" +
                    "This is UNSAFE and should only be used in development/testing.\n" +
                    "Environment: {}", environment
                )

                try {
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf<TrustManager>(InsecureTrustManager()), SecureRandom())
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                    HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

                    LOG.warn("TrustAll SSL context initialized - ALL certificate validation bypassed!")
                } catch (e: Exception) {
                    LOG.error("Failed to initialize TrustAll SSL context: {}", e.message, e)
                    throw e
                }
            } else {
                LOG.info("SSL certificate verification enabled (secure mode)")
            }
        }
    }
}