package app.jibiki.model

import java.time.Instant

data class User(
        val id: Snowflake,
        val creation: Instant,
        val username: String,
        val email: String
)