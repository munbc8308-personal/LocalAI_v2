package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.inference.HttpClients
import dev.vesta.inference.OllamaAdmin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 모델 받기·확인 (PROJECT.md N-02, M23) */
class ModelsCommand : CliktCommand(name = "models") {
    override fun help(context: Context) = "설정의 모델 받기·확인"
    override fun run() = Unit

    companion object {
        fun create() = ModelsCommand().subcommands(ModelsPullCommand(), ModelsListCommand())

        /** 설정에서 Ollama로 돌리는 모델 (메인·코드·비전·임베딩, 중복 제거) */
        fun ollamaModels(config: VestaConfig): List<String> = listOfNotNull(config.models.general, config.models.code, config.models.vision, config.models.embedding)
            .filter { it.engine == null || it.engine == "ollama" }.map { it.name }.filter { it.isNotBlank() }.distinct()

        /** Ollaya(결정 모델)에 받아 둔 모델 이름. ollaya가 없거나 실패하면 null */
        fun ollayaModels(binary: Path): Set<String>? = runCatching {
            val process = ProcessBuilder(binary.toString(), "list").redirectErrorStream(true).start()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
            process.inputStream.bufferedReader().readLines().drop(1).mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf(String::isNotBlank) }.toSet()
        }.getOrNull()

        val OLLAYA: Path = Path.of(System.getProperty("user.home"), ".local", "bin", "ollaya")

        /** Ollama 모델 저장소가 있는 디스크의 남은 공간 */
        fun freeBytes(): Long = runCatching { Files.getFileStore(Path.of(System.getProperty("user.home"))).usableSpace }.getOrDefault(Long.MAX_VALUE)
    }
}

class ModelsListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "설정의 모델이 받아져 있는지 본다"

    override fun run() = runBlocking {
        val config = ConfigLoader.load(VestaPaths.default().configFile, VestaPaths.default().tunedFile)
        val http = HttpClients.local(requestTimeoutSeconds = 10)
        try {
            val local = runCatching { OllamaAdmin(config.inference.ollamaUrl, http).localModels().associateBy { it.name } }.getOrElse {
                echo("Ollama에 연결하지 못했습니다 (${config.inference.ollamaUrl}): brew services start ollama", err = true); throw ProgramResult(1)
            }
            ModelsCommand.ollamaModels(config).forEach { name ->
                echo((local[name]?.let { "✓ $name (${gb(it.sizeBytes)})" } ?: "✗ $name (받기: vesta models pull)"))
            }
            val decided = ModelsCommand.ollayaModels(ModelsCommand.OLLAYA)
            val model = config.decision.model
            echo(when {
                decided == null -> "? 결정 모델 $model (ollaya 확인 불가)"
                model in decided || "$model:latest" in decided -> "✓ 결정 모델 $model (Ollaya)"
                else -> "✗ 결정 모델 $model (받기: vesta models pull)"
            })
        } finally {
            http.close()
        }
    }
}

class ModelsPullCommand : CliktCommand(name = "pull") {
    override fun help(context: Context) = "설정의 모델 중 없는 것을 받는다 (Ollama·Ollaya). 받기 전에 디스크 여유를 확인한다"
    private val force by option("--force", help = "디스크 여유가 모자라 보여도 받는다").flag()

    override fun run() = runBlocking {
        val paths = VestaPaths.default()
        val config = ConfigLoader.load(paths.configFile, paths.tunedFile)
        val http = HttpClients.local(requestTimeoutSeconds = 30)
        try {
            val admin = OllamaAdmin(config.inference.ollamaUrl, http)
            val local = runCatching { admin.localModels().map { it.name }.toSet() }.getOrElse {
                echo("Ollama에 연결하지 못했습니다 (${config.inference.ollamaUrl}): brew services start ollama", err = true); throw ProgramResult(1)
            }
            val missing = ModelsCommand.ollamaModels(config).filter { it !in local && "$it:latest" !in local }
            val decision = config.decision.model.takeIf { config.decision.enabled }
            val decided = ModelsCommand.ollayaModels(ModelsCommand.OLLAYA)
            val needDecision = decision != null && decided != null && decision !in decided && "$decision:latest" !in decided
            if (missing.isEmpty() && !needDecision) { echo("받을 모델이 없습니다 (모두 받아 둠)"); return@runBlocking }

            // 크기는 받기 시작해야 알 수 있어, 이 Mac 메모리 단계의 대략 크기로 미리 본다
            val plan = dev.vesta.app.HardwareProfile.recommend(dev.vesta.app.MacSystem.totalMemoryBytes())
            val need = plan.downloadGigabytes * 1_000_000_000L + 10_000_000_000L
            val free = ModelsCommand.freeBytes()
            if (free < need && !force) {
                echo("디스크 여유가 모자랍니다: 남은 ${gb(free)}, 필요 약 ${gb(need)} (여유 10GB 포함). 정리한 뒤 다시 하거나 --force", err = true)
                throw ProgramResult(1)
            }
            for (model in missing) {
                echo("받기: $model")
                var shown = -1L
                admin.pull(model) { done, total ->
                    val percent = done * 100 / total
                    if (percent / 5 != shown / 5) { shown = percent; echo("  $percent% (${gb(done)} / ${gb(total)})") }
                }
                echo("  ✓ $model")
            }
            if (needDecision) {
                echo("받기: 결정 모델 $decision (Ollaya)")
                val process = ProcessBuilder(ModelsCommand.OLLAYA.toString(), "pull", decision!!).inheritIO().start()
                if (process.waitFor() != 0) { echo("결정 모델을 받지 못했습니다: ollaya pull $decision", err = true); throw ProgramResult(1) }
            } else if (decided == null && decision != null) {
                echo("Ollaya가 없어 결정 모델을 건너뜁니다 (scripts/setup-macos.sh)", err = true)
            }
            echo("그림(FLUX)·음성 인식(Whisper)은 처음 쓸 때 미디어 워커가 받는다 (약 12GB)")
        } finally {
            http.close()
        }
    }
}
