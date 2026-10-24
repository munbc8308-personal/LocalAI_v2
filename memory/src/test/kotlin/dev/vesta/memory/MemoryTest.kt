package dev.vesta.memory

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.memory.Memory
import dev.vesta.core.memory.MemoryKind
import dev.vesta.core.memory.MemoryRepository
import dev.vesta.core.memory.MemorySource
import dev.vesta.core.memory.StoredMemory
import dev.vesta.core.memory.UserQuestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/** 메모리 저장소 (테스트용) */
class MemoryStore : MemoryRepository {
    val items = mutableListOf<Triple<Memory, FloatArray?, String?>>()
    val questions = mutableListOf<UserQuestion>()
    var at = 0L
    private var next = 1L
    private var clock = 0L

    override suspend fun add(user: UserId, text: String, kind: MemoryKind, source: MemorySource, vector: FloatArray?, model: String?): Memory {
        val now = Instant.ofEpochSecond(clock++)
        val m = Memory(next++, user, text, kind, source, now, now)
        items += Triple(m, vector, model)
        return m
    }
    override suspend fun update(user: UserId, id: Long, text: String, kind: MemoryKind?, vector: FloatArray?, model: String?): Boolean {
        val i = items.indexOfFirst { it.first.id == id && it.first.user == user }.takeIf { it >= 0 } ?: return false
        val old = items[i].first
        items[i] = Triple(old.copy(text = text, kind = kind ?: old.kind, updatedAt = Instant.ofEpochSecond(clock++)), vector, model)
        return true
    }
    override suspend fun revector(id: Long, vector: FloatArray, model: String) {
        val i = items.indexOfFirst { it.first.id == id }
        items[i] = Triple(items[i].first, vector, model)
    }
    override suspend fun list(user: UserId, limit: Int) = items.map { it.first }.filter { it.user == user }.sortedByDescending { it.updatedAt }.take(limit)
    override suspend fun stored(user: UserId) = items.filter { it.first.user == user }.map { StoredMemory(it.first, it.second, it.third) }
    override suspend fun delete(user: UserId, id: Long) = items.removeIf { it.first.id == id && it.first.user == user }
    override suspend fun clear(user: UserId): Int { val n = items.count { it.first.user == user }; items.removeIf { it.first.user == user }; return n }
    override suspend fun touch(ids: Collection<Long>) {
        items.replaceAll { if (it.first.id in ids) Triple(it.first.copy(uses = it.first.uses + 1), it.second, it.third) else it }
    }
    override suspend fun questionsAfter(after: Long, limit: Int) = questions.filter { it.id > after }.take(limit)
    override suspend fun cursor() = at
    override suspend fun setCursor(at: Long) { this.at = at }
}

/** 낱말 주머니 임베딩: 같은 낱말이 많을수록 비슷하다 (조사는 떼지 않는다) */
class WordEmbedder(var model: String = "bge-m3") {
    var calls = 0
    val embed: Embedder = { texts ->
        calls++
        model to texts.map { text ->
            FloatArray(4096).also { v -> text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.forEach { w -> v[(w.hashCode() and 0x7fffffff) % 4096] += 1f } }
        }
    }
}

private val me = UserId(1)
private val other = UserId(2)

class MemoryTest : FunSpec({
    test("명령 알아보기: 기억해·잊어·목록, 질문은 명령이 아님") {
        MemoryCommand.parse("기억해: 나는 차를 좋아해") shouldBe MemoryCommand.Remember("나는 차를 좋아해")
        MemoryCommand.parse("기억해 줘, 내 생일은 3월 2일이야") shouldBe MemoryCommand.Remember("내 생일은 3월 2일이야")
        MemoryCommand.parse("나는 고양이를 키운다고 기억해 줘.") shouldBe MemoryCommand.Remember("나는 고양이를 키운다")
        MemoryCommand.parse("/remember 판교에서 일해") shouldBe MemoryCommand.Remember("판교에서 일해")
        MemoryCommand.parse("!remember 판교에서 일해") shouldBe MemoryCommand.Remember("판교에서 일해")
        MemoryCommand.parse("/forget 3") shouldBe MemoryCommand.Forget("3")
        MemoryCommand.parse("고양이 얘기는 잊어 줘") shouldBe MemoryCommand.Forget("고양이 얘기")
        MemoryCommand.parse("/memory") shouldBe MemoryCommand.Show
        MemoryCommand.parse("나에 대해 뭘 기억하고 있어?") shouldBe MemoryCommand.Show
        MemoryCommand.parse("내가 어제 한 말 기억해?") shouldBe null
        MemoryCommand.parse("기억해야 할 회의 일정 정리해 줘") shouldBe null
        MemoryCommand.parse("파이썬에서 리스트 정렬하는 법") shouldBe null
    }

    test("저장·꺼내기: 비슷한 것만 꺼내고, 같은 사실은 새 문장으로 바꾸며, 남의 기억은 안 보인다") {
        val store = MemoryStore()
        val words = WordEmbedder()
        val service = MemoryService(store, words.embed)
        service.remember(me, "나는 커피보다 녹차를 좋아한다", MemorySource.EXPLICIT).shouldBeInstanceOf<MemoryService.Saved.New>()
            .memory.kind shouldBe MemoryKind.PREFERENCE
        service.remember(me, "판교 회사에서 백엔드 개발을 한다", MemorySource.AUTO)
        service.remember(other, "나는 커피보다 녹차를 좋아한다", MemorySource.EXPLICIT)
        // 거의 같은 문장 → 바꾼다
        service.remember(me, "나는 커피보다 녹차를 좋아한다 정말", MemorySource.AUTO).shouldBeInstanceOf<MemoryService.Saved.Replaced>()
        store.list(me).size shouldBe 2
        service.recall(me, "커피보다 녹차를 좋아한다 카페 추천").map { it.text } shouldContainExactly listOf("나는 커피보다 녹차를 좋아한다 정말")
        store.list(me).first { it.text.contains("녹차") }.uses shouldBe 1
        service.recall(me, "양자역학 설명해 줘") shouldBe emptyList()
        service.recall(other, "판교 회사 백엔드 개발") shouldBe emptyList()
    }

    test("민감한 꼴은 저장하지 않는다") {
        val service = MemoryService(MemoryStore(), WordEmbedder().embed)
        listOf(
            "내 비밀번호는 hunter22",
            "카드 번호 1234 5678 9012 3456",
            "주민번호 900101-1234567",
            "깃허브 키 ghp_" + "a".repeat(30),
        ).forEach { service.remember(me, it, MemorySource.EXPLICIT).shouldBeInstanceOf<MemoryService.Saved.Refused>() }
        service.remember(me, "내 전화는 아이폰이야", MemorySource.EXPLICIT).shouldBeInstanceOf<MemoryService.Saved.New>()
    }

    test("가득 차면 오래 안 쓴 자동 기억부터 비우고, 직접 말한 것만 남으면 거절한다") {
        val store = MemoryStore()
        val service = MemoryService(store, WordEmbedder().embed, maxPerUser = 2)
        service.remember(me, "사과 바나나 포도", MemorySource.AUTO)
        service.remember(me, "기차 버스 자전거", MemorySource.EXPLICIT)
        service.remember(me, "바다 산 강", MemorySource.EXPLICIT).shouldBeInstanceOf<MemoryService.Saved.New>()
        store.list(me).map { it.text }.toSet() shouldBe setOf("기차 버스 자전거", "바다 산 강")
        service.remember(me, "연필 지우개 공책", MemorySource.AUTO).shouldBeInstanceOf<MemoryService.Saved.Refused>()
    }

    test("임베딩 모델이 바뀌면 꺼낼 때 옛 벡터를 다시 만든다") {
        val store = MemoryStore()
        val words = WordEmbedder("bge-m3")
        val service = MemoryService(store, words.embed)
        service.remember(me, "나는 강아지 두 마리를 키운다", MemorySource.EXPLICIT)
        words.model = "bge-m3-vesta:r5"
        service.recall(me, "강아지 키운다").size shouldBe 1
        store.items.single().third shouldBe "bge-m3-vesta:r5"
    }

    test("대화 명령: 저장·목록·번호로 잊기, 손님은 안 됨") {
        val store = MemoryStore()
        val service = MemoryService(store, WordEmbedder().embed)
        val owner = UserContext(me, UserRole.OWNER)
        service.command(owner, "기억해: 나는 부산에 산다")!! shouldContain "기억했어요"
        service.command(owner, "기억해: 나는 매운 음식을 싫어한다")
        service.command(owner, "/memory")!!.let { it shouldContain "2개"; it shouldContain "부산" }
        service.command(owner, "/forget 1")!! shouldContain "잊었어요: 나는 매운 음식을 싫어한다"
        service.command(owner, "잊어: 부산에 산다")!! shouldContain "잊었어요"
        store.items.size shouldBe 0
        service.command(owner, "오늘 날씨 어때") shouldBe null
        service.command(UserContext(other, UserRole.GUEST), "기억해: 나는 손님이다")!! shouldContain "손님"
        store.items.size shouldBe 0
    }

    test("자동 기억: 1인칭 신호가 있는 질문만 보내고, 켠 사용자만, 커서를 옮긴다. 바쁘면 멈춘다") {
        val store = MemoryStore()
        val service = MemoryService(store, WordEmbedder().embed)
        store.questions += listOf(
            UserQuestion(1, me, "나는 판교에서 백엔드 개발자로 일해. 코틀린 질문 좀 할게"),
            UserQuestion(2, me, "/web 오늘 날씨"),
            UserQuestion(3, me, "파이썬 정렬 방법"),
            UserQuestion(4, other, "저는 고양이를 키워요"),
            UserQuestion(5, me, "기억해: 나는 차를 좋아해"),
        )
        val sent = mutableListOf<List<String>>()
        var idle = true
        val keeper = MemoryKeeper(store, service,
            extract = { qs -> sent += qs; listOf(ExtractedFact("사용자는 판교에서 백엔드 개발자로 일한다", MemoryKind.PROJECT)) },
            enabled = { it == me }, idle = { idle })
        val report = keeper.runOnce()!!
        sent shouldBe listOf(listOf("나는 판교에서 백엔드 개발자로 일해. 코틀린 질문 좀 할게"))
        report.saved shouldBe listOf("사용자는 판교에서 백엔드 개발자로 일한다")
        store.at shouldBe 5
        store.list(me).single().source shouldBe MemorySource.AUTO
        keeper.runOnce() shouldBe null
        idle = false
        store.questions += UserQuestion(6, me, "나는 부산 출신이야")
        keeper.runOnce() shouldBe null
        store.at shouldBe 5
    }

    test("모델 답 읽기: 앞뒤 글·코드 블록이 있어도 배열만, 모르는 종류는 추측") {
        MemoryKeeper.parse("```json\n[{\"text\":\"사용자는 녹차를 좋아한다\",\"kind\":\"PREFERENCE\"},{\"text\":\"사용자는 부산에 산다\",\"kind\":\"x\"}]\n```") shouldBe
            listOf(ExtractedFact("사용자는 녹차를 좋아한다", MemoryKind.PREFERENCE), ExtractedFact("사용자는 부산에 산다", MemoryKind.PROFILE))
        MemoryKeeper.parse("없습니다") shouldBe emptyList()
        MemoryKeeper.parse("[]") shouldBe emptyList()
    }
})
