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
    val sentiment: Int,
    val createdAt: String,
    val updatedAt: String,
    val curatedAt: String?,
) {
    companion object {
        fun wrapRow(row: ResultRow): ReviewWebsocketDTO {
            return ReviewWebsocketDTO(
                row[Review.userId].value,
                row[Review.mapId].value,
                row[Review.text],
                row[Review.sentiment],
                row[Review.createdAt].toKotlinInstant().toString(),
                row[Review.updatedAt].toKotlinInstant().toString(),
                row[Review.curatedAt]?.toKotlinInstant()?.toString(),
            )
        }
    }
}

suspend fun retrieveAndSendReview(messageType: WebsocketMessageType, reviewCompositeId: ReviewUpdateInfo, holder: ChannelHolder) {
    transaction {
        Review
            .select {
                (Review.mapId eq reviewCompositeId.mapId) and (Review.userId eq reviewCompositeId.userId)
            }
            .firstOrNull()?.let { row ->
                ReviewWebsocketDTO.wrapRow(row)
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
            retrieveAndSendReview(WebsocketMessageType.REVIEW_CREATE, reviewCompositeId, holder)
        }

        consumeAck("ws.reviewStream.updated", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_UPDATE, reviewCompositeId, holder)
        }

        consumeAck("ws.reviewStream.deleted", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            val wsMsg = inlineJackson.writeValueAsString(WebsocketMessage(WebsocketMessageType.REVIEW_DELETE, reviewCompositeId))
            loopAndTerminateOnError(holder) {
                it.send(wsMsg)
            }
        }

        consumeAck("ws.reviewStream.curated", (ReviewUpdateInfo::class)) { _, reviewCompositeId ->
            retrieveAndSendReview(WebsocketMessageType.REVIEW_CURATE, reviewCompositeId, holder)
        }
    }

    webSocket("/ws/reviews") {
        websocketConnection(holder)
    }
}
