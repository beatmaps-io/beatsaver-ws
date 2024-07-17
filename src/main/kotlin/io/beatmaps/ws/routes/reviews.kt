package io.beatmaps.ws.routes

import io.beatmaps.common.api.ReviewSentiment
import io.beatmaps.common.consumeAck
import io.beatmaps.common.dbo.Review
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Integer.toHexString

@Serializable
data class ReviewUpdateInfo(val mapId: Int, val userId: Int)

@Serializable
data class ReviewDeleteDTO(val mapId: String, val userId: Int)

@Serializable
data class ReviewWebsocketDTO(
    val userId: Int,
    val mapId: String,
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
                toHexString(row[Review.mapId].value),
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
            .selectAll()
            .where {
                (Review.mapId eq reviewCompositeId.mapId) and (Review.userId eq reviewCompositeId.userId) and Review.deletedAt.isNull()
            }
            .firstOrNull()?.let { row ->
                ReviewWebsocketDTO.wrapRow(row)
            }
    }?.let { summary ->
        holder.send(
            WebsocketMessage(messageType, summary)
        )
    }
}

fun Route.reviewsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.reviewStream", ReviewUpdateInfo.serializer()) { routingKey, reviewCompositeId ->
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
                    holder.send(
                        WebsocketMessage(
                            WebsocketMessageType.REVIEW_DELETE,
                            ReviewDeleteDTO(toHexString(reviewCompositeId.mapId), reviewCompositeId.userId)
                        )
                    )
                }
                else -> { /* NO OP */ }
            }
        }
    }

    webSocket("/ws/reviews") {
        websocketConnection(holder)
    }
}
