package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.choice
import dev.vesta.core.UserRole
import dev.vesta.core.config.VestaPaths
import dev.vesta.identity.UserException
import dev.vesta.identity.UserService
import dev.vesta.storage.ExposedUserRepository
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.storage.VestaDatabase
import dev.vesta.storage.WebAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 사용자 관리 (PROJECT.md U-01). 이 Mac 터미널(= Mac 주인)에서만. 서버는 요청마다 DB를 읽으므로 다시 띄울 필요 없다 */
class UserCommand : CliktCommand(name = "user") {
    override fun help(context: Context) = "사용자 관리 (가족·팀, 최대 5명)"
    override fun run() = Unit

    companion object {
        fun create() = UserCommand().subcommands(UserListCommand(), UserAddCommand(), UserRoleCommand(), UserPasswdCommand(), UserConsentCommand(), UserRemoveCommand())
    }
}

/** DB와 사용자 서비스 (가벼운 명령용: 모델·도구는 띄우지 않는다) */
internal class UserStore {
    val paths = VestaPaths.default()
    val db = VestaDatabase.open(paths.database).also { it.migrate() }
    val owner = db.ensureOwner(System.getProperty("user.name") ?: "owner")
    val repository = ExposedUserRepository(db.db)
    val service = UserService(
        repository, WebAuthRepository(db.db),
        deleteMedia = { id -> withContext(Dispatchers.IO) { paths.home.resolve("media").resolve(id.value.toString()).toFile().deleteRecursively() } },
    )
    val knowledge = KnowledgeRepository(db.db)
}

/** 규칙 위반은 메시지만 보여 주고 종료 코드 1 */
internal suspend fun CliktCommand.userAction(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: UserException) {
        echo(e.message, err = true)
        throw ProgramResult(1)
    }
}

private val ROLES = arrayOf("member", "guest")
private fun role(name: String) = UserRole.valueOf(name.uppercase())

class UserListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "사용자와 오늘 사용량"

    override fun run() = runBlocking {
        val store = UserStore()
        val usage = store.service.usageToday()
        store.service.list().forEach { u ->
            val today = usage[u.id]
            val flags = listOfNotNull(if (!u.hasPassword) "비밀번호 없음" else null, if (u.trainingConsent && u.role != UserRole.OWNER) "학습 동의" else null)
            echo("${u.name.padEnd(16)} ${u.role.name.padEnd(6)}  오늘 질문 ${today?.questions ?: 0}개 · 토큰 ${today?.completionTokens ?: 0}" + flags.joinToString("") { " · $it" })
        }
    }
}

class UserAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "사용자를 추가한다 (기본 MEMBER: Apple 앱·셸 없음, 파일은 자기 폴더만)"
    private val name by argument()
    private val role by option("--role", help = "member: 문서·웹·파일(자기 폴더)·이미지 / guest: 대화·웹만").choice(*ROLES).default("member")
    private val password by option("--password", hidden = true)
        .prompt("웹 UI 비밀번호", hideInput = true, requireConfirmation = true, confirmationPrompt = "한 번 더")

    override fun run() = runBlocking {
        val store = UserStore()
        userAction {
            val user = store.service.create(name, role(role), password)
            echo("추가했습니다: ${user.name} (${user.role}). 웹 UI 로그인 이름은 ${user.name}")
            echo("메신저 연결: vesta telegram pair --user ${user.name} (discord·slack도 같음)")
        }
    }
}

class UserRoleCommand : CliktCommand(name = "role") {
    override fun help(context: Context) = "역할을 바꾼다 (다음 요청부터 적용)"
    private val name by argument()
    private val role by argument().convert { it.lowercase() }

    override fun run() = runBlocking {
        if (role !in ROLES) { echo("역할은 member·guest 중 하나입니다", err = true); throw ProgramResult(1) }
        val store = UserStore()
        userAction { store.service.setRole(name, role(role)).also { echo("${it.name}: ${it.role}") } }
    }
}

class UserPasswdCommand : CliktCommand(name = "passwd") {
    override fun help(context: Context) = "사용자의 웹 UI 비밀번호를 정한다 (그 사용자의 기기는 모두 로그아웃)"
    private val name by argument()
    private val password by option("--password", hidden = true)
        .prompt("새 비밀번호", hideInput = true, requireConfirmation = true, confirmationPrompt = "한 번 더")

    override fun run() = runBlocking {
        val store = UserStore()
        userAction { store.service.setPassword(name, password); echo("$name 의 비밀번호를 저장했습니다.") }
    }
}

class UserConsentCommand : CliktCommand(name = "consent") {
    override fun help(context: Context) = "이 사용자의 라우팅 정정을 결정 모델 학습에 써도 되는지 (L-09)"
    private val name by argument()
    private val off by option("--off", help = "동의 취소").flag()

    override fun run() = runBlocking {
        val store = UserStore()
        userAction {
            store.service.setConsent(name, !off)
            echo(if (off) "$name: 학습에 쓰지 않습니다" else "$name: 학습 동의")
        }
    }
}

class UserRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "사용자와 그 데이터(대화·컬렉션·이미지·메신저 연결)를 지운다. 되돌릴 수 없다"
    private val name by argument()
    private val yes by option("--yes", help = "확인 없이 지운다").flag()

    override fun run() = runBlocking {
        val store = UserStore()
        if (!yes) {
            echo("$name 의 대화·컬렉션·이미지가 모두 지워집니다. 지우려면 이름을 다시 입력하세요: ", trailingNewline = false)
            if (readlnOrNull()?.trim() != name) { echo("취소했습니다."); return@runBlocking }
        }
        userAction { store.service.delete(name); echo("지웠습니다: $name (파일 폴더 ${dev.vesta.core.config.ConfigLoader.load(store.paths.configFile).users.memberRoot}/$name 은 남겨 둡니다)") }
    }
}

/** 컬렉션 공유 (U-04). Mac 주인의 컬렉션을 다른 사용자도 검색할 수 있게 한다. 색인은 하나를 같이 쓴다 */
class CollectionShareCommand : CliktCommand(name = "share") {
    override fun help(context: Context) = "컬렉션을 다른 사용자와 공유한다 (읽기·검색만)"
    private val collection by argument()
    private val user by argument()

    override fun run() = runBlocking { share(collection, user, on = true) }
}

class CollectionUnshareCommand : CliktCommand(name = "unshare") {
    override fun help(context: Context) = "컬렉션 공유를 끊는다"
    private val collection by argument()
    private val user by argument()

    override fun run() = runBlocking { share(collection, user, on = false) }
}

private suspend fun CliktCommand.share(collection: String, user: String, on: Boolean) {
    val store = UserStore()
    val target = store.knowledge.findCollection(store.owner, collection) ?: run { echo("컬렉션이 없습니다: $collection", err = true); throw ProgramResult(1) }
    val member = store.service.find(user) ?: run { echo("없는 사용자입니다: $user", err = true); throw ProgramResult(1) }
    if (member.role == UserRole.GUEST) { echo("GUEST는 로컬 문서를 쓰지 않습니다. 먼저 vesta user role $user member", err = true); throw ProgramResult(1) }
    val ok = if (on) store.knowledge.share(store.owner, target.id, member.id) else store.knowledge.unshare(store.owner, target.id, member.id)
    echo(
        when {
            on && ok -> "공유했습니다: $collection → $user (@$collection 으로 검색 가능)"
            on -> "공유하지 못했습니다 (본인에게는 공유할 수 없음)"
            ok -> "공유를 끊었습니다: $collection ✕ $user"
            else -> "공유돼 있지 않습니다"
        },
    )
}
