package jarvis.server.gateway

import jarvis.server.model.ChannelSendRequest
import jarvis.server.model.ChannelStreamEvent
import kotlinx.coroutines.flow.Flow

interface ChannelGateway {
    suspend fun submit(request: ChannelSendRequest): String
    fun streamEvents(requestId: String): Flow<ChannelStreamEvent>
}
