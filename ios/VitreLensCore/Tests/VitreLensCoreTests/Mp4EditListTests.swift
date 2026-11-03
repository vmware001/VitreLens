import XCTest
@testable import VitreLensCore

final class Mp4EditListTests: XCTestCase {

    // MARK: - 构造最小的 MP4 盒树

    private func be32(_ v: Int) -> [UInt8] {
        [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
    }

    private func be64(_ v: Int64) -> [UInt8] {
        (0..<8).reversed().map { UInt8((v >> (Int64($0) * 8)) & 0xFF) }
    }

    private func box(_ type: String, _ payload: [UInt8]) -> [UInt8] {
        be32(8 + payload.count) + Array(type.utf8) + payload
    }

    private func hdlr(_ handler: String) -> [UInt8] {
        // version+flags(4) pre_defined(4) handler_type(4) 之后还有 reserved，够解析即可
        box("hdlr", [0, 0, 0, 0] + [0, 0, 0, 0] + Array(handler.utf8) + [0, 0, 0, 0])
    }

    private func mdhd(timescale: Int) -> [UInt8] {
        // version+flags(4) creation(4) modification(4) timescale(4) duration(4)
        box("mdhd", [0, 0, 0, 0] + be32(0) + be32(0) + be32(timescale) + be32(1000))
    }

    private func elstV0(mediaTime: Int, duration: Int = 1000) -> [UInt8] {
        box("elst", [0, 0, 0, 0] + be32(1) + be32(duration) + be32(mediaTime) + be32(1 << 16))
    }

    private func elstV1(mediaTime: Int64, duration: Int64 = 1000) -> [UInt8] {
        box("elst", [1, 0, 0, 0] + be32(1) + be64(duration) + be64(mediaTime) + be32(1 << 16))
    }

    private func trak(handler: String, timescale: Int, elst: [UInt8]?) -> [UInt8] {
        var mdia = hdlr(handler) + mdhd(timescale: timescale)
        mdia = box("mdia", mdia)
        var body = mdia
        if let elst = elst {
            body += box("edts", elst)
        }
        return box("trak", body)
    }

    private func mp4(_ traks: [[UInt8]]) -> [UInt8] {
        let ftyp = box("ftyp", Array("isom".utf8) + be32(0x200) + Array("isomiso2avc1mp41".utf8))
        return ftyp + box("moov", traks.flatMap { $0 })
    }

    // MARK: - 用例

    func testReadsVideoPreRollOffset() {
        // 600 timescale、media_time 60 → 100ms，正好是我在 OPPO 实况照片上实测到的预卷
        let data = mp4([trak(handler: "vide", timescale: 600, elst: elstV0(mediaTime: 60))])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 100_000)
    }

    func testReadsVersion1Elst() {
        let data = mp4([trak(handler: "vide", timescale: 90000, elst: elstV1(mediaTime: 9000))])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 100_000)
    }

    func testSkipsAudioTrackAndFindsVideo() {
        let data = mp4([
            trak(handler: "soun", timescale: 48000, elst: elstV0(mediaTime: 4800)),
            trak(handler: "vide", timescale: 600, elst: elstV0(mediaTime: 30))
        ])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 50_000)
    }

    func testAudioTrackAloneReturnsZero() {
        let data = mp4([trak(handler: "soun", timescale: 48000, elst: elstV0(mediaTime: 4800))])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 0)
    }

    func testNoEditListReturnsZero() {
        let data = mp4([trak(handler: "vide", timescale: 600, elst: nil)])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 0)
    }

    func testEmptyEditIsIgnored() {
        // media_time = -1 表示空编辑，不是偏移
        var bytes = elstV0(mediaTime: 0)
        // 把 media_time 改写成 0xFFFFFFFF
        let mediaTimeOffset = bytes.count - 4 - 4
        bytes.replaceSubrange(mediaTimeOffset..<(mediaTimeOffset + 4), with: [0xFF, 0xFF, 0xFF, 0xFF])
        let data = mp4([trak(handler: "vide", timescale: 600, elst: bytes)])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 0)
    }

    func testAbsurdOffsetIsTreatedAsParseFailure() {
        // 超过 10 秒的偏移不可能是预卷，应当当作解析错误
        let data = mp4([trak(handler: "vide", timescale: 1, elst: elstV0(mediaTime: 60))])
        XCTAssertEqual(Mp4EditList.videoStartUs(data: data), 0)
    }

    func testTruncatedDataDoesNotCrash() {
        XCTAssertEqual(Mp4EditList.videoStartUs(data: []), 0)
        XCTAssertEqual(Mp4EditList.videoStartUs(data: [0, 0, 0, 8]), 0)
        let data = mp4([trak(handler: "vide", timescale: 600, elst: elstV0(mediaTime: 60))])
        for cut in stride(from: 8, to: data.count, by: 17) {
            _ = Mp4EditList.videoStartUs(data: Array(data.prefix(cut)))
        }
    }
}
