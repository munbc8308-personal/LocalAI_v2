package dev.vesta.channels

import dev.vesta.core.ChannelType
import dev.vesta.core.UserContext
import java.nio.file.Path

/** 메신저로 받은 문서 파일 처리 (PROJECT.md 6.5 파일 첨부). 구현은 app-server가 한다. */
interface ChannelFiles {
    /** 읽을 수 있는 형식인지 (.txt .md .pdf) */
    fun supports(fileName: String): Boolean

    /** 받은 파일을 `~/.vesta/inbox/<채널>/`에 저장한다 */
    suspend fun save(user: UserContext, channel: ChannelType, fileName: String, bytes: ByteArray): Path

    /** 파일 본문 (질문에 붙인다). 길면 [maxChars]에서 자른다 */
    suspend fun readText(path: Path, maxChars: Int): String

    /** 파일을 더할 수 있는 문서 컬렉션 이름 */
    suspend fun collections(user: UserContext): List<String>

    /** 컬렉션에 색인하고 결과를 한 줄로 돌려준다 */
    suspend fun addToCollection(user: UserContext, collection: String, path: Path): String
}
