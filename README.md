# Page Finder Service

A multi-tenant website search-as-a-service backend. Crawl websites, import feeds, and provide full-text search and autocomplete over indexed content.

Production: `https://search.loxal.net`

## Tech Stack

- **Kotlin 2.3** on **JVM 25** (BellSoft Liberica)
- **Spring Boot 4** (WebFlux / Reactor Netty, HTTP/2, virtual threads)
- **Elasticsearch** — all persistence (no ORM, raw HTTP client)
- **crawler4j** — multi-threaded web crawler with BerkeleyDB JE frontier
- **Jsoup** / **Apache Tika** — HTML and PDF content extraction
- **Rome** — RSS/Atom feed parsing
- **SpringDoc OpenAPI** — Swagger UI at `/swagger-ui.html`

## Build & Run

Requires **Gradle 9.3.1** (no wrapper — use a system install, e.g. `nix run nixpkgs#gradle`).

```sh
# Build (from the lox/ root)
just build-page-finder

# Or directly
gradle --project-dir page-finder-service build

# Required environment variables
export SERVICE_SECRET="<uuid>"
export ADMIN_SITE_SECRET="<uuid>"
export ELASTICSEARCH_SERVICE="http://localhost:9200"
export EMAIL_SMTP_SECRET="<gmail-app-password>"

# Run
java --enable-native-access=ALL-UNNAMED -XX:+UseZGC \
  -jar build/libs/page-finder-service.jar \
  --spring.config.additional-location=config/local.yaml

# Tests
gradle --project-dir page-finder-service test

# JMH benchmarks
gradle --project-dir page-finder-service benchmark
```

The service listens on port **8001** (HTTP) and **7443** (HTTPS).

## Docker

```sh
./release.sh
```

Builds a linux/amd64 image and pushes to `docker.loxal.net/loxal/page-finder:latest`.

## API Overview

Interactive API docs are available at `/swagger-ui.html` and `/v3/api-docs` when the service is running.

### Sites

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/sites` | Create a new site (tenant) |
| `GET` | `/sites/{siteId}` | List page IDs for a site |
| `DELETE` | `/sites/{siteId}` | Clear all pages for a site |
| `GET` | `/sites/{siteId}/profile` | Fetch site profile |
| `PUT` | `/sites/{siteId}/profile` | Update site profile |

### Pages

| Method | Path | Description |
|--------|------|-------------|
| `PUT` | `/sites/{siteId}/pages` | Index a page |
| `GET` | `/sites/{siteId}/pages?url=` | Fetch a page by URL |
| `DELETE` | `/sites/{siteId}/pages/{pageId}` | Delete a page |

### Search & Autocomplete

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/sites/{siteId}/search?query=` | Full-text fuzzy search |
| `GET` | `/sites/{siteId}/autocomplete?query=` | Search-as-you-type suggestions |

### Crawling

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/sites/{siteId}/crawl` | Trigger a crawl |
| `POST` | `/sites/{siteId}/recrawl` | Re-crawl using stored configs |

### Feed Import

| Method | Path | Description |
|--------|------|-------------|
| `PUT` | `/sites/{siteId}/xml` | Import generic XML feed |
| `PUT` | `/sites/{siteId}/rss` | Import RSS/Atom feed |
| `POST` | `/sites/rss` | Import RSS feed into a new site |

## Architecture

- **Multi-tenancy**: each site gets a `siteId` (UUID) + `siteSecret` for authentication. Page IDs are SHA-256 of `siteId + url`.
- **Elasticsearch indexes**: `site-page` (documents), `site-profile` (tenant config), `svc-singletons` (crawl schedules).
- **Crawler**: respects `robots.txt`, supports sitemap-only mode, CSS-selector scoped body extraction, and PDF text extraction via Tika.
- **Standalone Gradle project** (formerly part of the `lox` Gradle monorepo). Sibling: `hutils-service`.
