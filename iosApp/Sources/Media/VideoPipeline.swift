import UIKit
import VideoToolbox
import AVFoundation
import Photos
import CoreImage

final class VideoPipeline {
    let view = UIImageView()
    private let queue = DispatchQueue(label: "hanppie.video", qos: .userInteractive)
    private let slots = DispatchSemaphore(value: 4)
    private var session: VTDecompressionSession?
    private var format: CMVideoFormatDescription?
    private var sps: Data?, pps: Data?
    private var waitingForKey = true
    private let lifecycleLock = NSLock()
    private var lifecycleGeneration = 0
    private var generation: Int {
        get { lifecycleLock.lock(); defer { lifecycleLock.unlock() }; return lifecycleGeneration }
        set { lifecycleLock.lock(); lifecycleGeneration = newValue; lifecycleLock.unlock() }
    }
    private var recordingGeneration = 0
    private var frameCallback: ((Data) -> Void)?
    private var errorCallback: ((String) -> Void)?
    private let context = CIContext()
    private var latest: UIImage?
    private var cacheStamp = 0.0
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var recordingURL: URL?
    private var recordingStart = 0.0
    private var recordingRequested = false
    init() { view.contentMode = .scaleAspectFit; view.backgroundColor = .clear }
    func configure(frame: @escaping (Data) -> Void, error: @escaping (String) -> Void) {
        queue.sync {
            reset(); generation += 1
            frameCallback = frame; errorCallback = error
        }
    }
    func receive(_ bytes: Data) {
        guard slots.wait(timeout: .now()) == .success else { queue.async { [weak self] in self?.waitingForKey = true }; return }
        queue.async { [weak self] in
            guard let self else { return }
            defer { self.slots.signal() }
            do { try self.decode(bytes) } catch { self.report(error) }
        }
    }
    private func decode(_ bytes: Data) throws {
        var starts = [(Int, Int)]()
        let array = [UInt8](bytes)
        var index = 0
        while index + 3 < array.count {
            if array[index] == 0 && array[index + 1] == 0 {
                if array[index + 2] == 1 { starts.append((index, index + 3)); index += 3; continue }
                if array[index + 2] == 0 && array[index + 3] == 1 { starts.append((index, index + 4)); index += 4; continue }
            }
            index += 1
        }
        var avc = Data(), key = false
        for (offset, start) in starts.enumerated() {
            let end = offset + 1 < starts.count ? starts[offset + 1].0 : array.count
            guard start.1 < end else { continue }
            let nal = bytes.subdata(in: start.1..<end)
            let type = nal[0] & 31
            if type == 7 { if sps != nal { resetDecoder(); sps = nal } }
            else if type == 8 { if pps != nal { resetDecoder(); pps = nal } }
            else if (1...5).contains(type) {
                key = key || type == 5
                var length = UInt32(nal.count).bigEndian
                withUnsafeBytes(of: &length) { avc.append(contentsOf: $0) }; avc.append(nal)
            }
        }
        guard !avc.isEmpty, let sps, let pps else { return }
        if waitingForKey && !key { return }
        waitingForKey = false
        if session == nil {
            let status = sps.withUnsafeBytes { s in pps.withUnsafeBytes { p -> OSStatus in
                let pointers = [s.baseAddress!.assumingMemoryBound(to: UInt8.self), p.baseAddress!.assumingMemoryBound(to: UInt8.self)]
                let sizes = [sps.count, pps.count]
                return pointers.withUnsafeBufferPointer { pointers in sizes.withUnsafeBufferPointer { sizes in
                    CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault,
                        parameterSetCount: 2, parameterSetPointers: pointers.baseAddress!, parameterSetSizes: sizes.baseAddress!, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
                } }
            } }
            guard status == noErr, let format else { throw NSError(domain: "H264", code: Int(status)) }
            var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { ref, frameToken, status, _, pixel, _, _ in
                guard status == noErr, let ref, let pixel else { return }
                let pipeline = Unmanaged<VideoPipeline>.fromOpaque(ref).takeUnretainedValue()
                let token = Int(bitPattern: frameToken) - 1
                pipeline.queue.async { guard pipeline.generation == token else { return }; pipeline.display(pixel) }
            }, decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque())
            let decoderStatus = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format,
                decoderSpecification: nil, imageBufferAttributes: [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA] as CFDictionary,
                outputCallback: &callback, decompressionSessionOut: &session)
            guard decoderStatus == noErr else { throw NSError(domain: "H264", code: Int(decoderStatus)) }
        }
        var block: CMBlockBuffer?
        var sample: CMSampleBuffer?
        let count = avc.count
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: count,
            blockAllocator: nil, customBlockSource: nil, offsetToData: 0, dataLength: count, flags: 0, blockBufferOut: &block) == noErr,
            let block else { throw MediaError.noFrame }
        let copyStatus = avc.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: count) }
        guard copyStatus == noErr else { throw MediaError.noFrame }
        var length = count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
            sampleSizeArray: &length, sampleBufferOut: &sample) == noErr, let sample, let session else { throw MediaError.noFrame }
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], frameRefcon: UnsafeMutableRawPointer(bitPattern: generation + 1), infoFlagsOut: nil)
        if status != noErr { resetDecoder(); throw NSError(domain: "H264", code: Int(status)) }
    }
    private func display(_ pixel: CVPixelBuffer) {
        guard let cg = context.createCGImage(CIImage(cvPixelBuffer: pixel), from: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(pixel), height: CVPixelBufferGetHeight(pixel))) else { return }
        let image = UIImage(cgImage: cg)
        let now = CACurrentMediaTime()
        let cached = now - cacheStamp > 0.5 ? image.pngData() ?? Data() : Data()
        if !cached.isEmpty { cacheStamp = now }
        let callback = frameCallback, token = generation
        DispatchQueue.main.async { [weak self] in
            guard let self, self.generation == token else { return }
            self.latest = image; self.view.image = image; callback?(cached)
        }
        if recordingRequested {
            do {
                if writer == nil { try beginWriter(pixel); recordingStart = now }
                let stamp = CMTime(seconds: now - recordingStart, preferredTimescale: 60_000)
                if writerInput?.isReadyForMoreMediaData == true {
                    guard adaptor?.append(pixel, withPresentationTime: stamp) == true else { throw writer?.error ?? MediaError.noFrame }
                }
            } catch { recordingRequested = false; writer?.cancelWriting()
                if let recordingURL { try? FileManager.default.removeItem(at: recordingURL) }
                writer = nil; writerInput = nil; audioInput = nil; adaptor = nil; recordingURL = nil
                report(error) }
        }
    }
    private func beginWriter(_ pixel: CVPixelBuffer) throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Hanppie-\(UUID().uuidString).mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: CVPixelBufferGetWidth(pixel), AVVideoHeightKey: CVPixelBufferGetHeight(pixel)])
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else { throw MediaError.noFrame }
        writer.add(input)
        let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000])
        audio.expectsMediaDataInRealTime = true
        guard writer.canAdd(audio) else { throw MediaError.codecUnavailable }
        writer.add(audio); audioInput = audio
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
        guard writer.startWriting() else { throw writer.error ?? MediaError.noFrame }
        writer.startSession(atSourceTime: .zero)
        self.writer = writer; writerInput = input; self.adaptor = adaptor; recordingURL = url
    }
    func audio(_ pcm: AVAudioPCMBuffer) {
        queue.async { [weak self] in
            guard let self, self.recordingRequested, self.writer != nil, let input = self.audioInput,
                input.isReadyForMoreMediaData, pcm.frameLength > 0 else { return }
            var format: CMAudioFormatDescription?
            let description = pcm.format.streamDescription
            guard CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: description,
                layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format) == noErr,
                let format else { return }
            var sample: CMSampleBuffer?
            var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 48_000),
                presentationTimeStamp: CMTime(seconds: max(0, CACurrentMediaTime() - self.recordingStart), preferredTimescale: 48_000), decodeTimeStamp: .invalid)
            guard CMSampleBufferCreate(allocator: kCFAllocatorDefault, dataBuffer: nil, dataReady: false,
                makeDataReadyCallback: nil, refcon: nil, formatDescription: format, sampleCount: Int(pcm.frameLength),
                sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 0, sampleSizeArray: nil, sampleBufferOut: &sample) == noErr,
                let sample else { return }
            guard CMSampleBufferSetDataBufferFromAudioBufferList(sample, blockBufferAllocator: kCFAllocatorDefault,
                blockBufferMemoryAllocator: kCFAllocatorDefault, flags: 0, bufferList: pcm.audioBufferList) == noErr else { return }
            CMSampleBufferSetDataReady(sample)
            if !input.append(sample) { self.report(self.writer?.error ?? MediaError.invalidAudio) }
        }
    }
    func record(_ enabled: Bool, completion: @escaping (String?, String?) -> Void) {
        queue.async { [weak self] in
            guard let self else { return }
            self.recordingGeneration += 1
            let token = self.recordingGeneration
            if enabled {
                DispatchQueue.main.async {
                    self.authorizePhotos { allowed in
                        self.queue.async {
                            guard self.recordingGeneration == token else { return }
                            guard allowed else { DispatchQueue.main.async { completion(nil, MediaError.permissionDenied.localizedDescription) }; return }
                            self.recordingRequested = true
                            DispatchQueue.main.async { completion(nil, nil) }
                        }
                    }
                }
            } else { self.finishRecording(completion) }
        }
    }
    private func finishRecording(_ completion: @escaping (String?, String?) -> Void) {
        recordingRequested = false
        guard let writer, let url = recordingURL else { DispatchQueue.main.async { completion(nil, MediaError.noFrame.localizedDescription) }; return }
        writerInput?.markAsFinished(); audioInput?.markAsFinished()
        self.writer = nil; writerInput = nil; audioInput = nil; adaptor = nil; recordingURL = nil
        writer.finishWriting {
            guard writer.status == .completed else { DispatchQueue.main.async { completion(nil, writer.error?.localizedDescription ?? MediaError.noFrame.localizedDescription) }; return }
            PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) }) { success, error in
                if success { try? FileManager.default.removeItem(at: url) }
                DispatchQueue.main.async { completion(success ? NSLocalizedString("Saved to Photos", comment: "") : nil, error?.localizedDescription ?? (success ? nil : MediaError.permissionDenied.localizedDescription)) }
            }
        }
    }
    func photo(_ completion: @escaping (String?, String?) -> Void) {
        guard let latest else { completion(nil, MediaError.noFrame.localizedDescription); return }
        authorizePhotos { allowed in
            guard allowed else { completion(nil, MediaError.permissionDenied.localizedDescription); return }
            PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.creationRequestForAsset(from: latest) }) { success, error in
                DispatchQueue.main.async { completion(success ? NSLocalizedString("Saved to Photos", comment: "") : nil, error?.localizedDescription ?? (success ? nil : MediaError.permissionDenied.localizedDescription)) }
            }
        }
    }
    private func authorizePhotos(_ completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in DispatchQueue.main.async { completion(status == .authorized || status == .limited) } }
    }
    private func report(_ error: Error) {
        let callback = errorCallback
        DispatchQueue.main.async { callback?(error.localizedDescription) }
    }
    private func resetDecoder() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil; format = nil; waitingForKey = true
    }
    private func reset() { resetDecoder(); sps = nil; pps = nil; cacheStamp = 0 }
    func stop() { queue.sync {
        generation += 1; recordingGeneration += 1; reset(); frameCallback = nil; errorCallback = nil
        let token = generation
        DispatchQueue.main.async { [weak self] in guard let self, self.generation == token else { return }; self.latest = nil; self.view.image = nil }
        if recordingRequested || writer != nil { finishRecording { _, _ in } }
    } }
    deinit { resetDecoder() }
}
