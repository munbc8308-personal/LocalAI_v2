package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.vesta.app.PasswordHasher
import dev.vesta.core.config.VestaPaths
import dev.vesta.storage.VestaDatabase
import dev.vesta.storage.WebAuthRepository
import kotlinx.coroutines.runBlocking

/** 웹 UI 로그인 비밀번호 설정 (PROJECT.md 6.3, U-02). 이 Mac 터미널에서만 정할 수 있다. 바꾸면 모든 기기가 로그아웃된다. */
class PasswdCommand : CliktCommand(name = "passwd") {
    override fun help(context: Context) = "웹 UI 로그인 비밀번호를 정한다 (바꾸면 로그인한 기기 모두 로그아웃)"
    private val password by option("--password", hidden = true)
        .prompt("새 비밀번호", hideInput = true, requireConfirmation = true, confirmationPrompt = "한 번 더")

    override fun run() = runBlocking {
        if (password.length < 8) {
            echo("비밀번호는 8자 이상이어야 합니다", err = true)
            throw ProgramResult(1)
        }
        val paths = VestaPaths.default()
        val db = VestaDatabase.open(paths.database).also { it.migrate() }
        val owner = db.ensureOwner(System.getProperty("user.name") ?: "owner")
        WebAuthRepository(db.db).setPasswordHash(owner, PasswordHasher.hash(password))
        echo("웹 UI 비밀번호를 저장했습니다. 로그인돼 있던 기기는 모두 로그아웃됩니다.")
    }
}
