package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.media.MediaKind
import dev.vesta.core.session.NewMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory

class FileMediaStoreTest : FunSpec({
    test("본인 것만 읽고, 파일은 본인만 읽게 만들며, 메시지에 붙이면 목록에 함께 나온다") {
        val home = createTempDirectory("vesta")
        val db = VestaDatabase.open(home.resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "guest"; it[role] = dev.vesta.core.UserRole.GUEST } }.value.let(::UserId)
        val store = FileMediaStore(db.db, home.resolve("media"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

        val asset = store.save(owner, MediaKind.IMAGE, "image/png", png)
        store.load(owner, asset.id)!!.second.toList() shouldBe png.toList()
        store.load(other, asset.id) shouldBe null
        val file = Files.list(home.resolve("media").resolve(owner.value.toString())).findFirst().get()
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"

        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val mine = sessions.create(owner, ChannelType.WEB, "t")
        val theirs = sessions.create(other, ChannelType.WEB, "t")
        // 남의 세션 메시지에는 붙지 않는다
        messages.append(theirs.id, NewMessage(ChatRole.USER, "x", attachments = listOf(asset.id))).attachments.shouldBeEmpty()
        messages.append(mine.id, NewMessage(ChatRole.USER, "이 사진", attachments = listOf(asset.id))).attachments.single().id shouldBe asset.id
        messages.list(mine.id).single().attachments.single().mime shouldBe "image/png"
        messages.list(theirs.id).single().attachments.shouldBeEmpty()
        Path(file.toString()).toFile().exists() shouldBe true
    }

    test("갤러리: 만든 이미지만 최근 것부터, 프롬프트와 함께") {
        val home = createTempDirectory("vesta")
        val db = VestaDatabase.open(home.resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val store = FileMediaStore(db.db, home.resolve("media"))
        store.save(owner, MediaKind.IMAGE, "image/png", byteArrayOf(1))
        val first = store.save(owner, MediaKind.GENERATED, "image/png", byteArrayOf(2), "a cat")
        Thread.sleep(5)
        val second = store.save(owner, MediaKind.GENERATED, "image/png", byteArrayOf(3), "a dog")
        store.list(owner, MediaKind.GENERATED, 10).map { it.id to it.caption } shouldBe listOf(second.id to "a dog", first.id to "a cat")
        db.schemaVersion() shouldBe VestaDatabase.CURRENT_VERSION
    }
})
