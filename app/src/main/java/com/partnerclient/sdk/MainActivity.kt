package com.partnerclient.sdk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.partnerclient.sdkcore.rtc.ConnectParams
import com.partnerclient.sdkcore.rtc.EndpointUrl
import com.partnerclient.sdkcore.rtc.MediaCredentialsProvider
import com.partnerclient.sdkcore.rtc.RtcClient
import com.partnerclient.sdkcore.rtc.RtcConfig
import com.partnerclient.sdkcore.rtc.RtcEvent
import com.partnerclient.sdkcore.rtc.RtcRoomState
import com.partnerclient.sdkcore.rtc.RoomRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var client: RtcClient? = null
    private var backend: DemoRtcBackend? = null
    private var backendToken: String? = null
    private var activeRoomId: String? = null

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var micButton: Button
    private lateinit var refreshButton: Button
    private lateinit var kickButton: Button
    private lateinit var muteTargetButton: Button
    private lateinit var unmuteTargetButton: Button
    private lateinit var setHostButton: Button
    private lateinit var setSpeakerButton: Button
    private lateinit var setListenerButton: Button

    private lateinit var backendUrlInput: EditText
    private lateinit var roomIdInput: EditText
    private lateinit var userNameInput: EditText
    private lateinit var authTokenInput: EditText
    private lateinit var targetIdentityInput: EditText
    private lateinit var roleGroup: RadioGroup
    private lateinit var participantsRecyclerView: RecyclerView
    private lateinit var participantsAdapter: ParticipantsAdapter

    private var isConnected = false
    private var currentHostIdentity: String? = null
    private var localRoomRole: RoomRole = RoomRole.SPEAKER
    private var isMicMuted = false

    private var stateJob: Job? = null
    private var participantsJob: Job? = null
    private var eventsJob: Job? = null
    private var pendingMicMuted: Boolean? = null

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingMicMuted
        pendingMicMuted = null
        if (!granted) {
            toast(getString(R.string.toast_mic_permission_required))
            logE("麦克风权限被拒绝")
            return@registerForActivityResult
        }
        if (pending != null) {
            setMicMuted(pending)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupViews()
    }

    private fun setupViews() {
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        micButton = findViewById(R.id.micButton)
        refreshButton = findViewById(R.id.refreshButton)
        kickButton = findViewById(R.id.kickButton)
        muteTargetButton = findViewById(R.id.muteTargetButton)
        unmuteTargetButton = findViewById(R.id.unmuteTargetButton)
        setHostButton = findViewById(R.id.setHostButton)
        setSpeakerButton = findViewById(R.id.setSpeakerButton)
        setListenerButton = findViewById(R.id.setListenerButton)

        backendUrlInput = findViewById(R.id.backendUrlInput)
        roomIdInput = findViewById(R.id.roomIdInput)
        userNameInput = findViewById(R.id.userNameInput)
        authTokenInput = findViewById(R.id.authTokenInput)
        targetIdentityInput = findViewById(R.id.targetIdentityInput)
        roleGroup = findViewById(R.id.roleGroup)
        participantsRecyclerView = findViewById(R.id.participantsRecyclerView)

        participantsAdapter = ParticipantsAdapter()
        participantsRecyclerView.layoutManager = LinearLayoutManager(this)
        participantsRecyclerView.adapter = participantsAdapter

        connectButton.setOnClickListener { if (isConnected) disconnect() else connect() }
        micButton.setOnClickListener { setMicMuted(!isMicMuted) }
        refreshButton.setOnClickListener { refreshParticipants() }
        kickButton.setOnClickListener { withTarget { kickParticipant(it) } }
        muteTargetButton.setOnClickListener { withTarget { muteParticipant(it, true) } }
        unmuteTargetButton.setOnClickListener { withTarget { muteParticipant(it, false) } }
        setHostButton.setOnClickListener { withTarget { setRole(it, RoomRole.HOST) } }
        setSpeakerButton.setOnClickListener { withTarget { setRole(it, RoomRole.SPEAKER) } }
        setListenerButton.setOnClickListener { withTarget { setRole(it, RoomRole.LISTENER) } }

        recreateClientFromInput() ?: return
        observeRtcEvents()
    }

    private fun recreateClientFromInput(): RtcClient? {
        val endpoint = runCatching { EndpointUrl.parse(backendUrlInput.text.toString().trim()) }.getOrElse {
            toast(getString(R.string.toast_invalid_url))
            logE("BE 地址不合法", it)
            return null
        }
        backend = DemoRtcBackend(endpoint)
        client?.close()
        client = RtcClient.create(this, RtcConfig())
        return client
    }

    private fun observeRtcEvents() {
        val activeClient = client ?: return
        stateJob?.cancel()
        participantsJob?.cancel()
        eventsJob?.cancel()
        stateJob = lifecycleScope.launch {
            activeClient.state.collectLatest { state ->
                logI("房间状态更新: $state")
                updateUiState(state)
            }
        }
        participantsJob = lifecycleScope.launch {
            activeClient.participants.collectLatest { participants ->
                if (localRoomRole == RoomRole.HOST) {
                    val localIdentity = participants.firstOrNull { it.isLocal }?.identity
                    if (!localIdentity.isNullOrBlank()) currentHostIdentity = localIdentity
                }
                participantsAdapter.submitList(participants)
                participantsAdapter.setHostIdentity(currentHostIdentity)
                logI("参与者刷新, count=${participants.size}, host=$currentHostIdentity")
            }
        }
        eventsJob = lifecycleScope.launch {
            activeClient.events.collectLatest { event ->
                when (event) {
                    is RtcEvent.ParticipantJoined -> {
                        logI("事件: ParticipantJoined identity=${event.identity}")
                        toast(getString(R.string.toast_participant_joined, event.identity))
                    }
                    is RtcEvent.ParticipantLeft -> {
                        logI("事件: ParticipantLeft identity=${event.identity}")
                        toast(getString(R.string.toast_participant_left, event.identity))
                    }
                    is RtcEvent.Error -> {
                        logE("事件: Error message=${event.message}")
                        toast(getString(R.string.toast_error, event.message))
                    }
                }
            }
        }
    }

    private fun updateUiState(state: RtcRoomState) {
        when (state) {
            RtcRoomState.DISCONNECTED -> {
                statusText.text = getString(R.string.status_disconnected)
                connectButton.text = getString(R.string.connect)
                connectButton.isEnabled = true
                isConnected = false
            }
            RtcRoomState.CONNECTING -> {
                statusText.text = getString(R.string.status_connecting)
                connectButton.isEnabled = false
            }
            RtcRoomState.CONNECTED -> {
                statusText.text = getString(R.string.status_connected)
                connectButton.text = getString(R.string.disconnect)
                connectButton.isEnabled = true
                isConnected = true
            }
            RtcRoomState.ERROR -> {
                statusText.text = getString(R.string.status_error)
                connectButton.text = getString(R.string.connect)
                connectButton.isEnabled = true
                isConnected = false
            }
        }
        micButton.text = if (isMicMuted) getString(R.string.unmute_self) else getString(R.string.mute_self)
    }

    /**
     * 建立连接：根据输入框中的 BE 地址、房间、昵称和角色发起连接。
     * 这里会重建 SDK 客户端，确保 BE 地址每次以最新输入为准。
     */
    private fun connect() {
        if (authTokenInput.text.toString().trim().isBlank()) {
            toast(getString(R.string.toast_required_auth_token))
            logE("缺少 WebRTC 访问令牌，网关请求将返回 401")
            return
        }
        val activeClient = recreateClientFromInput() ?: return
        val activeBackend = backend ?: return
        observeRtcEvents()
        val roomId = roomIdInput.text.toString().trim().ifBlank { getString(R.string.default_room_id) }
        val userName = userNameInput.text.toString().trim().ifBlank { "User-${System.currentTimeMillis() % 10000}" }
        val role = when (roleGroup.checkedRadioButtonId) {
            R.id.roleHostRadio -> RoomRole.HOST
            R.id.roleListenerRadio -> RoomRole.LISTENER
            else -> RoomRole.SPEAKER
        }
        localRoomRole = role
        lifecycleScope.launch {
            try {
                val accessToken = accessTokenValue()
                logI("宿主自行发起 join HTTP 请求 roomId=$roomId, user=$userName, role=$role")
                backendToken = accessToken
                activeRoomId = roomId
                val params = ConnectParams(roomId = roomId, displayName = userName, role = role)
                activeClient.connect(
                    params = params,
                    credentialsProvider = MediaCredentialsProvider { requestParams ->
                        activeBackend.joinRoom(
                            accessToken = accessToken,
                            roomId = requestParams.roomId,
                            displayName = requestParams.displayName,
                            role = requestParams.role,
                            ttlSeconds = DEFAULT_TTL_SECONDS,
                        )
                    },
                )
                isMicMuted = false
                if (role != RoomRole.HOST) {
                    currentHostIdentity = null
                    participantsAdapter.setHostIdentity(null)
                }
            } catch (e: Exception) {
                logE("连接失败", e)
                toast(getString(R.string.toast_connect_failed, e.message))
            }
        }
    }

    /** 断开房间连接并清理本地展示状态。 */
    private fun disconnect() {
        val activeClient = client ?: return
        lifecycleScope.launch {
            logI("主动断开连接")
            activeClient.disconnect()
            backendToken = null
            activeRoomId = null
            isMicMuted = false
            currentHostIdentity = null
            participantsAdapter.setHostIdentity(null)
        }
    }

    /** 调用 SDK 的本地麦克风开关能力。 */
    private fun setMicMuted(muted: Boolean) {
        val activeClient = client ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicMuted = muted
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        lifecycleScope.launch {
            runCatching { activeClient.setMicrophoneMuted(muted) }
                .onSuccess {
                    isMicMuted = muted
                    updateUiState(if (isConnected) RtcRoomState.CONNECTED else RtcRoomState.DISCONNECTED)
                    logI("本地麦克风状态: muted=$muted")
                }
                .onFailure { logE("本地麦克风操作失败", it) }
        }
    }

    /** 调用业务后端命令接口：刷新参与者列表。 */
    private fun refreshParticipants() {
        val activeBackend = backend ?: return
        val token = backendToken ?: return
        val roomId = activeRoomId ?: return
        lifecycleScope.launch {
            runCatching { activeBackend.listParticipants(token, roomId) }
                .onSuccess { result ->
                    logI("命令成功: listParticipants size=${result.participants.size}")
                    participantsAdapter.submitList(result.participants)
                    participantsAdapter.setHostIdentity(currentHostIdentity)
                }
                .onFailure { logE("命令失败: listParticipants", it) }
        }
    }

    /** 调用业务后端命令接口：踢出目标参与者。 */
    private fun kickParticipant(identity: String) {
        val activeBackend = backend ?: return
        val token = backendToken ?: return
        val roomId = activeRoomId ?: return
        lifecycleScope.launch {
            runCatching { activeBackend.kickParticipant(token, roomId, identity) }
                .onSuccess { logI("命令成功: kick identity=$identity") }
                .onFailure { logE("命令失败: kick identity=$identity", it) }
        }
    }

    /** 调用业务后端命令接口：静音/取消静音目标参与者。 */
    private fun muteParticipant(identity: String, muted: Boolean) {
        val activeBackend = backend ?: return
        val token = backendToken ?: return
        val roomId = activeRoomId ?: return
        lifecycleScope.launch {
            runCatching { activeBackend.muteParticipant(token, roomId, identity, muted) }
                .onSuccess { logI("命令成功: mute identity=$identity muted=$muted") }
                .onFailure { logE("命令失败: mute identity=$identity muted=$muted", it) }
        }
    }

    /** 调用业务后端命令接口：设置目标角色并同步本地主持人标识。 */
    private fun setRole(identity: String, role: RoomRole) {
        val activeBackend = backend ?: return
        val token = backendToken ?: return
        val roomId = activeRoomId ?: return
        lifecycleScope.launch {
            runCatching { activeBackend.setParticipantRole(token, roomId, identity, role) }
                .onSuccess {
                    logI("命令成功: setRole identity=$identity role=$role")
                    if (role == RoomRole.HOST) {
                        currentHostIdentity = identity
                        participantsAdapter.setHostIdentity(currentHostIdentity)
                    } else if (currentHostIdentity == identity) {
                        currentHostIdentity = null
                        participantsAdapter.setHostIdentity(null)
                    }
                }
                .onFailure { logE("命令失败: setRole identity=$identity role=$role", it) }
        }
    }

    private fun withTarget(block: (String) -> Unit) {
        val target = targetIdentityInput.text.toString().trim()
        if (target.isBlank()) {
            toast(getString(R.string.toast_required_target))
            return
        }
        block(target)
    }

    private fun accessTokenValue(): String {
        return authTokenInput.text.toString().trim()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun logI(message: String) {
        Log.i("RTC_DEMO", "[INFO] $message")
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        Log.e("RTC_DEMO", "[ERROR] $message", throwable)
    }

    override fun onDestroy() {
        stateJob?.cancel()
        participantsJob?.cancel()
        eventsJob?.cancel()
        client?.close()
        client = null
        backend = null
        backendToken = null
        activeRoomId = null
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 3600
    }
}
