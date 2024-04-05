package io.beatmaps.ws

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.beatmaps.common.KotlinTimeModule
import io.beatmaps.common.db.setupDB
import io.beatmaps.common.genericQueueConfig
import io.beatmaps.common.installMetrics
import io.beatmaps.common.rabbitHost
import io.beatmaps.common.setupAMQP
import io.beatmaps.common.setupLogging
import io.beatmaps.ws.routes.websockets
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.locations.Locations
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import pl.jutupe.ktor_rabbitmq.RabbitMQ
import java.time.Duration

val port = System.getenv("LISTEN_PORT")?.toIntOrNull() ?: 3030
val host = System.getenv("LISTEN_HOST") ?: "127.0.0.1"

fun main() {
    setupLogging()
    setupDB()

    embeddedServer(Netty, port = port, host = host, module = Application::ws).start(wait = true)
}

data class ErrorResponse(val error: String)

fun Application.ws() {
    installMetrics()

    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            registerModule(JavaTimeModule())
            registerModule(KotlinTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }

    install(Locations)
    install(StatusPages) {
        exception<NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found"))
        }

        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, "Internal Server Error")
            throw cause
        }
    }

    if (rabbitHost.isNotEmpty()) {
        install(RabbitMQ) {
            setupAMQP {
                queueDeclare("ws.voteStream", true, false, false, genericQueueConfig)
                queueBind("ws.voteStream", "beatmaps", "voteupdate.*")

                queueDeclare("ws.reviewStream", true, false, false, genericQueueConfig)
                queueBind("ws.reviewStream", "beatmaps", "reviews.*")

                queueDeclare("ws.mapStream", true, false, false, genericQueueConfig)
                queueBind("ws.mapStream", "beatmaps", "ws.map.*")
            }
        }
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(60)
    }

    routing {
        websockets()

        staticResources("/static", "assets")
    }
}
