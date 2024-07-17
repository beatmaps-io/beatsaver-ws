package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.dbo.ReviewReply
import io.beatmaps.common.rabbitOptional
import io.beatmaps.ws.wsJson
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ReplyDeleteDTO(val id: Int)

@Serializable
data class ReplyWebsocketDTO(
    val id: Int,
    val userId: Int,
    val reviewId: Int,
    val text: String,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun wrapRow(row: ResultRow): ReplyWebsocketDTO {
            return ReplyWebsocketDTO(
                row[ReviewReply.id].value,
                row[ReviewReply.userId].value,
                row[ReviewReply.reviewId].value,
                row[ReviewReply.text],
                row[ReviewReply.createdAt].toKotlinInstant(),
                row[ReviewReply.updatedAt].toKotlinInstant()
            )
        }
    }
}

suspend fun retrieveAndSendReplies(messageType: WebsocketMessageType, replyId: Int, holder: ChannelHolder) {
    transaction {
        ReviewReply
            .select {
                ReviewReply.id eq replyId and ReviewReply.deletedAt.isNull()
            }
            .firstOrNull()?.let { row ->
                ReplyWebsocketDTO.wrapRow(row)
            }
    }?.let { summary ->
        val wsMsg = wsJson.encodeToString(WebsocketMessage(messageType, summary))
        loopAndTerminateOnError(holder) {
            it.send(wsMsg)
        }
    }
}

fun Route.reviewRepliesWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.reviewReplyStream", (Int::class)) { routingKey, replyId ->
            val messageType = if (routingKey.endsWith(".created")) {
                WebsocketMessageType.REVIEW_REPLY_CREATE
            } else if (routingKey.endsWith(".updated")) {
                WebsocketMessageType.REVIEW_REPLY_UPDATE
            } else if (routingKey.endsWith(".deleted")) {
                WebsocketMessageType.REVIEW_REPLY_DELETE
            } else {
                null
            }

            when (messageType) {
                WebsocketMessageType.REVIEW_REPLY_CREATE,
                WebsocketMessageType.REVIEW_REPLY_UPDATE -> {
                    retrieveAndSendReplies(messageType, replyId, holder)
                }
                WebsocketMessageType.REVIEW_REPLY_DELETE -> {
                    val wsMsg = wsJson.encodeToString(
                        WebsocketMessage(
                            WebsocketMessageType.REVIEW_REPLY_DELETE,
                            ReplyDeleteDTO(replyId)
                        )
                    )
                    loopAndTerminateOnError(holder) {
                        it.send(wsMsg)
                    }
                }
                else -> { /* NO OP */ }
            }
        }
    }

    webSocket("/ws/review-replies") {
        websocketConnection(holder)
    }
}
