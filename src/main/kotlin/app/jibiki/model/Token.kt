package app.jibiki.model

data class Token(
        val snowflake: String? = null,
        val token: String? = null,
        val expiry: Int? = null
)