package dev.vesta.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class WorkspacesTest : FunSpec({
    test("VESTA.md: 저장소 루트(.git)부터 작업 폴더까지 차례로 붙이고, 루트 위는 보지 않는다") {
        val home = createTempDirectory("home").toRealPath()
        val above = home.resolve("work").createDirectories()
        above.resolve("VESTA.md").writeText("위쪽 (저장소 밖)")
        val repo = above.resolve("repo").createDirectories()
        repo.resolve(".git").createDirectories()
        repo.resolve("VESTA.md").writeText("공통 규칙")
        val sub = repo.resolve("app").createDirectories()
        sub.resolve("VESTA.md").writeText("앱 규칙")

        val text = Workspaces.instructions(sub, home)!!
        text shouldContain "공통 규칙"
        text shouldContain "앱 규칙"
        (text.indexOf("공통 규칙") < text.indexOf("앱 규칙")) shouldBe true
        ("위쪽" in text) shouldBe false

        Workspaces.instructions(repo.resolve(".git"), home) shouldBe "공통 규칙"
        Workspaces.current(home, home) shouldBe null
        Workspaces.current(sub, home)!!.path shouldBe sub.toString()
    }
})
