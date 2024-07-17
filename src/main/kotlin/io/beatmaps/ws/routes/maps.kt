package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.JsonElement

fun Route.mapsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.mapStream", JsonElement.serializer()) { _, wsMsg ->
            holder.send(wsMsg)
        }
    }

    webSocket("/ws/maps") {
        websocketConnection(holder)
    }
}
