package dev.vesta.cli

import dev.vesta.core.config.VestaPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 이 CLI 설치의 ID (RC-08). 대화 기록을 CLI 클라이언트(PC)마다 따로 둔다: 새 대화는 이 ID로 남고,
 * `-c`·`/resume`·`/sessions`는 이 클라이언트의 대화만 본다. 웹 대화 목록에는 CLI 클라이언트의 대화가 나오지 않는다
 */
internal object ClientId {
    private val FORMAT = Regex("^[A-Za-z0-9-]{8,64}$")

    fun get(path: Path = VestaPaths.default().home.resolve("client-id")): String {
        runCatching { Files.readString(path).trim() }.getOrNull()?.takeIf { FORMAT.matches(it) }?.let { return it }
        val id = UUID.randomUUID().toString()
        runCatching { Files.createDirectories(path.parent); Files.writeString(path, id) }
        return id
    }
}
