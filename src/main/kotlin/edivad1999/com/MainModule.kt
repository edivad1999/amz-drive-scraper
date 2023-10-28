package edivad1999.com

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

fun Application.mainModule() {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val exposedLogger = loggerContext.getLogger("Exposed")
    exposedLogger.level = Level.OFF
    val client = HttpClient(CIO) {

        install(HttpTimeout) {
            requestTimeoutMillis = 1000 * 36000
            connectTimeoutMillis = 1000 * 36000
            socketTimeoutMillis = 1000 * 36000
        }
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.HEADERS
            filter {
                true

            }
        }


    }
    val config = {
        val configFile = File(System.getenv("configPath")).readText()

        val config = Json.decodeFromString<AuthConfig>(configFile)
        config
    }
    val repo = AmazonDriveRepository(client, config, coroutineContext)

    launch {
        repo.start()
    }


}
