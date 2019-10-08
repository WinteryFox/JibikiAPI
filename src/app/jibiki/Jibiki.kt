package app.jibiki

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Jibiki

fun main(args: Array<String>) {
    runApplication<Jibiki>(*args)
}