package dev.vesta.server

import dev.vesta.api.AttachedDocumentDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Base64

class DocumentsTest : FunSpec({
    fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    test("붙인 문서: .txt·.md·.pdf 글을 뽑고, 이름은 파일 이름만 (V-12)") {
        val pdf = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Autumn concert at Hwaseong")
                cs.endText()
            }
            java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val result = decodeDocuments(listOf(
            AttachedDocumentDto("../../etc/메모.txt", b64("가을 음악회".toByteArray())),
            AttachedDocumentDto("event.pdf", b64(pdf)),
        ))
        result.shouldBeInstanceOf<DocumentsResult.Ok>()
        result.documents.map { it.name } shouldBe listOf("메모.txt", "event.pdf")
        result.documents[0].text shouldBe "가을 음악회"
        result.documents[1].text shouldContain "Autumn concert"
    }

    test("형식·개수·빈 글·base64가 틀리면 거부한다") {
        (decodeDocuments(listOf(AttachedDocumentDto("a.exe", b64(byteArrayOf(1))))) as DocumentsResult.Invalid).message shouldContain ".txt·.md·.pdf"
        (decodeDocuments(List(4) { AttachedDocumentDto("a.txt", b64("x".toByteArray())) }) as DocumentsResult.Invalid).message shouldContain "3개까지"
        (decodeDocuments(listOf(AttachedDocumentDto("a.txt", b64("   ".toByteArray())))) as DocumentsResult.Invalid).message shouldContain "글을 찾지 못했습니다"
        decodeDocuments(listOf(AttachedDocumentDto("a.txt", "%%%"))).shouldBeInstanceOf<DocumentsResult.Invalid>()
        decodeDocuments(listOf(AttachedDocumentDto("a.pdf", b64("not a pdf".toByteArray())))).shouldBeInstanceOf<DocumentsResult.Invalid>()
    }
})
