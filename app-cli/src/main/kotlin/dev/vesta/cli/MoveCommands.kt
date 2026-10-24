package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.core.config.VestaPaths
import dev.vesta.storage.ExposedModelVersionRepository
import dev.vesta.storage.VestaDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** 옮기기 묶음 안내서 (PROJECT.md N-05) */
@Serializable
internal data class MoveManifest(
    val format: Int = 1,
    val created: String,
    /** 만든 Mac의 홈 (가져올 때 미디어 경로를 바꾼다) */
    val home: String,
    val schema: Int,
    val media: Boolean,
    /** 넣은 학습 모델: 역할 → 이름 (기본 모델은 새 Mac에서 다시 받는다) */
    val models: Map<String, String> = emptyMap(),
)

/**
 * 모델 저장소 (매니페스트 + blob). Ollaya·Ollama가 같은 꼴. [registries]는 매니페스트를 찾는 곳들:
 * Ollama는 받은 모델을 `manifests/registry.ollama.ai/library`, API로 만든 모델(학습한 임베딩)을 `manifests-v2/ollama.com/library`에 둔다
 */
internal data class ModelStore(val key: String, val root: Path, val registries: List<String>) {
    /** 있는 곳의 매니페스트 (없으면 첫 위치) */
    fun manifest(name: String): Path {
        val (repo, tag) = name.split(':').let { it[0] to (it.getOrNull(1) ?: "latest") }
        val candidates = registries.map { root.resolve(it).resolve(repo).resolve(tag) }
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    /** 매니페스트가 가리키는 blob 이름들 (sha256-…) */
    fun blobs(manifest: Path): Set<String> = runCatching {
        val json = Json.parseToJsonElement(manifest.readText()).jsonObject
        (listOfNotNull(json["config"]) + json["layers"]!!.jsonArray).mapNotNull { it.jsonObject["digest"]?.jsonPrimitive?.content?.replace(':', '-') }.toSet()
    }.getOrDefault(emptySet())

    companion object {
        fun all(home: Path) = listOf(
            ModelStore("ollaya", home.resolve(".ollaya/models"), listOf("manifests/ollaya.dev/library")),
            ModelStore("ollama", home.resolve(".ollama/models"), listOf("manifests/registry.ollama.ai/library", "manifests-v2/ollama.com/library")),
        )
    }
}

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

/**
 * 옮기기 (N-05): 설정·tuned·DB(대화·컬렉션·사용자·설정)·미디어·학습한 모델을 파일 하나(tar.gz)로.
 * 키체인의 토큰·비밀번호는 넣지 않는다. 묶음에는 대화·문서 내용이 들어 있으므로 본인만 읽게(600) 만든다
 */
class ExportCommand : CliktCommand(name = "export") {
    override fun help(context: Context) = "설정·대화·컬렉션·사용자·미디어·학습한 모델을 파일 하나로 묶는다 (다른 Mac에서 vesta import)"
    private val output by option("--output", "-o", help = "만들 파일 (기본 ~/vesta-export-날짜.tar.gz)").path()
    private val noMedia by option("--no-media", help = "미디어(만든 그림·영상, 붙인 이미지)는 빼기").flag()
    private val noModels by option("--no-models", help = "학습한 모델은 빼기 (새 Mac에서는 기본 모델을 쓴다)").flag()

    override fun run() = runBlocking {
        val paths = VestaPaths.default()
        val home = Path.of(System.getProperty("user.home"))
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val target = (output ?: home.resolve("vesta-export-$stamp.tar.gz")).toAbsolutePath()
        val stage = Files.createTempDirectory("vesta-export")
        try {
            listOf(paths.configFile, paths.tunedFile).filter { it.exists() }.forEach { Files.copy(it, stage.resolve(it.fileName)) }
            // DB 스냅숏: 서버가 돌아도 한 시점의 일관된 사본
            val database = VestaDatabase.open(paths.database).also { it.migrate() }
            java.sql.DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { c ->
                c.createStatement().use { it.execute("VACUUM INTO '${stage.resolve("vesta.db").toString().replace("'", "''")}'") }
            }
            echo("DB: 대화·컬렉션·사용자·설정 (schema v${database.schemaVersion()})")
            val media = paths.home.resolve("media")
            if (!noMedia && media.isDirectory()) {
                copyTree(media, stage.resolve("media"))
                echo("미디어: ${gb(sizeOf(media))}")
            }
            val models = mutableMapOf<String, String>()
            if (!noModels) {
                val versions = ExposedModelVersionRepository(database.db)
                for (role in listOf("decision", "embedding")) {
                    val active = versions.active(role) ?: continue
                    val copied = ModelStore.all(home).firstNotNullOfOrNull { store -> copyModel(store, active.name, active.baseModel, stage.resolve("models").resolve(store.key)) }
                    if (copied != null) { models[role] = active.name; echo("학습 모델: ${active.name} ($role, ${gb(copied)})") }
                    else echo("학습 모델 ${active.name}을(를) 찾지 못해 뺍니다", err = true)
                }
            }
            stage.resolve("manifest.json").writeText(json.encodeToString(MoveManifest.serializer(),
                MoveManifest(created = java.time.Instant.now().toString(), home = home.toString(), schema = database.schemaVersion(), media = !noMedia, models = models)))
            Files.createDirectories(target.parent)
            // 대화·문서가 든 파일이라 처음부터 본인만 읽게(600) 만든 뒤 tar 출력을 그 안에 쓴다 (tar가 만들게 하면 잠깐 644로 열려 있다, 보안 검토)
            Files.deleteIfExists(target)
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val process = ProcessBuilder("/usr/bin/tar", "-czf", "-", "-C", stage.toString(), ".")
                .redirectOutput(ProcessBuilder.Redirect.to(target.toFile())).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            if (process.waitFor() != 0) { Files.deleteIfExists(target); echo("묶지 못했습니다 (tar)", err = true); throw ProgramResult(1) }
            echo("만들었습니다: $target (${gb(Files.size(target))})")
            echo("대화·문서 내용이 들어 있습니다. 안전한 곳에만 두세요. 토큰·비밀번호(키체인)는 들어 있지 않아 새 Mac에서 vesta setup으로 다시 넣습니다.")
        } finally {
            stage.toFile().deleteRecursively()
        }
    }

    /** 학습 모델의 매니페스트와, 기본 모델에 없는 blob만. 복사한 크기 (없으면 null) */
    private fun copyModel(store: ModelStore, name: String, base: String, into: Path): Long? {
        val manifest = store.manifest(name).takeIf { it.exists() } ?: return null
        val shared = store.manifest(base).takeIf { it.exists() }?.let { store.blobs(it) }.orEmpty()
        var size = 0L
        val relative = store.root.relativize(manifest)
        Files.createDirectories(into.resolve(relative).parent)
        Files.copy(manifest, into.resolve(relative), StandardCopyOption.REPLACE_EXISTING)
        for (blob in store.blobs(manifest) - shared) {
            val source = store.root.resolve("blobs").resolve(blob).takeIf { it.exists() } ?: continue
            Files.createDirectories(into.resolve("blobs"))
            Files.copy(source, into.resolve("blobs").resolve(blob), StandardCopyOption.REPLACE_EXISTING)
            size += Files.size(source)
        }
        return size
    }
}

/**
 * 가져오기 (N-05): `vesta export` 파일을 이 Mac에 푼다. 서비스를 멈추고, 지금 것은 `~/.vesta/backup-날짜/`로 옮긴 뒤 바꾼다.
 * 미디어 경로를 이 Mac 홈으로 바꾸고, 학습한 모델은 모델 저장소에 넣는다. 기본 모델·토큰은 `vesta models pull`·`vesta setup`
 */
class ImportCommand : CliktCommand(name = "import") {
    override fun help(context: Context) = "vesta export 파일을 이 Mac에 푼다 (지금 것은 ~/.vesta/backup-날짜 로 옮겨 둔다)"
    private val file by argument(help = "vesta export 로 만든 .tar.gz").path(mustExist = true)
    private val yes by option("--yes", help = "묻지 않고 바꾼다").flag()

    override fun run() = runBlocking {
        val paths = VestaPaths.default()
        val home = Path.of(System.getProperty("user.home"))
        val stage = Files.createTempDirectory("vesta-import")
        try {
            val untar = ProcessBuilder("/usr/bin/tar", "-xzf", file.toAbsolutePath().toString(), "-C", stage.toString()).inheritIO().start()
            if (untar.waitFor() != 0) { echo("풀지 못했습니다 (tar)", err = true); throw ProgramResult(1) }
            val manifest = runCatching { json.decodeFromString(MoveManifest.serializer(), stage.resolve("manifest.json").readText()) }.getOrElse {
                echo("vesta export 파일이 아닙니다 (manifest.json 없음)", err = true); throw ProgramResult(1)
            }
            if (manifest.format != 1) { echo("모르는 형식입니다 (${manifest.format}). Vesta를 업데이트하세요", err = true); throw ProgramResult(1) }
            echo("묶음: ${manifest.created} · ${manifest.home} · schema v${manifest.schema} · 미디어 ${if (manifest.media) "있음" else "없음"} · 학습 모델 ${manifest.models.values.joinToString().ifEmpty { "없음" }}")
            if (!yes) {
                val console = System.console() ?: run { echo("터미널에서 실행하거나 --yes", err = true); throw ProgramResult(1) }
                val answer = console.readLine("이 Mac의 Vesta 설정·대화·미디어를 이 묶음으로 바꿉니다 (지금 것은 백업). 계속할까요? [y/N] ")?.trim()?.lowercase()
                if (answer !in setOf("y", "yes", "ㅛ", "네", "예")) { echo("그만둡니다"); return@runBlocking }
            }
            // VESTA_HOME으로 따로 둔 환경(시험 등)이면 이 Mac의 서비스는 건드리지 않는다
            val services = Services().takeIf { System.getenv("VESTA_HOME") == null }
            val running = services?.stop() ?: false
            val backup = paths.home.resolve("backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
            Files.createDirectories(backup)
            for (name in listOf("config.yaml", "tuned.properties", "vesta.db", "vesta.db-wal", "vesta.db-shm", "media")) {
                val current = paths.home.resolve(name)
                if (current.exists()) Files.move(current, backup.resolve(name))
            }
            echo("지금 것을 옮겨 둠: $backup")
            for (name in listOf("config.yaml", "tuned.properties", "vesta.db", "media")) {
                val incoming = stage.resolve(name)
                if (incoming.exists()) Files.move(incoming, paths.home.resolve(name))
            }
            Files.setPosixFilePermissions(paths.home.resolve("vesta.db"), PosixFilePermissions.fromString("rw-------"))
            // 미디어 파일 경로는 만든 Mac의 홈 기준 → 이 Mac 홈으로
            if (manifest.home != home.toString()) {
                java.sql.DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { c ->
                    c.prepareStatement("UPDATE media_asset SET path = ? || substr(path, ?) WHERE path LIKE ? || '%'").use { s ->
                        s.setString(1, home.toString()); s.setInt(2, manifest.home.length + 1); s.setString(3, manifest.home); s.executeUpdate()
                    }
                }
            }
            VestaDatabase.open(paths.database).migrate()
            val stores = ModelStore.all(home).associateBy { it.key }
            for (key in listOf("ollaya", "ollama")) {
                val source = stage.resolve("models").resolve(key).takeIf { it.isDirectory() } ?: continue
                val store = stores.getValue(key)
                Files.walk(source).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.forEach { f ->
                    val dest = store.root.resolve(source.relativize(f))
                    Files.createDirectories(dest.parent)
                    if (!dest.exists() || dest.toString().contains("/manifests")) Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            if (manifest.models.isNotEmpty()) echo("학습 모델을 넣었습니다: ${manifest.models.values.joinToString()} (기본 모델은 vesta models pull)")
            if (running) services?.start()
            echo("끝. 다음: vesta models pull (기본 모델) → vesta setup (토큰·비밀번호, 키체인) → vesta doctor")
            echo("컬렉션의 원본 파일 경로는 만든 Mac 기준입니다. 검색은 되지만, 다시 색인하려면 이 Mac 경로로 vesta collection add")
        } finally {
            stage.toFile().deleteRecursively()
        }
    }
}

/** Vesta launchd 서비스 멈추기·다시 켜기 (가져오기·업데이트 중에 DB를 바꾸지 않게) */
internal class Services(private val labels: List<String> = listOf("dev.vesta.server", "dev.vesta.media", "dev.vesta.evolve")) {
    private val uid = run("/usr/bin/id", "-u")?.trim()
    private val agents = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")

    /** 하나라도 돌고 있었으면 true */
    fun stop(): Boolean {
        var any = false
        for (label in labels) if (run("/bin/launchctl", "bootout", "gui/$uid/$label") != null) any = true
        return any
    }

    fun start() {
        for (label in labels) {
            val plist = agents.resolve("$label.plist").takeIf { it.exists() } ?: continue
            repeat(5) { if (run("/bin/launchctl", "bootstrap", "gui/$uid", plist.toString()) != null) return@repeat; Thread.sleep(1_000) }
        }
    }

    private fun run(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0)
        out
    }.getOrNull()
}

internal fun copyTree(from: Path, to: Path) {
    Files.walk(from).use { s ->
        s.forEach { p ->
            val dest = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

internal fun sizeOf(dir: Path): Long = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
