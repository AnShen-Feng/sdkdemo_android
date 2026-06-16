package com.partnerclient.sdk

import com.partnerclient.sdkcore.rtc.EndpointUrl
import com.partnerclient.sdkcore.rtc.MediaCredentials
import com.partnerclient.sdkcore.rtc.RtcCommandResult
import com.partnerclient.sdkcore.rtc.RtcParticipant
import com.partnerclient.sdkcore.rtc.RoomRole
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class DemoRtcBackend(
    gatewayBaseUrl: EndpointUrl,
    private val http: OkHttpClient = OkHttpClient(),
    private val apiPrefix: String = "/api/v1",
) {
    private val baseUrl: HttpUrl = requireNotNull(gatewayBaseUrl.value.toHttpUrlOrNull())
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun joinRoom(
        accessToken: String,
        roomId: String,
        displayName: String?,
        role: RoomRole?,
        ttlSeconds: Int,
    ): MediaCredentials {
        val endpoint = resolve(joinedPath(apiPrefix, "rooms", roomId, "join"))
        fun requestBody(): String {
            val obj = JSONObject()
            if (!displayName.isNullOrBlank()) obj.put("name", displayName)
            if (role != null) obj.put("role", role.name)
            obj.put("ttlSeconds", ttlSeconds)
            return obj.toString()
        }

        val initial = executeJson(
            Request.Builder()
                .url(endpoint)
                .post(requestBody().toRequestBody(jsonMediaType))
                .header("Authorization", accessToken.asBearerHeader())
                .header("Content-Type", "application/json")
                .build(),
            setOf(200, 201, 404),
        )
        val response = if (initial == HTTP_404_MARKER) {
            createRoom(accessToken, roomId)
            executeJson(
                Request.Builder()
                    .url(endpoint)
                    .post(requestBody().toRequestBody(jsonMediaType))
                    .header("Authorization", accessToken.asBearerHeader())
                    .header("Content-Type", "application/json")
                    .build(),
                setOf(200, 201),
            )
        } else {
            initial
        }
        return parseJoinResponse(response)
    }

    suspend fun listParticipants(accessToken: String, roomId: String): RtcCommandResult.ParticipantsListed {
        val response = executeJson(
            Request.Builder()
                .url(resolve(joinedPath(apiPrefix, "commands", "rooms", roomId, "participants")))
                .get()
                .header("Authorization", accessToken.asBearerHeader())
                .build(),
            setOf(200),
        )
        val array = JSONObject(response).requiredDataArray()
        val participants = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RtcParticipant(
                        identity = item.optString("identity"),
                        name = item.optString("name"),
                        isLocal = false,
                        isMicrophoneMuted = item.optBoolean("isMicrophoneMuted", item.optBoolean("muted", false)),
                    ),
                )
            }
        }
        return RtcCommandResult.ParticipantsListed(participants)
    }

    suspend fun muteParticipant(
        accessToken: String,
        roomId: String,
        identity: String,
        muted: Boolean,
    ): RtcCommandResult.ParticipantMuted {
        val body = JSONObject().put("muted", muted).toString()
        val response = executeJson(
            Request.Builder()
                .url(resolve(joinedPath(apiPrefix, "commands", "rooms", roomId, "participants", identity, "mute")))
                .post(body.toRequestBody(jsonMediaType))
                .header("Authorization", accessToken.asBearerHeader())
                .header("Content-Type", "application/json")
                .build(),
            setOf(200, 201),
        )
        val data = JSONObject(response).requiredDataObject()
        return RtcCommandResult.ParticipantMuted(identity = identity, muted = data.optBoolean("muted", muted))
    }

    suspend fun kickParticipant(accessToken: String, roomId: String, identity: String): RtcCommandResult.ParticipantKicked {
        executeJson(
            Request.Builder()
                .url(resolve(joinedPath(apiPrefix, "commands", "rooms", roomId, "participants", identity, "kick")))
                .post(JSONObject().toString().toRequestBody(jsonMediaType))
                .header("Authorization", accessToken.asBearerHeader())
                .header("Content-Type", "application/json")
                .build(),
            setOf(200, 201),
        )
        return RtcCommandResult.ParticipantKicked(identity)
    }

    suspend fun setParticipantRole(
        accessToken: String,
        roomId: String,
        identity: String,
        role: RoomRole,
    ): RtcCommandResult.ParticipantRoleChanged {
        val body = JSONObject().put("role", role.name).toString()
        val response = executeJson(
            Request.Builder()
                .url(resolve(joinedPath(apiPrefix, "commands", "rooms", roomId, "participants", identity, "setRole")))
                .post(body.toRequestBody(jsonMediaType))
                .header("Authorization", accessToken.asBearerHeader())
                .header("Content-Type", "application/json")
                .build(),
            setOf(200, 201),
        )
        val data = JSONObject(response).requiredDataObject()
        val returnedRole = data.optString("role").toRoomRoleOrNull() ?: role
        return RtcCommandResult.ParticipantRoleChanged(
            identity = identity,
            role = returnedRole,
            enforcedByKick = data.optBoolean("enforcedByKick", false),
        )
    }

    private suspend fun createRoom(accessToken: String, roomId: String) {
        val body = JSONObject().put("roomId", roomId).toString()
        executeJson(
            Request.Builder()
                .url(resolve(joinedPath(apiPrefix, "rooms")))
                .post(body.toRequestBody(jsonMediaType))
                .header("Authorization", accessToken.asBearerHeader())
                .header("Content-Type", "application/json")
                .build(),
            setOf(200, 201),
        )
    }

    private fun parseJoinResponse(json: String): MediaCredentials {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: throw IllegalStateException("missing data")
        val livekit = data.optJSONObject("livekit") ?: throw IllegalStateException("missing livekit")
        val url = livekit.optString("url").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing url")
        val token = livekit.optString("token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing token")
        return MediaCredentials(url = url, token = token)
    }

    private fun JSONObject.requiredDataObject(): JSONObject {
        return optJSONObject("data") ?: throw IllegalStateException("missing data")
    }

    private fun JSONObject.requiredDataArray(): JSONArray {
        return optJSONArray("data") ?: throw IllegalStateException("missing data")
    }

    private fun String.asBearerHeader(): String {
        val trimmed = trim()
        return if (trimmed.startsWith("Bearer ")) trimmed else "Bearer $trimmed"
    }

    private fun String.toRoomRoleOrNull(): RoomRole? {
        return runCatching { RoomRole.valueOf(this) }.getOrNull()
    }

    private suspend fun executeJson(request: Request, acceptCodes: Set<Int>): String {
        val response = http.newCall(request).await()
        response.use {
            if (it.code == 404 && acceptCodes.contains(404)) return HTTP_404_MARKER
            val body = it.body?.string().orEmpty()
            if (!acceptCodes.contains(it.code)) {
                throw IllegalStateException("http ${it.code} ${request.method} ${request.url}: $body")
            }
            return body
        }
    }

    private fun resolve(path: String): HttpUrl {
        val base = if (baseUrl.encodedPath.endsWith("/")) baseUrl else baseUrl.newBuilder().addPathSegment("").build()
        return base.resolve(path.trimStart('/')) ?: throw IllegalStateException("invalid url")
    }

    private fun joinedPath(vararg segments: String): String {
        return segments
            .flatMap { it.split('/') }
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private suspend fun okhttp3.Call.await(): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { cancel() } }
            enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        continuation.resume(response)
                    }
                },
            )
        }

    private companion object {
        const val HTTP_404_MARKER = "__HTTP_404__"
    }
}
