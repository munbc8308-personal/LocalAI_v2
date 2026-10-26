package dev.vesta.app

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.storage.VestaDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class DocumentUploadsTest : FunSpec({
    test("웹에서 문서 올리기 (M36 F-04): 본인 컬렉션만, 손님 거부, 이름·확장자 검사, 올리면 그 폴더를 색인, 지우면 파일도") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("mina")
        val knowledge = KnowledgeRepository(db.db)
        val root = createTempDirectory("users").toRealPath()
        val indexed = mutableListOf<String>()
        val uploads = DocumentUploads(knowledge, { c, folder -> indexed += "${c.name}:${folder.fileName}" }, { "bge-m3:567m" },
            { u -> if (u.userId == owner) "mina" else "other" }, root, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val mina = UserContext(owner, UserRole.MEMBER)
        val guest = UserContext(owner, UserRole.GUEST)

        uploads.create(guest, "집", null)!! shouldContain "손님"
        uploads.create(mina, "../x", null)!! shouldContain "이름은"
        uploads.create(mina, "우리집", "레시피") shouldBe null
        uploads.create(mina, "우리집", null)!! shouldContain "이미 있는"

        uploads.upload(mina, "우리집", "../evil.md", "x".toByteArray())!! shouldContain "파일 이름"
        uploads.upload(mina, "우리집", ".env.md", "x".toByteArray()) shouldNotBe null
        uploads.upload(mina, "우리집", "run.sh", "x".toByteArray()) shouldNotBe null
        uploads.upload(mina, "없는곳", "a.md", "x".toByteArray())!! shouldContain "컬렉션이 없습니다"
        uploads.upload(mina, "우리집", "레시피.md", "김치찌개: 돼지고기 300g".toByteArray()) shouldBe null
        Files.readString(root.resolve("mina/collections/우리집/레시피.md")) shouldBe "김치찌개: 돼지고기 300g"
        uploads.documents(mina, "우리집") shouldBe listOf("레시피.md")
        uploads.documents(UserContext(UserId(owner.value + 1), UserRole.MEMBER), "우리집") shouldBe null
        repeat(50) { if (indexed.isEmpty()) delay(20) }
        indexed shouldContain "우리집:우리집"

        uploads.deleteDocument(mina, "우리집", "레시피.md") shouldBe null
        uploads.documents(mina, "우리집") shouldBe emptyList()
        uploads.deleteCollection(mina, "우리집") shouldBe true
        Files.exists(root.resolve("mina/collections/우리집")) shouldBe false
        knowledge.findCollection(owner, "우리집") shouldBe null
    }
})
