package dev.vesta.core.knowledge

import dev.vesta.core.SourceType
import dev.vesta.core.UserId

@JvmInline
value class CollectionId(val value: Long)

/** 지식 베이스 (PROJECT.md K-01). 설명은 라우팅(D4)에 쓴다. */
data class Collection(
    val id: CollectionId,
    val ownerUserId: UserId,
    val name: String,
    val description: String?,
    /** 이 컬렉션의 임베딩을 만든 모델. 바꾸면 다시 색인해야 한다. */
    val embeddingModel: String,
)

/** 검색으로 찾은 근거 한 조각 (PROJECT.md 5.1, 11장 Evidence). */
data class Evidence(
    val source: SourceType,
    /** 문서 경로 또는 URL */
    val ref: String,
    val title: String,
    /** 사람이 읽는 위치. 예: "12–30행", "3쪽" */
    val location: String?,
    val text: String,
    val score: Double,
    /** 답변에서 인용할 번호 [n]. 컨텍스트 조립 때 붙인다. */
    val citation: Int? = null,
)

data class RetrieveOptions(
    val collections: List<CollectionId>,
    val limit: Int = 8,
)

/** 정보원별 검색기 (PROJECT.md 8.1). */
interface Retriever {
    val source: SourceType
    suspend fun retrieve(user: UserId, queries: List<String>, options: RetrieveOptions): List<Evidence>
}

/** 질문과 후보의 관련도를 다시 매긴다 (K-07). */
interface Reranker {
    suspend fun rerank(question: String, candidates: List<Evidence>, topN: Int): List<Evidence>
}
