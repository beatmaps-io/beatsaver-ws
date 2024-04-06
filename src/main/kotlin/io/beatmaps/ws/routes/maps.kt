package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.jackson
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket

fun Route.mapsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.mapStream", Any::class) { _, wsMsg ->
            val wsMsgStr = jackson.writeValueAsString(wsMsg)
            loopAndTerminateOnError(holder) {
                it.send(wsMsgStr)
            }
        }
    }

    webSocket("/ws/maps") {
        websocketConnection(holder)
    }
}
