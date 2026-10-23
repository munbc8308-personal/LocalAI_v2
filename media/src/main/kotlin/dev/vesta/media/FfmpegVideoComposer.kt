package dev.vesta.media

import dev.vesta.core.media.MediaException
import dev.vesta.core.media.Motion
import dev.vesta.core.media.VideoComposer
import dev.vesta.core.media.VideoScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * 구성형 영상 (PROJECT.md G-02): 장면마다 그림을 천천히 확대·이동(zoompan)하고, 자막 PNG를 겹치고, 내레이션 길이에 맞춰 클립을 만든 뒤
 * 끝 화면("AI 생성")을 붙여 이어 붙인다. 배경 음악은 사용자가 넣은 파일만 작게 섞는다. 결과 mp4 메타데이터에도 "AI 생성"을 남긴다.
 * 입력은 모두 Vesta가 만든 파일이다(그림·합성 음성). 음악 파일은 형식을 확인하고 로컬 파일 외 프로토콜은 막는다
 */
class FfmpegVideoComposer(
    private val ffmpeg: Ffmpeg,
    private val fps: Int = 30,
    private val timeoutSeconds: Long = 600,
    /** 내레이션 앞뒤 여유 (초) */
    private val padSeconds: Double = 0.6,
    private val minSceneSeconds: Double = 2.5,
    private val endCardSeconds: Double = 2.0,
    private val musicVolume: Double = 0.12,
) : VideoComposer {
    override suspend fun compose(scenes: List<VideoScene>, width: Int, height: Int, title: String, music: ByteArray?, onScene: suspend (Int) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            if (scenes.isEmpty()) throw MediaException("장면이 없습니다")
            val dir = Files.createTempDirectory("vesta-video")
            try {
                // Path는 Iterable<Path>라 += 가 목록 더하기로 읽힌다: add로
                val clips = mutableListOf<Path>()
                scenes.forEachIndexed { i, scene ->
                    clips.add(clip(dir, "s$i", scene.image, scene.narration, SubtitleRenderer.subtitle(scene.subtitle, width, height), scene.motion, width, height, speed = scene.speed, moving = scene.clip))
                    onScene(i + 1)
                }
                val card = SubtitleRenderer.endCard(title, "AI 생성 · Vesta", width, height)
                clips.add(clip(dir, "end", card, null, null, null, width, height, fixed = endCardSeconds))
                val list = dir.resolve("list.txt")
                Files.writeString(list, clips.joinToString("\n") { "file '${it.fileName}'" } + "\n")
                val joined = dir.resolve("joined.mp4")
                exec(dir, listOf("-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", joined.toString()))
                val out = dir.resolve("out.mp4")
                val meta = listOf("-metadata", "title=${title.take(200)}", "-metadata", "comment=AI 생성 (Vesta)", "-movflags", "+faststart")
                val format = music?.let { Ffmpeg.sniff(it) }
                if (music != null && format != null) {
                    val track = dir.resolve("music.$format").also { it.writeBytes(music) }
                    exec(dir, listOf("-i", joined.toString(), "-stream_loop", "-1", "-f", format, "-i", track.toString(),
                        "-filter_complex", "[1:a]volume=$musicVolume[m];[0:a][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]",
                        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k") + meta + out.toString())
                } else {
                    exec(dir, listOf("-i", joined.toString(), "-c", "copy") + meta + out.toString())
                }
                out.readBytes()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    /**
     * 장면 클립 하나. 내레이션이 없으면 [fixed]초(또는 최소 길이)만큼 소리 없이. [speed]는 내레이션 빠르기(atempo).
     * [moving]이 있으면(생성형 클립, G-03) 그림 대신 그 영상을 화면에 맞춰 쓰고, 내레이션보다 짧으면 마지막 프레임을 늘린다
     */
    private fun clip(
        dir: Path, name: String, image: ByteArray, narration: ByteArray?, subtitle: ByteArray?, motion: Motion?, width: Int, height: Int,
        fixed: Double? = null, speed: Double = 1.0, moving: ByteArray? = null,
    ): Path {
        val img = dir.resolve("$name.png").also { it.writeBytes(image) }
        val audio = narration?.let { wav(dir, name, it) }
        val tempo = speed.coerceIn(0.5, 2.0)
        val seconds = fixed ?: maxOf(minSceneSeconds, (audio?.let { wavSeconds(it) / tempo } ?: 0.0) + padSeconds)
        val frames = (seconds * fps).toInt()
        val fade = minOf(0.4, seconds / 4)
        val z = when (motion) {
            Motion.ZOOM_IN -> "z='1+0.12*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
            Motion.ZOOM_OUT -> "z='1.12-0.12*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
            Motion.PAN_LEFT -> "z='1.12':x='(iw-iw/zoom)*(1-on/$frames)':y='ih/2-(ih/zoom/2)'"
            Motion.PAN_RIGHT -> "z='1.12':x='(iw-iw/zoom)*on/$frames':y='ih/2-(ih/zoom/2)'"
            null -> "z='1':x='0':y='0'"
        }
        val movie = moving?.let { dir.resolve("$name-clip.mp4").also { p -> p.writeBytes(it) } }
        val inputs = if (movie != null) mutableListOf("-f", "mov", "-i", movie.toString()) else mutableListOf("-f", "png_pipe", "-i", img.toString())
        val sub = subtitle?.let { dir.resolve("$name-sub.png").also { p -> p.writeBytes(it) } }
        if (sub != null) inputs += listOf("-f", "png_pipe", "-i", sub.toString())
        if (audio != null) inputs += listOf("-f", "wav", "-i", audio.toString())
        else inputs += listOf("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo")
        val audioIndex = if (sub != null) 2 else 1
        val num = { v: Double -> String.format(Locale.ROOT, "%.3f", v) }
        // 확대할 때 떨림이 덜하게 두 배로 키운 뒤 zoompan
        var video = if (movie != null) {
            "[0:v]scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,setsar=1,fps=$fps," +
                "tpad=stop_mode=clone:stop_duration=${num(seconds)},trim=duration=${num(seconds)},setpts=PTS-STARTPTS,format=yuv420p[base]"
        } else {
            "[0:v]scale=${width * 2}:${height * 2}:force_original_aspect_ratio=increase,crop=${width * 2}:${height * 2},setsar=1," +
                "zoompan=$z:d=$frames:s=${width}x$height:fps=$fps,format=yuv420p[base]"
        }
        video += if (sub != null) ";[base][1:v]overlay=0:0:format=auto,format=yuv420p[ov]" else ";[base]null[ov]"
        video += ";[ov]fade=t=in:st=0:d=${num(fade)},fade=t=out:st=${num(seconds - fade)}:d=${num(fade)}[v]"
        val tempoFilter = if (audio != null && tempo != 1.0) "atempo=${num(tempo)}," else ""
        val sound = "[$audioIndex:a]aresample=44100,aformat=channel_layouts=stereo,$tempoFilter" + "apad,atrim=0:${num(seconds)},afade=t=in:st=0:d=0.05,afade=t=out:st=${num(seconds - fade)}:d=${num(fade)}[a]"
        val out = dir.resolve("$name.mp4")
        exec(dir, inputs + listOf(
            "-filter_complex", "$video;$sound", "-map", "[v]", "-map", "[a]", "-t", num(seconds),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-r", fps.toString(),
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2", out.toString(),
        ))
        return out
    }

    /** 내레이션 → 44.1kHz 스테레오 WAV (길이를 재고 그대로 넣는다) */
    private fun wav(dir: Path, name: String, audio: ByteArray): Path {
        val format = Ffmpeg.sniff(audio) ?: throw MediaException("내레이션 소리 형식을 알 수 없습니다")
        val source = dir.resolve("$name-voice-in.$format").also { it.writeBytes(audio) }
        val target = dir.resolve("$name-voice.wav")
        exec(dir, listOf("-f", format, "-i", source.toString(), "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", "-f", "wav", target.toString()))
        return target
    }

    /** 16비트 스테레오 44.1kHz WAV의 길이 (머리 44바이트) */
    internal fun wavSeconds(wav: Path): Double = (Files.size(wav) - 44).coerceAtLeast(0) / (44_100.0 * 2 * 2)

    private fun exec(dir: Path, args: List<String>) {
        val log = dir.resolve("ffmpeg.log")
        ffmpeg.run(
            listOf(ffmpeg.binary, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe") + args,
            log, timeoutSeconds, "영상 만들기가 너무 오래 걸립니다", "영상을 합치지 못했습니다",
        )
    }
}
