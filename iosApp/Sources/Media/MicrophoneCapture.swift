import AVFoundation
import Speech
import UIKit

/// Owns the single Apple microphone. Permission callbacks cannot restart a cancelled capture.
final class MicrophoneCapture {
    private let captureLock = NSLock()
    private var captureToken = -1
    private var engine: AVAudioEngine?
    private var file: AVAudioFile?
    private var url: URL?
    private var generation = 0
    private var task: SFSpeechRecognitionTask?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognizer: SFSpeechRecognizer?
    private var timeout: DispatchWorkItem?
    private var talkError: Error?
    private var speechCallback: ((String, Bool, String?) -> Void)?
    private var lastTranscript = ""
    var isActive: Bool { engine != nil }

    private func authorize(_ token: Int, completion: @escaping () -> Void, error: @escaping (String) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                if allowed && UIApplication.shared.applicationState == .active { completion() } else { error(MediaError.permissionDenied.localizedDescription) }
            }
        }
    }
    private func prepare() throws -> AVAudioEngine {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        let engine = AVAudioEngine()
        guard engine.inputNode.outputFormat(forBus: 0).sampleRate > 0 else { throw MediaError.codecUnavailable }
        self.engine = engine
        return engine
    }
    func startTalk(ready: @escaping () -> Void, onError: @escaping (String) -> Void) {
        cancel()
        let token = generation
        authorize(token, completion: { [weak self] in
            guard let self else { return }
            do {
                let engine = try self.prepare()
                let format = engine.inputNode.outputFormat(forBus: 0)
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("Hanppie-talk-\(UUID().uuidString).caf")
                self.url = url
                let file = try AVAudioFile(forWriting: url, settings: format.settings)
                self.file = file
                self.captureLock.lock(); self.captureToken = token; self.captureLock.unlock()
                var frames: AVAudioFrameCount = 0
                engine.inputNode.installTap(onBus: 0, bufferSize: 960, format: format) { [weak self] buffer, _ in
                    guard let self else { return }
                    self.captureLock.lock(); defer { self.captureLock.unlock() }
                    guard self.captureToken == token, frames < AVAudioFrameCount(format.sampleRate * 15) else { return }
                    do { try file.write(from: buffer); frames += buffer.frameLength }
                    catch { DispatchQueue.main.async { guard self.generation == token else { return }; self.talkError = error } }
                }
                engine.prepare(); try engine.start(); ready()
                let work = DispatchWorkItem { [weak self] in
                    guard self?.generation == token else { return }; self?.stopEngine()
                }
                self.timeout = work
                DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: work)
            } catch { self.cancel(); onError(error.localizedDescription) }
        }, error: onError)
    }
    func finishTalk(_ completion: @escaping (Data?, String?) -> Void) {
        let token = generation
        timeout?.cancel(); timeout = nil
        stopEngine(); file = nil
        guard let url else { completion(nil, MediaError.invalidAudio.localizedDescription); return }
        self.url = nil
        if let error = talkError { talkError = nil; try? FileManager.default.removeItem(at: url); completion(nil, error.localizedDescription); return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            defer { try? FileManager.default.removeItem(at: url) }
            let result = Result {
                let pcm = try OpusAudio.load(url)
                guard pcm.frameLength >= 960 else { throw MediaError.invalidAudio }
                return try OpusAudio.encode(pcm, bitRate: 10_000)
            }
            DispatchQueue.main.async {
                guard self?.generation == token else { return }
                switch result { case .success(let bytes): completion(bytes, nil); case .failure(let error): completion(nil, error.localizedDescription) }
            }
        }
    }
    func startSpeech(languageTag: String, result: @escaping (String, Bool, String?) -> Void) {
        cancel()
        let token = generation
        speechCallback = result
        authorize(token, completion: { [weak self] in
            SFSpeechRecognizer.requestAuthorization { status in
                DispatchQueue.main.async {
                    guard let self, self.generation == token else { return }
                    guard status == .authorized else { self.completeSpeech(error: MediaError.permissionDenied.localizedDescription); return }
                    do {
                        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: languageTag)), recognizer.isAvailable else { throw MediaError.codecUnavailable }
                        self.recognizer = recognizer
                        let request = SFSpeechAudioBufferRecognitionRequest()
                        request.shouldReportPartialResults = true
                        // The shared UI discloses online processing before the first request.
                        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
                        self.request = request
                        let engine = try self.prepare()
                        self.captureLock.lock(); self.captureToken = token; self.captureLock.unlock()
                        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: engine.inputNode.outputFormat(forBus: 0)) { [weak self] buffer, _ in
                            guard let self else { return }
                            self.captureLock.lock(); defer { self.captureLock.unlock() }
                            if self.captureToken == token { request.append(buffer) }
                        }
                        self.task = recognizer.recognitionTask(with: request) { [weak self] recognition, error in
                            DispatchQueue.main.async {
                                guard let self, self.generation == token else { return }
                                if let recognition {
                                    self.lastTranscript = recognition.bestTranscription.formattedString
                                    if recognition.isFinal { self.completeSpeech() } else { result(self.lastTranscript, false, nil) }
                                } else if let error { self.completeSpeech(error: error.localizedDescription) }
                            }
                        }
                        engine.prepare(); try engine.start()
                        let work = DispatchWorkItem { [weak self] in guard self?.generation == token else { return }; self?.finishSpeech() }
                        self.timeout = work
                        DispatchQueue.main.asyncAfter(deadline: .now() + 60, execute: work)
                    } catch { self.completeSpeech(error: error.localizedDescription) }
                }
            }
        }, error: { [weak self] message in self?.completeSpeech(error: message) })
    }
    func finishSpeech() {
        stopEngine(); request?.endAudio(); timeout?.cancel()
        let token = generation
        let work = DispatchWorkItem { [weak self] in guard self?.generation == token else { return }; self?.completeSpeech() }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }
    private func completeSpeech(error: String? = nil) {
        let callback = speechCallback, text = lastTranscript
        cancel(); callback?(text, true, error)
    }
    private func stopEngine() {
        captureLock.lock(); captureToken = -1; captureLock.unlock()
        if let engine { engine.inputNode.removeTap(onBus: 0); engine.stop() }
        engine = nil
    }
    func cancel() {
        generation += 1; timeout?.cancel(); timeout = nil
        stopEngine(); task?.cancel(); task = nil; request = nil; recognizer = nil
        file = nil; if let url { try? FileManager.default.removeItem(at: url) }; url = nil
        talkError = nil; speechCallback = nil; lastTranscript = ""
    }
}
