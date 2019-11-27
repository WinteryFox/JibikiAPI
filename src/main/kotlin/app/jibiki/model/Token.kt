package app.jibiki.model

data class Token(
        val snowflake: Long? = null,
        val token: String? = null,
        val expiry: Int? = null
)