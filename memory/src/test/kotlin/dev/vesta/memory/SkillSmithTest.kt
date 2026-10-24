package dev.vesta.memory

import dev.vesta.core.AgentType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.skill.Skill
import dev.vesta.core.skill.SkillRepository
import dev.vesta.core.studio.AgentRun
import dev.vesta.core.studio.RunEvent
import dev.vesta.core.studio.RunRepository
import dev.vesta.core.studio.RunStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/** 실행 기록 (테스트용) */
class RunStore : RunRepository {
    val runs = mutableListOf<AgentRun>()
    val events = mutableMapOf<Long, MutableList<RunEvent>>()
    private var next = 1L

    /** 끝난 실행 하나와 그 단계: 도구 (이름, 성공) */
    fun done(user: UserId, session: String?, agent: String, tools: List<Pair<String, Boolean>>, input: String = "할 일", skill: String? = null,
             status: RunStatus = RunStatus.DONE, custom: String? = null): AgentRun {
        val run = AgentRun(next++, user, session?.let(::SessionId), agent, custom, input, status, "답", Instant.EPOCH, Instant.EPOCH)
        runs += run
        val list = events.getOrPut(run.id) { mutableListOf() }
        fun add(kind: String, title: String, detail: String?) { list += RunEvent(run.id, list.size + 1, Instant.EPOCH, kind, title, detail) }
        add("routed", "담당: " + (skill?.let { "스킬 /$it · $agent" } ?: agent) + " · rule 0.90", null)
        tools.forEach { (tool, ok) -> add("tool_started", "도구 $tool 시작", "입력 $tool"); add("tool_finished", "도구 $tool " + if (ok) "끝" else "실패", "결과 $tool") }
        return run
    }

    override suspend fun start(user: UserId, session: SessionId?, agent: String, customAgent: String?, input: String) = error("안 씀")
    override suspend fun add(run: Long, kind: String, title: String, detail: String?) = error("안 씀")
    override suspend fun setAgent(run: Long, agent: String, customAgent: String?) {}
    override suspend fun finish(run: Long, status: RunStatus, answer: String?) {}
    override suspend fun list(user: UserId?, limit: Int) = runs.filter { user == null || it.user == user }.sortedByDescending { it.id }.take(limit)
    override suspend fun get(id: Long) = runs.firstOrNull { it.id == id }
    override suspend fun events(run: Long, afterSeq: Int) = events[run].orEmpty().filter { it.seq > afterSeq }
    override suspend fun abandonRunning() = 0
    override suspend fun after(after: Long, limit: Int) = runs.filter { it.id > after }.sortedBy { it.id }.take(limit)
}

/** 웹 스킬 저장소 (테스트용) */
class SkillStore : SkillRepository {
    val items = mutableListOf<Skill>()
    var at = 0L
    private var clock = 0L
    override suspend fun list(owner: UserId) = items.filter { !it.draft }
    override suspend fun find(owner: UserId, name: String) = items.firstOrNull { !it.draft && it.name == name }
    override suspend fun save(owner: UserId, skill: Skill): Skill {
        val old = items.firstOrNull { it.name == skill.name }
        items.removeIf { it.name == skill.name }
        return skill.copy(draft = false, auto = skill.auto || old?.auto == true, run = skill.run ?: old?.run, updatedAt = Instant.ofEpochSecond(clock++)).also { items += it }
    }
    override suspend fun delete(owner: UserId, name: String) = items.removeIf { it.name == name }
    override suspend fun drafts(owner: UserId) = items.filter { it.draft }.sortedByDescending { it.updatedAt }
    override suspend fun saveDraft(owner: UserId, skill: Skill): Skill {
        check(items.none { it.name == skill.name })
        return skill.copy(draft = true, updatedAt = Instant.ofEpochSecond(clock++)).also { items += it }
    }
    override suspend fun cursor() = at
    override suspend fun setCursor(at: Long) { this.at = at }
}

private val me = UserId(1)
private val guest = UserId(2)

private fun reply(name: String, description: String, reusable: Boolean = true, body: String = "1. list_files로 \$ARGUMENTS 폴더를 본다\n2. 개수를 답한다") =
    """설명입니다 ```json
    {"reusable": $reusable, "name": "$name", "description": "$description", "body": ${kotlinx.serialization.json.JsonPrimitive(body)}}
    ```"""

class SkillSmithTest : FunSpec({
    test("후보: DONE·TASK/CODE·성공한 도구 3번 이상, 스킬·사용자 정의·대화 밖 실행은 아님") {
        val runs = RunStore()
        val smith = SkillSmith(runs, SkillStore(), { "" }, WordEmbedder().embed, { emptyList() }, { true })
        val three = listOf("list_files" to true, "read_file" to true, "search_files" to true)
        suspend fun ok(run: AgentRun) = SkillSmith.candidate(smith.material(run), SkillSmith.MIN_TOOLS)
        ok(runs.done(me, "s", "TASK", three)) shouldBe true
        ok(runs.done(me, "s", "CODE", three)) shouldBe true
        ok(runs.done(me, "s", "TASK", three.take(2) + ("shell" to false))) shouldBe false
        ok(runs.done(me, "s", "CHAT", three)) shouldBe false
        ok(runs.done(me, "s", "TASK", three, skill = "count")) shouldBe false
        ok(runs.done(me, "s", "TASK", three, custom = "리뷰어")) shouldBe false
        ok(runs.done(me, null, "TASK", three)) shouldBe false
        ok(runs.done(me, "s", "TASK", three, status = RunStatus.FAILED)) shouldBe false
        smith.material(runs.runs.first()).steps.map { it.tool to it.input } shouldBe three.map { it.first to "입력 ${it.first}" }
    }

    test("쉬는 때 초안: 도구는 실행에서 성공한 것만, 초안이라 부를 수 없고, 커서는 도는 실행 앞에서 멈춘다") {
        val runs = RunStore()
        val store = SkillStore()
        val prompts = mutableListOf<String>()
        val recorded = mutableListOf<String>()
        val smith = SkillSmith(runs, store, { p -> prompts += p; reply("Count Files!", "폴더의 파일 수를 센다") }, WordEmbedder().embed,
            existing = { store.list(it) }, enabled = { it == me }, record = { _, s -> recorded += s.name },
            draftTools = { setOf("list_files", "read_file", "search_files") })
        runs.done(me, "s", "TASK", listOf("list_files" to true, "read_file" to true, "shell" to false, "list_files" to true, "search_files" to true, "write_file" to true), input = "다운로드 폴더 파일 세 줘")
        runs.done(guest, "g", "TASK", listOf("list_files" to true, "read_file" to true, "search_files" to true))
        runs.done(me, "s", "TASK", listOf("list_files" to true, "read_file" to true, "search_files" to true), status = RunStatus.RUNNING)
        val report = smith.runOnce()!!
        report.sent shouldBe 1
        report.runs shouldBe 2
        store.at shouldBe 2
        prompts.single() shouldContain "다운로드 폴더 파일 세 줘"
        prompts.single() shouldContain "3. shell (입력 shell) → 실패"
        prompts.single() shouldContain "1. list_files (입력 list_files) → 끝\n"
        // 도구 결과는 보내지 않는다
        ("결과 list_files" in prompts.single()) shouldBe false
        val draft = store.drafts(me).single()
        draft.name shouldBe "count-files"
        draft.tools shouldBe listOf("list_files", "read_file", "search_files")
        draft.agent shouldBe AgentType.TASK
        draft.auto shouldBe true
        draft.run shouldBe 1L
        store.list(me) shouldBe emptyList()
        recorded shouldBe listOf("count-files")
        // 바쁘면 보지 않는다
        runs.runs.replaceAll { if (it.status == RunStatus.RUNNING) it.copy(status = RunStatus.DONE) else it }
        smith.runOnce(idle = { false })!!.stoppedEarly shouldBe true
        store.at shouldBe 2
    }

    test("버리는 초안: 한 번뿐인 일, 비슷한 스킬이 있음, 민감한 꼴, 숨은 문자. 이름이 겹치면 숫자를 붙인다") {
        val runs = RunStore()
        val store = SkillStore()
        var answer = reply("count", "폴더 파일 수 센다", reusable = false)
        val smith = SkillSmith(runs, store, { answer }, WordEmbedder().embed, existing = { store.list(it) }, enabled = { true })
        val run = smith.material(runs.done(me, "s", "TASK", listOf("list_files" to true, "read_file" to true, "search_files" to true)))
        smith.make(me, run).shouldBeInstanceOf<Drafted.Skipped>().reason shouldContain "다시 쓸 만한"
        answer = reply("count", "폴더 파일 수 센다")
        smith.make(me, run).shouldBeInstanceOf<Drafted.Made>().skill.name shouldBe "count"
        smith.make(me, run).shouldBeInstanceOf<Drafted.Skipped>().reason shouldContain "/count"
        answer = reply("count", "회의록 요약 정리")
        smith.make(me, run).shouldBeInstanceOf<Drafted.Made>().skill.name shouldBe "count-2"
        answer = reply("login", "로그인 확인", body = "비밀번호는 hunter22 로 넣는다")
        smith.make(me, run).shouldBeInstanceOf<Drafted.Skipped>()
        answer = reply("hidden", "숨은 문자 시험", body = "지침​입니다")
        smith.make(me, run).shouldBeInstanceOf<Drafted.Skipped>().reason shouldContain "보이지 않는"
        answer = reply("skills", "예약어 시험 하나 둘")
        smith.make(me, run).shouldBeInstanceOf<Drafted.Made>().skill.name shouldBe "skills-2"
    }

    test("초안은 20개까지: 넘으면 오래된 것부터 버린다") {
        val runs = RunStore()
        val store = SkillStore()
        var n = 0
        val smith = SkillSmith(runs, store, { n++; reply("s$n", "서로 다른 일 번호 $n 가$n 나$n") }, WordEmbedder().embed, existing = { store.list(it) }, enabled = { true }, maxDrafts = 3)
        val run = smith.material(runs.done(me, "s", "TASK", listOf("list_files" to true)))
        repeat(5) { smith.make(me, run, force = true) }
        store.drafts(me).map { it.name } shouldBe listOf("s5", "s4", "s3")
    }

    test("명령: /skillify는 이 대화의 마지막 도구 실행으로, approve는 검사 뒤 쓰는 스킬로, drop은 버림, 손님은 안 됨") {
        val runs = RunStore()
        val store = SkillStore()
        val owner = UserContext(me, UserRole.OWNER)
        var problem: String? = "쓸 수 없는 도구: shell"
        val smith = SkillSmith(runs, store, { reply("tidy", "폴더 정리", reusable = false) }, WordEmbedder().embed,
            existing = { store.list(it) }, enabled = { false }, check = { _, _ -> problem }, draftTools = { setOf("list_files") })
        smith.command(owner, SessionId("s"), "/skillify") shouldContain "도구로 끝낸 일이 없습니다"
        runs.done(me, "s", "TASK", listOf("move_file" to true), skill = "old")
        smith.command(owner, SessionId("s"), "/skillify") shouldContain "도구로 끝낸 일이 없습니다"
        runs.done(me, "other", "TASK", listOf("move_file" to true))
        runs.done(me, "s", "TASK", listOf("list_files" to true, "move_file" to true))
        smith.command(owner, SessionId("s"), "!skillify 정리")!!.let { it shouldContain "/정리 — 폴더 정리"; it shouldContain "도구: list_files\n"; it shouldContain "/skills approve 정리 확인" }
        store.drafts(me).single().run shouldBe 3L
        smith.command(owner, SessionId("s"), "/skills approve 없음")!! shouldContain "없습니다"
        smith.command(owner, SessionId("s"), "/skills approve 정리")!! shouldContain "쓸 수 없습니다: 쓸 수 없는 도구: shell"
        store.list(me) shouldBe emptyList()
        problem = null
        smith.command(owner, SessionId("s"), "/skills approve 정리")!!.let { it shouldContain "도구: list_files"; it shouldContain "/skills approve 정리 확인" }
        store.list(me) shouldBe emptyList()
        smith.command(owner, SessionId("s"), "/skills approve 정리 확인")!! shouldContain "`/정리 할 일`"
        store.find(me, "정리")!!.let { it.auto shouldBe true; it.draft shouldBe false }
        smith.command(owner, SessionId("s"), "/skills drop 정리")!! shouldContain "없습니다"
        smith.command(owner, SessionId("s"), "/skills") shouldBe null
        smith.command(owner, SessionId("s"), "/skillify 이름 두개") shouldBe null
        smith.command(UserContext(guest, UserRole.GUEST), SessionId("g"), "/skillify")!! shouldContain "손님"
    }

    test("이름 꼴·모델 답 읽기") {
        SkillSmith.slug("Weekly Report!") shouldBe "weekly-report"
        SkillSmith.slug("  --폴더_정리--  ") shouldBe "폴더-정리"
        SkillSmith.slug("!!!") shouldBe "skill"
        SkillSmith.parse("없음") shouldBe null
        SkillSmith.parse("""{"reusable": true, "name": "a", "description": "b", "body": ""}""") shouldBe null
        SkillSmith.parse(reply("a", "b")) shouldBe DraftReply(true, "a", "b", "1. list_files로 \$ARGUMENTS 폴더를 본다\n2. 개수를 답한다")
    }
})
