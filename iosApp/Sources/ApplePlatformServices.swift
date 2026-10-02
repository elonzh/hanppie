import UIKit
import AVFoundation
import HanppieShared

final class ApplePlatformServices: NSObject, IosPlatformServices {
    private let video = VideoPipeline()
    private let stream = AudioPlayback()
    private let clips = AudioPlayback()
    private let microphone = MicrophoneCapture()
    private let mediaQueue = DispatchQueue(label: "hanppie.audio.import", qos: .userInitiated)
    private var lifecycleObserver: NSObjectProtocol?
    override init() {
        super.init()
        lifecycleObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.microphone.cancel(); self?.stream.stop(); self?.clips.stop(); self?.video.stop()
        }
    }
    func videoView() -> UIView { video.view }
    func configureVideo(onFrame: @escaping (Data) -> Void, onError: @escaping (String) -> Void) {
        video.configure(frame: { onFrame($0) }, error: onError)
    }
    func videoPacket(bytes: Data) { video.receive(bytes) }
    func audioPacket(bytes: Data) {
        let data = bytes
        DispatchQueue.main.async { [weak self] in
            guard let self, UIApplication.shared.applicationState == .active, !self.microphone.isActive else { return }
            do { let pcm = try self.stream.stream(data); self.video.audio(pcm) } catch { self.stream.stop() }
        }
    }
    func stopVideo() { video.stop(); DispatchQueue.main.async { [weak self] in self?.stream.stop() } }
    func takePhoto(completion: @escaping (String?, String?) -> Void) { DispatchQueue.main.async { self.video.photo(completion) } }
    func setRecording(enabled: Bool, completion: @escaping (String?, String?) -> Void) { video.record(enabled, completion: completion) }
    func encodeAudio(name: String, bytes: Data, completion: @escaping (Data?, KotlinLong, String?) -> Void) {
        let data = bytes
        mediaQueue.async {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(URL(fileURLWithPath: name).pathExtension)
            defer { try? FileManager.default.removeItem(at: url) }
            do {
                try data.write(to: url)
                let pcm = try OpusAudio.load(url)
                let encoded = try OpusAudio.encode(pcm)
                let duration = Int64(Double(pcm.frameLength) / pcm.format.sampleRate * 1000)
                DispatchQueue.main.async { completion(encoded, KotlinLong(value: duration), nil) }
            } catch { DispatchQueue.main.async { completion(nil, KotlinLong(value: 0), error.localizedDescription) } }
        }
    }
    func playAudio(id: Int32, bytes: Data, onProgress: @escaping (KotlinLong, KotlinLong, KotlinBoolean) -> Void) {
        let data = bytes
        DispatchQueue.main.async {
            do { try self.clips.play(data) { position, duration, playing in onProgress(KotlinLong(value: position), KotlinLong(value: duration), KotlinBoolean(value: playing)) } }
            catch { onProgress(KotlinLong(value: 0), KotlinLong(value: 0), KotlinBoolean(value: false)) }
        }
    }
    func pauseAudio() { DispatchQueue.main.async { self.clips.pause() } }
    func resumeAudio() { DispatchQueue.main.async { try? self.clips.resume() } }
    func stopAudio() { DispatchQueue.main.async { self.clips.stop() } }
    func startTalk(onReady: @escaping () -> Void, onError: @escaping (String) -> Void) {
        DispatchQueue.main.async { self.stream.stop(); self.clips.stop(); self.microphone.startTalk(ready: onReady, onError: onError) }
    }
    func finishTalk(completion: @escaping (Data?, String?) -> Void) {
        DispatchQueue.main.async { self.microphone.finishTalk { completion($0, $1) } }
    }
    func cancelTalk() { DispatchQueue.main.async { self.microphone.cancel() } }
    func startSpeech(languageTag: String, onResult: @escaping (String, KotlinBoolean, String?) -> Void) {
        DispatchQueue.main.async { self.stream.stop(); self.clips.stop(); self.microphone.startSpeech(languageTag: languageTag) { onResult($0, KotlinBoolean(value: $1), $2) } }
    }
    func finishSpeech() { DispatchQueue.main.async { self.microphone.finishSpeech() } }
    func cancelSpeech() { DispatchQueue.main.async { self.microphone.cancel() } }
    deinit { if let lifecycleObserver { NotificationCenter.default.removeObserver(lifecycleObserver) } }
}
