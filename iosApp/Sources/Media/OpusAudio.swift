import AVFoundation

/// Apple's codec performs Opus conversion; robot packets keep their existing 20 ms framing.
enum OpusAudio {
    static let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false)!
    static var opus: AVAudioFormat {
        var stream = AudioStreamBasicDescription(mSampleRate: 48_000, mFormatID: kAudioFormatOpus,
            mFormatFlags: 0, mBytesPerPacket: 0, mFramesPerPacket: 960, mBytesPerFrame: 0, mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
        return AVAudioFormat(streamDescription: &stream)!
    }
    static func encode(_ input: AVAudioPCMBuffer, bitRate: Int = 12_000) throws -> Data {
        guard input.frameLength > 0, input.format.commonFormat == .pcmFormatFloat32 else { throw MediaError.invalidAudio }
        guard let converter = AVAudioConverter(from: input.format, to: opus) else { throw MediaError.codecUnavailable }
        converter.bitRate = bitRate
        var offset: AVAudioFrameCount = 0
        var result = Data()
        var iterations = 0
        while true {
            let output = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: 4_000)
            var error: NSError?
            let previous = offset
            let status = converter.convert(to: output, error: &error) { requested, status in
                guard offset < input.frameLength else { status.pointee = .endOfStream; return nil }
                let count = min(AVAudioFrameCount(requested), input.frameLength - offset)
                let chunk = AVAudioPCMBuffer(pcmFormat: input.format, frameCapacity: max(count, 960))!
                chunk.frameLength = max(count, 960)
                for channel in 0..<Int(input.format.channelCount) {
                    chunk.floatChannelData![channel].update(from: input.floatChannelData![channel].advanced(by: Int(offset)), count: Int(count))
                    if count < chunk.frameLength {
                        chunk.floatChannelData![channel].advanced(by: Int(count)).initialize(repeating: 0, count: Int(chunk.frameLength - count))
                    }
                }
                offset += count
                status.pointee = .haveData
                return chunk
            }
            if let error { throw error }
            if status == .error { throw MediaError.codecUnavailable }
            if output.packetCount > 0 {
                let length = Int(output.byteLength)
                guard length <= 65_535 else { throw MediaError.invalidAudio }
                var prefix = UInt16(length).littleEndian
                withUnsafeBytes(of: &prefix) { result.append(contentsOf: $0) }
                result.append(output.data.assumingMemoryBound(to: UInt8.self), count: length)
            }
            if status == .endOfStream { break }
            iterations += 1
            guard iterations < Int(input.frameLength / 960) * 4 + 32,
                  offset > previous || output.packetCount > 0 else { throw MediaError.invalidAudio }
        }
        guard !result.isEmpty else { throw MediaError.invalidAudio }
        return result
    }
    static func load(_ url: URL) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        guard file.length > 0, file.length <= Int64(file.fileFormat.sampleRate * 600) else { throw MediaError.invalidAudio }
        let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
        try file.read(into: input)
        if input.format == pcm { return input }
        guard let converter = AVAudioConverter(from: input.format, to: pcm) else { throw MediaError.codecUnavailable }
        let capacity = AVAudioFrameCount(Double(input.frameLength) * 48_000 / input.format.sampleRate) + 1_024
        let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity)!
        var supplied = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if supplied { status.pointee = .endOfStream; return nil }
            supplied = true; status.pointee = .haveData; return input
        }
        if let error { throw error }
        return output
    }
    static func packets(_ bytes: Data) throws -> [Data] {
        guard !bytes.isEmpty else { throw MediaError.invalidAudio }
        var offset = 0
        var packets = [Data]()
        while offset < bytes.count {
            guard offset + 2 <= bytes.count else { throw MediaError.invalidAudio }
            let size = Int(bytes[offset]) | Int(bytes[offset + 1]) << 8
            offset += 2
            guard size > 0, offset + size <= bytes.count else { throw MediaError.invalidAudio }
            packets.append(bytes.subdata(in: offset..<offset + size)); offset += size
        }
        return packets
    }
    final class Decoder {
        private let converter: AVAudioConverter
        init() throws {
            guard let converter = AVAudioConverter(from: opus, to: pcm) else { throw MediaError.codecUnavailable }
            self.converter = converter
        }
        func decode(_ packet: Data) throws -> AVAudioPCMBuffer {
            guard !packet.isEmpty && packet.count < 65_536 else { throw MediaError.invalidAudio }
            let input = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: packet.count)
            packet.copyBytes(to: input.data.assumingMemoryBound(to: UInt8.self), count: packet.count)
            input.packetCount = 1; input.byteLength = UInt32(packet.count)
            input.packetDescriptions![0] = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 960, mDataByteSize: UInt32(packet.count))
            let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: 1_920)!
            var supplied = false
            var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return input
            }
            if let error { throw error }
            return output
        }
    }
}

enum MediaError: LocalizedError {
    case codecUnavailable, invalidAudio, noFrame, permissionDenied
    var errorDescription: String? {
        switch self {
        case .codecUnavailable: return NSLocalizedString("Audio codec unavailable", comment: "")
        case .invalidAudio: return NSLocalizedString("Audio is empty, invalid or exceeds 10 minutes", comment: "")
        case .noFrame: return NSLocalizedString("Wait for a live video frame", comment: "")
        case .permissionDenied: return NSLocalizedString("Permission denied. Enable access in Settings → Apps → Hanppie", comment: "")
        }
    }
}
