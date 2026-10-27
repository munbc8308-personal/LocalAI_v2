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

    test("링크로 밖을 가리키는 폴더·파일은 쓰지도 색인하지도 않는다 (보안 검토)") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("mina")
        val knowledge = KnowledgeRepository(db.db)
        val root = createTempDirectory("users").toRealPath()
        val secret = createTempDirectory("other").toRealPath().also { Files.writeString(it.resolve("secret.md"), "비밀") }
        val indexed = mutableListOf<String>()
        val uploads = DocumentUploads(knowledge, { c, _ -> indexed += c.name }, { "m" }, { "mina" }, root, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val mina = UserContext(owner, UserRole.MEMBER)
        uploads.create(mina, "a", null) shouldBe null
        uploads.create(mina, "b", null) shouldBe null
        // 컬렉션 폴더 자체가 밖을 가리키는 링크
        Files.createDirectories(root.resolve("mina/collections"))
        Files.createSymbolicLink(root.resolve("mina/collections/a"), secret)
        uploads.upload(mina, "a", "x.md", "x".toByteArray())!! shouldContain "컬렉션이 없습니다"
        uploads.documents(mina, "a") shouldBe null
        uploads.deleteCollection(mina, "a")
        Files.exists(secret.resolve("secret.md")) shouldBe true
        // 폴더 안에 밖을 가리키는 파일 링크: 목록에 안 보이고, 색인하지 않는다
        uploads.documents(mina, "b") shouldBe emptyList()
        Files.createSymbolicLink(root.resolve("mina/collections/b/leak.md"), secret.resolve("secret.md"))
        uploads.documents(mina, "b") shouldBe emptyList()
        uploads.upload(mina, "b", "ok.md", "x".toByteArray()) shouldBe null
        delay(300)
        indexed shouldBe emptyList()
        uploads.deleteDocument(mina, "b", "leak.md")!! shouldContain "없는 문서"
        Files.exists(secret.resolve("secret.md")) shouldBe true
    }
})
