package app.jibiki.model

import java.time.LocalDateTime

data class Token(
        val token: String,
        val expiry: LocalDateTime
)