// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

data class Subscription(val id: String, val plan: String, val paymentMethod: String, val siteId: String, val affiliate: String, val rawSubscription: Any)
