package io.beatmaps.ws.routes

import io.beatmaps.common.consumeAck
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.complexToBeatmap
import io.beatmaps.common.dbo.joinVersions
import io.beatmaps.common.rabbitOptional
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Integer.toHexString

@Serializable
data class VoteSummaryHex(val hash: String?, val mapId: String, val upvotes: Int, val downvotes: Int, val score: Double)

fun Route.votesWebsocket() {
    val holder = ChannelHolder()

    application.rabbitOptional {
        consumeAck("ws.voteStream", Int.serializer()) { _, mapId ->
            transaction {
                Beatmap
                    .joinVersions(false)
                    .selectAll()
                    .where {
                        (Beatmap.id eq mapId) and Beatmap.deletedAt.isNull()
                    }
                    .complexToBeatmap()
                    .firstOrNull()?.let {
                        val version = it.versions.values.firstOrNull()

                        VoteSummaryHex(
                            version?.hash,
                            toHexString(it.id.value),
                            it.upVotesInt,
                            it.downVotesInt,
                            it.score.toDouble()
                        )
                    }
            }?.let { summary ->
                holder.send(
                    WebsocketMessage(WebsocketMessageType.VOTE, summary)
                )
            }
        }
    }

    webSocket("/ws/votes") {
        websocketConnection(holder)
    }
}
