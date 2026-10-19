package dev.vesta.app

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 웹 UI 비밀번호 해시 (PROJECT.md 6.3, U-02). Argon2id, PHC 문자열 형식
 * `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>` (Base64, 패딩 없음)로 저장한다.
 */
object PasswordHasher {
    private const val MEMORY_KIB = 64 * 1024
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    /** 저장된 값에 적힌 매개변수로 다시 계산해 고정 시간 비교. 형식이 틀리면 false */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[5]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, m, t, p, expected.size), expected)
    }

    private fun derive(password: String, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        return ByteArray(length).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), it) }
    }
}
