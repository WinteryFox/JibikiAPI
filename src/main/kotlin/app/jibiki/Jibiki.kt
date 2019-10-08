package app.jibiki

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Jibiki

val database = Database()

fun main(args: Array<String>) {
    runApplication<Jibiki>(*args)
}