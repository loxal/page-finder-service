// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

data class Result(var query: String = "", var results: List<FoundPage> = emptyList())
