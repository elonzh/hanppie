import AVFoundation

/// Main-thread playback owner. Both robot audio and clips retain at most 25 decoded frames.
final class AudioPlayback {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var decoder: OpusAudio.Decoder?
    private var generation = 0
    private var timer: Timer?
    private var frames: Int64 = 0
    private var pendingStreamFrames = 0
    private var clipPackets: [Data] = []
    private var clipIndex = 0
    private var clipPending = 0
    private var position: Int64 = 0
    private var progress: ((Int64, Int64, Bool) -> Void)?
    init() {
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: OpusAudio.pcm)
    }
    private func start() throws {
        if !engine.isRunning {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            try engine.start()
        }
        if !player.isPlaying { player.play() }
    }
    func stream(_ bytes: Data) throws -> AVAudioPCMBuffer {
        if decoder == nil { decoder = try OpusAudio.Decoder() }
        try start()
        let buffer = try decoder!.decode(bytes)
        let token = generation
        if pendingStreamFrames < 25 {
            pendingStreamFrames += 1
            player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                DispatchQueue.main.async { guard let self, self.generation == token else { return }; self.pendingStreamFrames -= 1 }
            }
        }
        return buffer
    }
    func play(_ bytes: Data, progress: @escaping (Int64, Int64, Bool) -> Void) throws {
        stop()
        clipPackets = try OpusAudio.packets(bytes)
        decoder = try OpusAudio.Decoder()
        frames = Int64(clipPackets.count) * 960
        self.progress = progress
        do { try start(); try scheduleClipFrames() } catch { stop(); throw error }
        progress(0, frames / 48, true)
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if let samples = self.player.lastRenderTime.flatMap(self.player.playerTime(forNodeTime:))?.sampleTime {
                self.position = min(samples / 48, self.frames / 48)
            }
            self.progress?(self.position, self.frames / 48, self.player.isPlaying && self.engine.isRunning)
        }
    }
    private func scheduleClipFrames() throws {
        let token = generation
        while clipPending < 25 && clipIndex < clipPackets.count {
            let buffer = try decoder!.decode(clipPackets[clipIndex])
            clipIndex += 1; clipPending += 1
            player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self, self.generation == token else { return }
                    self.clipPending -= 1
                    if self.clipPending == 0 && self.clipIndex == self.clipPackets.count {
                        let callback = self.progress, duration = self.frames / 48
                        self.stop(); callback?(duration, duration, false)
                    } else {
                        do { try self.scheduleClipFrames() } catch { self.stop() }
                    }
                }
            }
        }
    }
    func pause() { player.pause(); progress?(position, frames / 48, false) }
    func resume() throws {
        guard progress != nil else { return }
        do { try start() } catch { stop(); throw error }
    }
    func stop() {
        let callback = progress
        generation += 1; timer?.invalidate(); timer = nil
        player.stop(); engine.stop(); decoder = nil; progress = nil; pendingStreamFrames = 0
        clipPackets = []; clipIndex = 0; clipPending = 0; position = 0
        callback?(0, 0, false)
    }
}
