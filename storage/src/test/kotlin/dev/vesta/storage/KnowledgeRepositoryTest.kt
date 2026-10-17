package dev.vesta.storage

import dev.vesta.core.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class KnowledgeRepositoryTest : FunSpec({
    fun setup(): Pair<VestaDatabase, KnowledgeRepository> {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        return db to KnowledgeRepository(db.db)
    }

    test("문서를 저장하면 벡터·키워드로 찾을 수 있고, 다시 저장하면 이전 청크는 사라진다") {
        val (db, repo) = setup()
        val owner = db.ensureOwner("owner")
        val collection = repo.createCollection(owner, "docs", "설명", "embed")
        repo.replaceDocument(
            collection.id, "/a.md", "A", "h1",
            listOf(
                NewChunk("회의록은 매주 월요일에 작성한다", "1행", "회의", floatArrayOf(1f, 0f)),
                NewChunk("MLX는 Apple의 프레임워크다", "3행", null, floatArrayOf(0f, 1f)),
            ),
        )
        repo.keywordSearch(listOf(collection.id), "\"회의록\"*", 10) shouldHaveSize 1
        repo.loadVectors(listOf(collection.id)).map { it.vector.toList() } shouldBe listOf(listOf(1f, 0f), listOf(0f, 1f))

        repo.replaceDocument(collection.id, "/a.md", "A", "h2", listOf(NewChunk("새 내용", null, null, floatArrayOf(1f, 1f))))
        repo.keywordSearch(listOf(collection.id), "\"회의록\"*", 10).shouldBeEmpty()
        repo.stats(collection.id) shouldBe CollectionStats(1, 1)
        repo.findDocument(collection.id, "/a.md")?.contentHash shouldBe "h2"
    }

    test("컬렉션을 지우면 키워드 색인도 지워지고, 다른 사용자는 지울 수 없다") {
        val (db, repo) = setup()
        val owner = db.ensureOwner("owner")
        val collection = repo.createCollection(owner, "docs", null, "embed")
        repo.replaceDocument(collection.id, "/a.md", "A", "h", listOf(NewChunk("고양이 사진", null, null, floatArrayOf(1f))))

        repo.deleteCollection(UserId(owner.value + 1), collection.id) shouldBe false
        repo.deleteCollection(owner, collection.id) shouldBe true
        repo.listCollections(owner).shouldBeEmpty()
        repo.keywordSearch(listOf(collection.id), "\"고양이\"*", 10).shouldBeEmpty()
    }

    test("벡터 인코딩 왕복") {
        KnowledgeRepository.decode(KnowledgeRepository.encode(floatArrayOf(0.5f, -1f, 3f))).toList() shouldBe listOf(0.5f, -1f, 3f)
    }
})
