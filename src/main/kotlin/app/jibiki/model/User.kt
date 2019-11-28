package app.jibiki.model

import java.time.LocalDateTime

data class User(
        val snowflake: String? = null,
        val creation: LocalDateTime? = null,
        val username: String? = null,
        val email: String? = null
)