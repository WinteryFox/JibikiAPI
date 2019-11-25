package app.jibiki.model

data class User(
        val id: Snowflake,
        val username: String,
        val email: String
)