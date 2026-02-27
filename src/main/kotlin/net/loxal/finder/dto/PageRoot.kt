// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PageRoot(var _source: FetchedPage? = null)