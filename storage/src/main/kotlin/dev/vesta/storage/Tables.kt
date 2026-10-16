package dev.vesta.storage

import dev.vesta.core.UserRole
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 사용자. 멀티 유저는 M15에서 켜지만 `userId`는 M1부터 모든 사용자 데이터에 넣는다 (PROJECT.md 11장).
 * 나머지 테이블(Session, Message, ...)은 해당 마일스톤에서 추가한다.
 */
object Users : LongIdTable("users") {
    val name = varchar("name", 100).uniqueIndex()
    val role = enumerationByName("role", 16, UserRole::class)
    val passwordHash = varchar("password_hash", 255).nullable()
    val trainingConsent = bool("training_consent").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

/** 스키마 버전. 마이그레이션 순서 관리용. */
object SchemaVersion : LongIdTable("schema_version") {
    val version = integer("version")
    val appliedAt = timestamp("applied_at").defaultExpression(CurrentTimestamp)
}
