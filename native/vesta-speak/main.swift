// vesta-speak: macOS 음성 합성을 스트리밍으로 (PROJECT.md V-11, M19).
// 표준 입력 한 줄 = 한 문장. 합성하는 대로 48kHz 16비트 스테레오 big-endian PCM을 표준 출력으로 흘린다.
// 문장마다 프로세스를 띄우는 `say -o`와 달리 첫 소리가 바로 나오고, 다음 문장이 같은 흐름에 이어 붙는다.
// 사용: vesta-speak [음성 이름, 기본 Yuna]   (음성 목록: vesta-speak --list)
import AVFoundation
import Foundation

let args = CommandLine.arguments
let voices = AVSpeechSynthesisVoice.speechVoices()
if args.count > 1 && args[1] == "--list" {
    for v in voices where v.language.hasPrefix("ko") { print("\(v.name)\t\(v.identifier)\t\(v.quality.rawValue)") }
    exit(0)
}
let wanted = args.count > 1 ? args[1] : "Yuna"
// 같은 이름이면 품질이 높은(고급·향상) 음성을 고른다
guard let voice = voices.filter({ $0.name == wanted || $0.identifier == wanted }).max(by: { $0.quality.rawValue < $1.quality.rawValue })
    ?? AVSpeechSynthesisVoice(language: "ko-KR") else {
    FileHandle.standardError.write("음성을 찾지 못했습니다: \(wanted)\n".data(using: .utf8)!)
    exit(2)
}

let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: 2, interleaved: true)!
let synthesizer = AVSpeechSynthesizer()
let out = FileHandle.standardOutput
var converter: AVAudioConverter?

/** 합성 버퍼 → 48kHz 스테레오 Int16 → big-endian으로 바꿔 내보낸다 */
func emit(_ buffer: AVAudioPCMBuffer) {
    if converter == nil || converter!.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: target) }
    guard let converter = converter else { return }
    let ratio = target.sampleRate / buffer.format.sampleRate
    let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 1024)
    guard let converted = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
    var fed = false
    var error: NSError?
    converter.convert(to: converted, error: &error) { _, status in
        if fed { status.pointee = .noDataNow; return nil }
        fed = true
        status.pointee = .haveData
        return buffer
    }
    if error != nil || converted.frameLength == 0 { return }
    let samples = Int(converted.frameLength) * 2
    let pointer = converted.int16ChannelData![0]
    var bytes = Data(count: samples * 2)
    bytes.withUnsafeMutableBytes { raw in
        let dst = raw.bindMemory(to: UInt16.self)
        for i in 0..<samples { dst[i] = UInt16(bitPattern: pointer[i]).bigEndian }
    }
    out.write(bytes)
}

/** 한 문장을 다 합성할 때까지 기다린다 (버퍼 길이 0이 끝) */
func speak(_ text: String) {
    let done = DispatchSemaphore(value: 0)
    let utterance = AVSpeechUtterance(string: text)
    utterance.voice = voice
    DispatchQueue.main.async {
        synthesizer.write(utterance) { buffer in
            guard let pcm = buffer as? AVAudioPCMBuffer else { return }
            if pcm.frameLength == 0 { done.signal(); return }
            emit(pcm)
        }
    }
    done.wait()
}

DispatchQueue.global().async {
    while let line = readLine(strippingNewline: true) {
        let text = line.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { speak(text) }
    }
    exit(0)
}
RunLoop.main.run()
