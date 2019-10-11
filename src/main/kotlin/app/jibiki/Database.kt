package app.jibiki

import com.moji4j.MojiConverter
import com.moji4j.MojiDetector
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
class Database {
    @Autowired
    lateinit var client: DatabaseClient

    fun getEntries(word: String): Flux<Int> {
        val detector = MojiDetector()
        val converter = MojiConverter()

        return when {
            detector.hasKanji(word) -> client.execute().sql("SELECT DISTINCT entr FROM kanj WHERE txt ILIKE $1 LIMIT 50;").bind(1, word).map { row, _ -> row["entr"] as Int }.all()
            detector.hasKana(word) -> client.execute().sql("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE $1 LIMIT 50;").bind(1, word).map { row, _ -> row["entr"] as Int }.all()
            else -> client.execute().sql("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE $1 LIMIT 50;").bind(1, converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                    .switchIfEmpty(client.execute().sql("SELECT DISTINCT entr FROM gloss WHERE txt ILIKE $1 LIMIT 50;").bind(1, word).map { row, _ -> row["entr"] as Int }.all())
        }
    }
}