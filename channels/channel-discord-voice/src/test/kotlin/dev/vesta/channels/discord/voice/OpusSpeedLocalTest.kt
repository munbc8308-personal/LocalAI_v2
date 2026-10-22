package dev.vesta.channels.discord.voice

import io.kotest.core.spec.style.FunSpec
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/** 진단: 20ms 조각 Opus 압축 시간 (M19: 46.6초 소리를 보내는 데 180초) */
class OpusSpeedLocalTest : FunSpec({
    test("opus 압축 속도").config(enabled = java.io.File("/opt/homebrew/lib/libopus.dylib").exists()) {
        println("load " + club.minnced.opus.util.OpusLibrary.loadFrom("/opt/homebrew/lib/libopus.dylib"))
        val opus = tomp2p.opuswrapper.Opus.INSTANCE
        val error = java.nio.IntBuffer.allocate(1)
        val encoder = opus.opus_encoder_create(48_000, 2, tomp2p.opuswrapper.Opus.OPUS_APPLICATION_AUDIO, error)
        val pcm = ShortBuffer.allocate(960 * 2)
        val out = ByteBuffer.allocateDirect(4096)
        val start = System.nanoTime()
        repeat(500) { pcm.rewind(); out.clear(); opus.opus_encode(encoder, pcm, 960, out, 4096) }
        println("500 frames %.1f ms (%.3f ms/frame)".format((System.nanoTime() - start) / 1e6, (System.nanoTime() - start) / 1e6 / 500))
    }
})
