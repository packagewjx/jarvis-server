package jarvis.server.model

import java.util.concurrent.ConcurrentHashMap

data class CachedRun(
    var requestId: String,
    val assistantMessageId: String,
    val events: MutableList<ChatEnvelope> = mutableListOf(),
    var completed: Boolean = false,
    var audioStarted: Boolean = false,
    var textSeq: Long = 0,
    val audioSeqByCard: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
)
