package edivad1999.com

import io.ktor.server.cio.*
import io.ktor.server.engine.*

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        mainModule()
    }).start(wait = true)
}

