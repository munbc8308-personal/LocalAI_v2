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

    test("지우기: 본인 것만, 파일까지. 대화를 지우면 붙은 미디어와 영상·장면 재료도") {
        val home = createTempDirectory("vesta")
        val db = VestaDatabase.open(home.resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "guest"; it[role] = dev.vesta.core.UserRole.GUEST } }.value.let(::UserId)
        val store = FileMediaStore(db.db, home.resolve("media"))
        val image = store.save(owner, MediaKind.GENERATED, "image/png", byteArrayOf(1), "cat")
        store.delete(other, image.id) shouldBe false
        store.delete(owner, image.id) shouldBe true
        store.load(owner, image.id) shouldBe null
        Files.list(home.resolve("media").resolve(owner.value.toString())).count() shouldBe 0L

        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val chat = sessions.create(owner, ChannelType.WEB, "영상")
        val keep = sessions.create(owner, ChannelType.WEB, "다른")
        val upload = store.save(owner, MediaKind.IMAGE, "image/png", byteArrayOf(2))
        messages.append(chat.id, NewMessage(ChatRole.USER, "이걸로", attachments = listOf(upload.id)))
        val scene = store.save(owner, MediaKind.SCENE, "image/png", byteArrayOf(3))
        val voice = store.save(owner, MediaKind.AUDIO, "audio/mp4", byteArrayOf(4))
        val video = store.save(owner, MediaKind.VIDEO, "video/mp4", byteArrayOf(5))
        messages.append(chat.id, NewMessage(ChatRole.ASSISTANT, "영상", attachments = listOf(video.id)))
        val other1 = store.save(owner, MediaKind.GENERATED, "image/png", byteArrayOf(6))
        messages.append(keep.id, NewMessage(ChatRole.ASSISTANT, "그림", attachments = listOf(other1.id)))
        ExposedVideoProjectRepository(db.db).save(owner, chat.id.value, dev.vesta.core.media.VideoProject(video.id, "t", "9:16", 1.0,
            listOf(dev.vesta.core.media.ProjectScene("n", "i", dev.vesta.core.media.Motion.entries.first(), scene.id, voice.id))))

        store.deleteSession(other, chat.id.value) shouldBe 0
        store.deleteSession(owner, chat.id.value) shouldBe 2
        listOf(upload, scene, voice, video).forEach { store.load(owner, it.id) shouldBe null }
        store.load(owner, other1.id)!!.first.id shouldBe other1.id
        ExposedVideoProjectRepository(db.db).latest(owner, chat.id.value) shouldBe null
        Files.list(home.resolve("media").resolve(owner.value.toString())).count() shouldBe 1L
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
