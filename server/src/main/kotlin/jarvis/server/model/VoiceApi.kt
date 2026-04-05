package jarvis.server.model

import kotlinx.serialization.Serializable

@Serializable
data class IatSignUrlSuccessResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: IatSignUrlResponseData,
    val traceId: String,
)

@Serializable
data class IatSignUrlErrorResponse(
    val code: Int,
    val message: String,
    val detail: String,
    val traceId: String,
)

@Serializable
data class IatSignUrlResponseData(
    val wsUrl: String,
    val expireAt: Long,
    val ttlSec: Long,
    val config: IatSessionConfig,
)

@Serializable
data class IatSessionConfig(
    val appId: String,
    val sampleRate: Int,
    val domain: String,
    val language: String,
    val accent: String,
    val audioEncoding: String,
)

@Serializable
data class TtsSignUrlSuccessResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: TtsSignUrlResponseData,
    val traceId: String,
)

@Serializable
data class TtsSignUrlErrorResponse(
    val code: Int,
    val message: String,
    val detail: String,
    val traceId: String,
)

@Serializable
data class TtsSignUrlResponseData(
    val wsUrl: String,
    val expireAt: Long,
    val ttlSec: Long,
    val config: TtsSessionConfig,
)

@Serializable
data class TtsSessionConfig(
    val appId: String,
    val vcn: String,
    val speed: Int,
    val pitch: Int,
    val volume: Int,
    val aue: String,
    val auf: String,
    val tte: String,
)
