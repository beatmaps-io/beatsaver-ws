package io.beatmaps.ws.routes

import io.beatmaps.common.api.ReviewSentiment
import io.beatmaps.common.consumeAck
import io.beatmaps.common.dbo.Review
import io.beatmaps.common.inlineJackson
import io.beatmaps.common.json
import io.beatmaps.common.rabbitOptional
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
data class ReviewUpdateInfo(val mapId: Int, val userId: Int)

@Serializable
data class ReviewWebsocketDTO(
    val userId: Int,
    val mapId: Int,
    val text: String,
    val sentiment: ReviewSentiment,
    val createdAt: Instant,
    val updatedAt: Instant,
    val curatedAt: Instant? = null
) {
    companion object {
        fun wrapRow(row: ResultRow): ReviewWebsocketDTO {
            return ReviewWebsocketDTO(
                row[Review.userId].value,
                row[Review.mapId].value,
                row[Review.text],
                ReviewSentiment.fromInt(row[Review.sentiment]),
                row[Review.createdAt].toKotlinInstant(),
                row[Review.updatedAt].toKotlinInstant(),
                row[Review.curatedAt]?.toKotlinInstant()
            )
        }
    }
}

suspend fun retrieveAndSendReview(messageType: WebsocketMessageType, reviewCompositeId: ReviewUpdateInfo, holder: ChannelHolder) {
    transaction {
        Review
            .select {
                (Review.mapId eq reviewCompositeId.mapId) and (Review.userId eq reviewCompositeId.userId) and Review.deletedAt.isNull()
            }
            .firstOrNull()?.let { row ->
                ReviewWebsocketDTO.wrapRow(row)
            }
    }?.let { summary ->
        val wsMsg = json.encodeToString(WebsocketMessage(messageType, summary))
        loopAndTerminateOnError(holder) {
            it.send(wsMsg)
        }
    }
}

fun Route.reviewsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.reviewStream", (ReviewUpdateInfo::class)) { routingKey, reviewCompositeId ->
            val messageType = if (routingKey.endsWith(".created")) {
                WebsocketMessageType.REVIEW_CREATE
            } else if (routingKey.endsWith(".updated")) {
                WebsocketMessageType.REVIEW_UPDATE
            } else if (routingKey.endsWith(".curated")) {
                WebsocketMessageType.REVIEW_CURATE
            } else if (routingKey.endsWith(".deleted")) {
                WebsocketMessageType.REVIEW_DELETE
            } else {
                null
            }

            when (messageType) {
                WebsocketMessageType.REVIEW_CREATE,
                WebsocketMessageType.REVIEW_UPDATE,
                WebsocketMessageType.REVIEW_CURATE -> {
                    retrieveAndSendReview(messageType, reviewCompositeId, holder)
                }
                WebsocketMessageType.REVIEW_DELETE -> {
                    val wsMsg = inlineJackson.writeValueAsString(
                        WebsocketMessage(
                            WebsocketMessageType.REVIEW_DELETE,
                            reviewCompositeId
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

    webSocket("/ws/reviews") {
        websocketConnection(holder)
    }
}
