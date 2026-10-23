package dev.vesta.core

/** 모델 역할. 역할마다 다른 모델을 배정한다 (PROJECT.md 4.9). */
enum class ModelRole { GENERAL, CODE, EMBEDDING, RERANKER, VISION, IMAGE, STT, TTS }

/** 결정 모델(D1)이 고르는 에이전트 (PROJECT.md 4.8). */
enum class AgentType { CHAT, DOCS, WEB, CODE, TASK, PERSONAL, IMAGE, VIDEO }

enum class SourceType { LOCAL_DOCS, CODE, WEB }

/** 도구 위험 등급. READ 자동, NETWORK 설정에 따름, WRITE 승인, EXEC 매번 승인 (PROJECT.md 7.7). */
enum class RiskLevel { READ, NETWORK, WRITE, EXEC }

/** 멀티 유저 역할 (PROJECT.md 7.12). 단일 사용자 동안은 OWNER 한 명으로 동작한다. */
enum class UserRole { OWNER, MEMBER, GUEST }

@JvmInline
value class UserId(val value: Long)

@JvmInline
value class SessionId(val value: String)

data class UserContext(val userId: UserId, val role: UserRole)

/** 접속 경로 (PROJECT.md 6장). */
enum class ChannelType { CLI, WEB, TELEGRAM, DISCORD, SLACK, SIGNAL, MATRIX, OPENAI_API }
