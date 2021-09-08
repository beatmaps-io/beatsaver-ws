package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.inlineJackson
import io.beatmaps.common.rabbitOptional
import io.ktor.routing.Route
import io.ktor.routing.application
import io.ktor.websocket.webSocket

fun Route.mapsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.mapStream", WebsocketMessage::class) { _, wsMsg ->
            loopAndTerminateOnError(holder) {
                it.send(inlineJackson.writeValueAsString(wsMsg))
            }
        }
    }

    webSocket("/ws/maps") {
        websocketConnection(holder)
    }
}
