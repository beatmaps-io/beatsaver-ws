package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.dbo.Review
import io.beatmaps.common.inlineJackson
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ReviewUpdateInfo(val mapId: Int, val userId: Int)

@Serializable
data class ReviewWebsocketDAO(
    val userId: Int,
    val mapId: Int,
    val text: String,
    val sentiment: Int,
    val createdAt: String,
    val updatedAt: String,
    val curatedAt: String?,
    val deletedAt: String?
)

suspend fun retrieveAndSendReview(messageType: WebsocketMessageType, reviewCompositeId: ReviewUpdateInfo, holder: ChannelHolder, onlyNonDeleted: Boolean) {
    transaction {
        Review
            .select {
                (Review.mapId eq reviewCompositeId.mapId) and (Review.userId eq reviewCompositeId.userId) and if (onlyNonDeleted) {
                    Review.deletedAt.isNull()
                } else {
                    Op.TRUE
                }
            }
            .firstOrNull()?.let {
                ReviewWebsocketDAO(
                    userId = it[Review.userId].value,
                    mapId = it[Review.mapId].value,
                    text = it[Review.text],
                    sentiment = it[Review.sentiment],
                    createdAt = it[Review.createdAt].toKotlinInstant().toString(),
                    updatedAt = it[Review.updatedAt].toKotlinInstant().toString(),
                    curatedAt = it[Review.curatedAt]?.toKotlinInstant()?.toString(),
                    deletedAt = it[Review.deletedAt]?.toKotlinInstant()?.toString()
                )
            }
    }?.let { summary ->
        val wsMsg = inlineJackson.writeValueAsString(WebsocketMessage(messageType, summary))
        loopAndTerminateOnError(holder) {
            it.send(wsMsg)
        }
    }
}

fun Route.reviewsWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.reviewStream.created", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_CREATED, reviewCompositeId, holder, true)
        }

        consumeAck("ws.reviewStream.updated", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_UPDATED, reviewCompositeId, holder, true)
        }

        consumeAck("ws.reviewStream.deleted", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_DELETED, reviewCompositeId, holder, false)
        }

        consumeAck("ws.reviewStream.curated", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_CURATED, reviewCompositeId, holder, true)
        }
    }

    webSocket("/ws/reviews") {
        websocketConnection(holder)
    }
}
