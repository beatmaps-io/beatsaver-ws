package io.beatmaps.ws.routes

import io.beatmaps.common.json
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

enum class WebsocketMessageType {
    MAP_UPDATE,
    MAP_DELETE,
    VOTE,
    REVIEW_CREATE,
    REVIEW_UPDATE,
    REVIEW_DELETE,
    REVIEW_CURATE,
    REVIEW_REPLY_CREATE,
    REVIEW_REPLY_UPDATE,
    REVIEW_REPLY_DELETE
}

@Serializable
data class WebsocketMessage<T>(val type: WebsocketMessageType, val msg: T)
data class ChannelHolder(var channels: List<Channel<String>> = listOf()) {
    suspend inline fun <reified T> send(msg: T) = send(serializer<T>(), msg)

    suspend fun <T> send(serializer: KSerializer<T>, msg: T) {
        val msgStr = json.encodeToString(serializer, msg)
        loopAndTerminateOnError {
            it.send(msgStr)
        }
    }

    private suspend fun loopAndTerminateOnError(block: suspend (Channel<String>) -> Unit) {
        channels.forEach {
            try {
                block(it)
            } catch (e: Exception) {
                e.printStackTrace()
                channels = channels.minus(it)
                it.close()
            }
        }
    }
}

suspend fun DefaultWebSocketServerSession.websocketConnection(holder: ChannelHolder) {
    Channel<String>(10).also {
        holder.channels = holder.channels.plus(it)
    }.let { channel ->
        try {
            launch(Dispatchers.Default) {
                channel.consumeEach {
                    outgoing.send(Frame.Text(it))
                }
            }

            incoming.consumeEach {
                // This will block while the socket is open
                // When closed the finally automatically shuts everything down
                // println("Received: $it")
            }
        } finally {
            holder.channels = holder.channels.minus(channel)
            channel.close()
        }
    }
}

fun Route.websockets() {
    mapsWebsocket()
    votesWebsocket()
    reviewsWebsocket()
    reviewRepliesWebsocket()
}
