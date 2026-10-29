package dev.vesta.app

import dev.vesta.core.UserId
import dev.vesta.google.GoogleCredentials
import dev.vesta.google.GoogleTokenStore
import java.util.Base64

/**
 * Google 갱신 토큰 (M40 GW-02). 키체인 서비스 `dev.vesta.google`, 계정 `user-<id>`. 값은 이메일·토큰을 base64로 묶은 것
 * (키체인 명령에 빈 칸·따옴표 없이 넘기려고). 클라이언트 비밀은 같은 서비스의 `client-secret`
 */
object GoogleKeychain : GoogleTokenStore {
    const val SERVICE = "dev.vesta.google"
    const val CLIENT_SECRET = "client-secret"

    override fun read(user: UserId): GoogleCredentials? {
        val value = BotTokens.keychain(SERVICE, account(user)) ?: return null
        val parts = runCatching { String(Base64.getUrlDecoder().decode(value)) }.getOrNull()?.split('\n', limit = 2) ?: return null
        return parts.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { GoogleCredentials(it[0], it[1]) }
    }

    override fun write(user: UserId, credentials: GoogleCredentials): Boolean =
        BotTokens.writeKeychain(SERVICE, account(user), Base64.getUrlEncoder().withoutPadding().encodeToString("${credentials.email}\n${credentials.refreshToken}".toByteArray()))

    override fun delete(user: UserId) {
        BotTokens.deleteKeychain(SERVICE, account(user))
    }

    fun clientSecret(): String? = BotTokens.keychain(SERVICE, CLIENT_SECRET)

    private fun account(user: UserId) = "user-${user.value}"
}
