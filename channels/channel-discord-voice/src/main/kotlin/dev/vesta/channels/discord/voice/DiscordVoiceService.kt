package dev.vesta.channels.discord.voice

import club.minnced.discord.jdave.interop.JDaveSessionFactory
import dev.vesta.channels.discord.DiscordVoiceControl
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.SpeechToText
import dev.vesta.core.media.TextToSpeech
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.audio.AudioReceiveHandler
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.UserAudio
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** 음성 채널 설정 (core의 DiscordConfig에서 옮겨 담는다) */
data class VoiceSettings(
    val wakeWords: List<String> = listOf("베스타", "vesta", "베스터"),
    val endSilenceMillis: Long = 800,
    val perHour: Int = 20,
    /** Apple Silicon용 libopus (JDA에 든 것은 x86_64뿐) */
    val opusLibrary: String = "/opt/homebrew/lib/libopus.dylib",
)

/**
 * Discord 음성 채널 대화 (PROJECT.md I-08, M19). 텍스트 봇과 같은 토큰으로 JDA가 Gateway를 하나 더 연다(음성 상태 인텐트만).
 * - 들어가기: 채팅에서 `@봇 !join` → 부른 사람이 있는 음성 채널로. 사람이 모두 나가면 저절로 나간다
 * - 듣기: 사람마다 소리를 모아 [VoiceSettings.endSilenceMillis] 조용하면 한 번 말한 것으로 → 16kHz WAV → 음성 인식
 * - 답하기: 사람이 둘 이상이면 호출어("베스타 …")로 시작한 말에만. 손님 계정(대화·웹 검색만)으로 묻고, 답을 문장 단위로 읽는다
 *   (공개 대화방과 같은 이유: 같은 채널 사람이 모두 듣는다). 말하는 중 "베스타 그만"이면 멈춘다
 * - 한 번에 답 하나 (서버마다), 한 사람이 한 시간에 [VoiceSettings.perHour]번까지
 */
class DiscordVoiceService(
    private val token: String,
    private val conversation: ConversationService,
    private val guest: suspend () -> UserContext,
    private val speechToText: SpeechToText,
    private val textToSpeech: TextToSpeech,
    private val settings: VoiceSettings,
    private val scope: CoroutineScope,
) : DiscordVoiceControl {
    private var jda: JDA? = null
    private val rooms = ConcurrentHashMap<String, Room>()
    /** 사람별 질문 시각·음성 인식 시각 (한 시간 창). 방을 다시 만들어도 이어진다 */
    private val questions = Budget(settings.perHour)
    private val transcriptions = Budget(settings.perHour * TRANSCRIBE_PER_QUESTION)

    /** JDA를 띄운다. libopus·DAVE를 쓸 수 없으면 false (음성 채널 없이 계속) */
    fun start(): Boolean = runCatching {
        check(club.minnced.opus.util.OpusLibrary.loadFrom(settings.opusLibrary)) { "libopus를 불러오지 못했습니다 (${settings.opusLibrary}, brew install opus)" }
        jda = JDABuilder.createDefault(token, GatewayIntent.GUILD_VOICE_STATES)
            .disableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_MESSAGE_TYPING,
                GatewayIntent.DIRECT_MESSAGES, GatewayIntent.DIRECT_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_TYPING, GatewayIntent.GUILD_EXPRESSIONS,
                GatewayIntent.SCHEDULED_EVENTS, GatewayIntent.GUILD_INVITES, GatewayIntent.GUILD_WEBHOOKS, GatewayIntent.GUILD_MODERATION)
            .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SOUNDBOARD_SOUNDS, CacheFlag.SCHEDULED_EVENTS)
            .enableCache(CacheFlag.VOICE_STATE)
            .setMemberCachePolicy(MemberCachePolicy.VOICE)
            .setAudioModuleConfig(AudioModuleConfig().withDaveSessionFactory(JDaveSessionFactory()))
            .addEventListeners(object : ListenerAdapter() {
                override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) = leaveIfAlone(event.guild)
            })
            .build()
        log.info { "Discord 음성 채널 준비 (JDA·DAVE)" }
        true
    }.getOrElse {
        log.warn { "Discord 음성 채널을 쓸 수 없습니다: ${it.message}" }
        false
    }

    fun stop() {
        rooms.values.forEach { it.close() }
        rooms.clear()
        jda?.shutdown()
    }

    override suspend fun join(guildId: String, userId: String): String = withContext(Dispatchers.IO) {
        val client = jda ?: return@withContext "음성 채널을 쓸 수 없습니다 (서버 로그 확인)"
        client.awaitReady()
        val guild = client.getGuildById(guildId) ?: return@withContext "이 서버의 음성 채널을 찾지 못했습니다"
        val channel = guild.getMemberById(userId)?.voiceState?.channel ?: return@withContext "먼저 음성 채널에 들어간 뒤 불러 주세요"
        // 다른 채널에서 사람들과 대화 중이면 그 채널 사람만 옮길 수 있다
        val current = guild.selfMember.voiceState?.channel
        if (current != null && current.id != channel.id && current.members.any { !it.user.isBot } && current.members.none { it.id == userId }) {
            return@withContext "지금 ${current.name}에서 대화 중입니다. 그 채널에 있는 사람만 옮길 수 있어요"
        }
        if (current?.id == channel.id && rooms.containsKey(guildId)) return@withContext "이미 ${channel.name}에 있습니다"
        rooms.remove(guildId)?.close()
        val room = Room(guild)
        rooms[guildId] = room
        guild.audioManager.receivingHandler = room
        guild.audioManager.sendingHandler = room.speaker
        guild.audioManager.openAudioConnection(channel)
        room.start()
        log.info { "Discord 음성 채널 들어감: ${guild.name} / ${channel.name}" }
        val call = if (channel.members.count { !it.user.isBot } > 1) "\"${settings.wakeWords.first()}\"로 시작해서 말해 주세요" else "편하게 말씀하세요"
        "🔊 ${channel.name}에 들어왔습니다. $call. 끝내려면 @봇 !leave"
    }

    override suspend fun leave(guildId: String, userId: String): String = withContext(Dispatchers.IO) {
        val guild = jda?.getGuildById(guildId) ?: return@withContext "음성 채널에 있지 않습니다"
        if (!rooms.containsKey(guildId)) return@withContext "음성 채널에 있지 않습니다"
        val current = guild.selfMember.voiceState?.channel
        if (current != null && current.members.none { it.id == userId }) return@withContext "${current.name}에 있는 사람만 내보낼 수 있어요"
        rooms.remove(guildId)?.close()
        "음성 채널에서 나왔습니다"
    }

    private fun leaveIfAlone(guild: Guild) {
        val room = rooms[guild.id] ?: return
        val channel = guild.selfMember.voiceState?.channel ?: return
        if (channel.members.none { !it.user.isBot }) {
            rooms.remove(guild.id)
            room.close()
            log.info { "Discord 음성 채널이 비어 나감: ${guild.name}" }
        }
    }

    /** 음성 채널 하나: 듣기(사람별 말 모으기) → 답하기(한 번에 하나) → 읽기 */
    private inner class Room(private val guild: Guild) : AudioReceiveHandler {
        val speaker = Speaker()
        private val turns = VoiceTurns(settings.endSilenceMillis)
        private var loop: Job? = null
        @Volatile private var answering: Job? = null
        private var session: SessionId? = null

        override fun canReceiveUser() = true

        override fun handleUserAudio(userAudio: UserAudio) {
            if (userAudio.user.isBot) return
            turns.onAudio(userAudio.user.id, userAudio.getAudioData(1.0), System.currentTimeMillis())
        }

        fun start() {
            loop = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    turns.finished(System.currentTimeMillis()).forEach { utterance ->
                        try {
                            onUtterance(utterance)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn(e) { "Discord 음성 처리 실패" }
                        }
                    }
                    delay(100)
                }
            }
        }

        private suspend fun onUtterance(utterance: Utterance) {
            // 호출어를 찾으려면 모든 말을 인식해야 한다: 사람마다 인식 횟수에도 상한을 둔다 (누구나 말할 수 있어 Mac이 붙잡히지 않게)
            if (!transcriptions.allow(utterance.userId)) return
            val text = speechToText.transcribe(Pcm.toWav16k(utterance.pcm)).trim()
            if (text.isBlank() || text in HALLUCINATIONS) return
            val humans = guild.selfMember.voiceState?.channel?.members?.count { !it.user.isBot } ?: 1
            val stripped = WakeWord.strip(text, settings.wakeWords)
            // 말하는 중: 호출어+"그만"이면 멈춘다. 다른 말은 듣지 않는다 (스피커 소리를 다시 듣지 않게)
            if (answering?.isActive == true || speaker.canProvide()) {
                if (stripped != null && WakeWord.isStop(stripped)) {
                    answering?.cancel()
                    speaker.clear()
                    log.info { "Discord 음성: 멈춤 (${utterance.userId})" }
                }
                return
            }
            val question = if (humans > 1) stripped ?: return else (stripped ?: text)
            if (question.isBlank()) { speaker.say("네, 말씀하세요."); return }
            if (!questions.allow(utterance.userId)) { speaker.say("한 시간에 ${settings.perHour}번까지 물어볼 수 있어요. 잠시 뒤에 불러 주세요."); return }
            log.info { "Discord 음성 질문 (${utterance.userId}, %.1f초): ${question.take(60)}".format(utterance.seconds) }
            answering = scope.launch { answer(question) }
        }

        /** 답이 생성되는 대로 문장마다 미리 합성해 순서대로 보낸다 */
        private suspend fun answer(question: String) = coroutineScope {
            val pieces = Channel<kotlinx.coroutines.Deferred<ByteArray?>>(Channel.UNLIMITED)
            launch {
                for (pending in pieces) pending.await()?.let(speaker::enqueue)
            }
            val text = StringBuilder()
            var spoken = 0
            var count = 0
            fun flush(finished: Boolean) {
                // 첫 조각은 첫 문장(빨리 시작), 그다음은 80자 이상씩 묶는다
                val end = Sentences.cut(text.toString(), spoken, finished, minChars = if (count == 0) 8 else LATER_CHARS)
                if (end > spoken) {
                    val piece = text.substring(spoken, end)
                    spoken = end
                    count++
                    if (piece.isNotBlank()) pieces.trySend(async {
                        runCatching { Pcm.trimSilence(textToSpeech.synthesize(piece, AudioFormat.PCM_48K_STEREO)) }.getOrNull()
                    })
                }
            }
            conversation.send(guest(), ChannelType.DISCORD, session, UserInput(question, spoken = true)).collect { event ->
                when (event) {
                    is AgentEvent.SessionReady -> session = event.sessionId
                    is AgentEvent.Token -> { text.append(event.text); flush(false) }
                    is AgentEvent.Error -> text.append("\n오류가 났어요.")
                    else -> Unit
                }
            }
            flush(true)
            pieces.close()
        }

        fun close() {
            loop?.cancel()
            answering?.cancel()
            speaker.clear()
            turns.clear()
            runCatching { guild.audioManager.closeAudioConnection() }
        }

        private fun Speaker.say(text: String) = scope.launch { runCatching { textToSpeech.synthesize(text, AudioFormat.PCM_48K_STEREO) }.getOrNull()?.let(::enqueue) }
    }

    /** 사람별 한 시간 횟수 (스레드 안전) */
    class Budget(private val perHour: Int, private val now: () -> Long = System::currentTimeMillis) {
        private val calls = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun allow(userId: String): Boolean {
            val t = now()
            val list = calls.getOrPut(userId) { ArrayDeque() }
            while (list.isNotEmpty() && t - list.first() > 3_600_000) list.removeFirst()
            if (list.size >= perHour) return false
            list.addLast(t)
            return true
        }
    }

    /** 보낼 소리 (48kHz 16비트 스테레오 BE). JDA가 20ms마다 3,840바이트씩 가져간다 */
    class Speaker : AudioSendHandler {
        private val queue = ConcurrentLinkedQueue<ByteArray>()
        private var current: ByteArray? = null
        private var position = 0

        fun enqueue(pcm: ByteArray) { if (pcm.isNotEmpty()) queue.add(pcm) }

        @Synchronized
        fun clear() { queue.clear(); current = null; position = 0 }

        @Synchronized
        override fun canProvide(): Boolean = current != null || queue.isNotEmpty()

        @Synchronized
        override fun provide20MsAudio(): ByteBuffer? {
            val frame = ByteArray(FRAME)
            var filled = 0
            while (filled < FRAME) {
                val data = current ?: queue.poll()?.also { current = it; position = 0 } ?: break
                val n = minOf(FRAME - filled, data.size - position)
                System.arraycopy(data, position, frame, filled, n)
                filled += n
                position += n
                if (position >= data.size) current = null
            }
            return if (filled == 0) null else ByteBuffer.wrap(frame)
        }

        override fun isOpus() = false

        private companion object {
            const val FRAME = 3_840
        }
    }

    private companion object {
        /** 첫 조각 뒤로 한 번에 합성할 최소 글자 수 */
        const val LATER_CHARS = 80
        /** 질문 하나에 인식할 수 있는 말 수 (호출어 없는 대화까지 인식하므로 넉넉히) */
        const val TRANSCRIBE_PER_QUESTION = 10
        /** 음성 인식 모델(Whisper)이 조용한 소리에서 자주 지어내는 말 (M19 시제품: 짧은 잡음이 "감사합니다."로) */
        val HALLUCINATIONS = setOf("감사합니다.", "감사합니다", "시청해 주셔서 감사합니다.", "MBC 뉴스 이덕영입니다.", "구독과 좋아요 부탁드립니다.")
    }
}
