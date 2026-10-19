package dev.vesta.storage

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Duration

/** 로그인할 사용자와 저장된 비밀번호 해시 */
data class Credential(val user: UserContext, val name: String, val passwordHash: String?)

/** 쿠키로 찾은 웹 세션 */
data class WebSession(val user: UserContext, val name: String, val csrf: String)

/** 웹 UI 비밀번호·로그인 세션 저장 (PROJECT.md 6.3, U-02). 해시 계산은 호출하는 쪽(app-common PasswordHasher)이 한다. */
class WebAuthRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    /** 이름으로 사용자를 찾는다. 이름이 없으면 OWNER */
    suspend fun credential(name: String?): Credential? = io {
        val query = if (name == null) Users.selectAll().where { Users.role eq UserRole.OWNER } else Users.selectAll().where { Users.name eq name }
        query.firstOrNull()?.let { Credential(UserContext(UserId(it[Users.id].value), it[Users.role]), it[Users.name], it[Users.passwordHash]) }
    }

    suspend fun setPasswordHash(userId: UserId, hash: String) = io {
        Users.update({ Users.id eq userId.value }) { it[passwordHash] = hash }
        // 비밀번호를 바꾸면 이미 로그인한 기기도 모두 로그아웃
        WebSessions.deleteWhere { WebSessions.userId eq userId.value }
    }

    suspend fun createSession(userId: UserId, tokenHash: String, csrf: String, userAgent: String?, ttl: Duration) = io {
        val now = clock.instant()
        WebSessions.deleteWhere { expiresAt lessEq now }
        WebSessions.insert {
            it[WebSessions.tokenHash] = tokenHash
            it[WebSessions.userId] = userId.value
            it[WebSessions.csrf] = csrf
            it[WebSessions.userAgent] = userAgent?.take(255)
            it[createdAt] = now
            it[expiresAt] = now.plus(ttl)
        }
    }

    /** 만료되지 않은 세션만 */
    suspend fun findSession(tokenHash: String): WebSession? = io {
        val row = WebSessions.selectAll()
            .where { (WebSessions.tokenHash eq tokenHash) and (WebSessions.expiresAt greater clock.instant()) }
            .firstOrNull() ?: return@io null
        val user = Users.selectAll().where { Users.id eq row[WebSessions.userId] }.firstOrNull() ?: return@io null
        WebSession(UserContext(UserId(user[Users.id].value), user[Users.role]), user[Users.name], row[WebSessions.csrf])
    }

    suspend fun deleteSession(tokenHash: String) = io {
        WebSessions.deleteWhere { WebSessions.tokenHash eq tokenHash }
    }
}
