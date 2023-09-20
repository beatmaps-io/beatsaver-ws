package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.inlineJackson
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket

fun Route.mapsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.mapStream", WebsocketMessage::class) { _, wsMsg ->
            val wsMsgStr = inlineJackson.writeValueAsString(wsMsg)
            loopAndTerminateOnError(holder) {
                it.send(wsMsgStr)
            }
        }
    }

    webSocket("/ws/maps") {
        websocketConnection(holder)
    }
}
