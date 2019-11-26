package app.jibiki.model

import java.time.LocalDateTime

data class User(
        val snowflake: Snowflake,
        val creation: LocalDateTime,
        val username: String,
        val email: String
)