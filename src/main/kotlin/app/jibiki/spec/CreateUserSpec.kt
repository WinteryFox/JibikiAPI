package app.jibiki.spec

data class CreateUserSpec(
        val username: String,
        val email: String,
        val password: String
)