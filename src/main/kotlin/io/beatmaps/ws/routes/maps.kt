package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.json
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

fun Route.mapsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.mapStream", JsonElement.serializer()) { _, wsMsg ->
            val wsMsgStr = json.encodeToString(wsMsg)
            loopAndTerminateOnError(holder) {
                it.send(wsMsgStr)
            }
        }
    }

    webSocket("/ws/maps") {
        websocketConnection(holder)
    }
}
