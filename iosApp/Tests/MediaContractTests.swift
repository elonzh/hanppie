import XCTest
import AVFoundation
import UIKit
@testable import Hanppie

final class MediaContractTests: XCTestCase {
    func testOpusRoundTripPreservesDurationAndRobotFraming() throws {
        let input = AVAudioPCMBuffer(pcmFormat: OpusAudio.pcm, frameCapacity: 48_000)!
        input.frameLength = 48_000
        for index in 0..<48_000 { input.floatChannelData![0][index] = Float(sin(Double(index) * 440 * 2 * .pi / 48_000)) * 0.3 }
        let encoded = try OpusAudio.encode(input)
        let packets = try OpusAudio.packets(encoded)
        let decoder = try OpusAudio.Decoder()
        let frames = try packets.reduce(0) { try $0 + Int(decoder.decode($1).frameLength) }
        XCTAssertGreaterThanOrEqual(frames, 48_000)
        XCTAssertLessThanOrEqual(frames, 49_920)
        XCTAssertTrue(packets.allSatisfy { !$0.isEmpty && $0.count < 65_536 })
    }
    func testTruncatedAndEmptyPacketsAreRejected() {
        for bytes in [Data(), Data([1]), Data([0, 0]), Data([3, 0, 1, 2])] {
            XCTAssertThrowsError(try OpusAudio.packets(bytes))
        }
        let pcm = AVAudioPCMBuffer(pcmFormat: OpusAudio.pcm, frameCapacity: 960)!
        XCTAssertThrowsError(try OpusAudio.encode(pcm))
    }
    @MainActor func testVideoToolboxDecodesAnAnnexBKeyframe() async throws {
        let path = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "frame", withExtension: "h264"))
        let pipeline = VideoPipeline()
        let frame = expectation(description: "Decoded H.264 frame")
        var image: UIImage?
        pipeline.configure(frame: { bytes in
            guard image == nil, !bytes.isEmpty else { return }
            image = UIImage(data: bytes); frame.fulfill()
        }, error: { XCTFail($0) })
        pipeline.receive(try Data(contentsOf: path))
        await fulfillment(of: [frame], timeout: 10)
        XCTAssertEqual(image?.size, CGSize(width: 64, height: 48))
        pipeline.stop()
    }
    func testShortTalkHasAtLeastOneDecodableFrame() throws {
        let input = AVAudioPCMBuffer(pcmFormat: OpusAudio.pcm, frameCapacity: 960)!
        input.frameLength = 960
        input.floatChannelData![0].initialize(repeating: 0.2, count: 960)
        let packets = try OpusAudio.packets(OpusAudio.encode(input, bitRate: 10_000))
        XCTAssertFalse(packets.isEmpty)
        let decoder = try OpusAudio.Decoder()
        XCTAssertGreaterThan(try decoder.decode(packets[0]).frameLength, 0)
    }
}
