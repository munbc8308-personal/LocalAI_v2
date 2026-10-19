package dev.vesta.channels

import java.security.MessageDigest
import java.security.SecureRandom

/** 메신저 계정 연결용 일회용 코드 (헷갈리는 0·O·1·I 제외, 8자 ≈ 40비트) */
object PairingCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(): String = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** 저장·비교용 해시. 대소문자·공백·하이픈 차이는 무시한다 */
    fun hash(code: String): String {
        val normalized = code.uppercase().filter { it.isLetterOrDigit() }
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
