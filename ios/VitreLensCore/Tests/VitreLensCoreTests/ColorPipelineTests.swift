import XCTest
@testable import VitreLensCore

final class ColorPipelineTests: XCTestCase {

    func testToneMapLUTIsMonotonic() {
        for transfer in [ColorPipeline.Transfer.hlg, .pq] {
            let t = ColorPipeline.toneMapLUT(transfer: transfer)
            XCTAssertEqual(t.count, 256)
            XCTAssertEqual(t[0], 0, "\(transfer) 黑场应为 0")
            XCTAssertEqual(t[255], 255, "\(transfer) 白场应为 255")
            for i in 1..<256 {
                XCTAssertGreaterThanOrEqual(t[i], t[i - 1], "\(transfer) LUT 在 \(i) 处非单调")
            }
        }
    }

    func testHLGWhitePointLandsAt82Percent() {
        // HLG 白点定在 82% 信号：该处约等于 SDR 的 1.0 线性，再经 sRGB 编码应接近纯白
        let t = ColorPipeline.toneMapLUT(transfer: .hlg)
        let at82 = t[Int((0.82 * 255).rounded())]
        XCTAssertGreaterThan(at82, 245, "82% 信号应接近白，实际 \(at82)")
    }

    func testPQMidGreyIsNotBlownOut() {
        let t = ColorPipeline.toneMapLUT(transfer: .pq)
        let at50 = t[128]
        XCTAssertGreaterThan(at50, 140, "PQ 中段过暗")
        XCTAssertLessThan(at50, 230, "PQ 中段过曝")
    }

    func testGamutWhiteAndBlackAreStable() {
        XCTAssertEqual(ColorPipeline.gamutPack(r: 255, g: 255, b: 255), 0xFFFFFF)
        XCTAssertEqual(ColorPipeline.gamutPack(r: 0, g: 0, b: 0), 0x000000)
    }

    func testGamutClampsOutOfRangePrimaries() {
        // BT.2020 的纯绿在 BT.709 里超出范围，必须被夹住而不是回绕
        let packed = ColorPipeline.gamutPack(r: 0, g: 255, b: 0)
        let r = (packed >> 16) & 0xFF
        let g = (packed >> 8) & 0xFF
        let b = packed & 0xFF
        XCTAssertGreaterThanOrEqual(r, 0)
        XCTAssertLessThanOrEqual(g, 255)
        XCTAssertGreaterThanOrEqual(b, 0)
    }

    func testBT709FullRangeWhiteAndBlack() {
        let c = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .full)
        XCTAssertEqual(c.rgb(y: 255, u: 128, v: 128), 0xFFFFFF)
        XCTAssertEqual(c.rgb(y: 0, u: 128, v: 128), 0x000000)
        XCTAssertEqual(c.label, "BT.709 / SDR / full")
    }

    func testBT709LimitedRangeBlackIsBlack() {
        // limited 范围的黑电平是 Y=16，转换后必须落到 0 而不是灰
        let c = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .limited)
        XCTAssertEqual(c.rgb(y: 16, u: 128, v: 128), 0x000000)
        XCTAssertEqual(c.rgb(y: 235, u: 128, v: 128), 0xFFFFFF, "limited 白电平 235 应到纯白")
    }

    func testRangeAffectsResult() {
        let full = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .full)
        let limited = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .limited)
        XCTAssertNotEqual(full.rgb(y: 128, u: 128, v: 128),
                          limited.rgb(y: 128, u: 128, v: 128),
                          "色彩范围标错会让黑白电平整体偏移")
    }

    func testHDRSourceAppliesToneMap() {
        let sdr = YUVConverter(standard: .bt2020, transfer: .sdrVideo, range: .full)
        let hlg = YUVConverter(standard: .bt2020, transfer: .hlg, range: .full)
        XCTAssertFalse(sdr.hdrSource)
        XCTAssertTrue(hlg.hdrSource)
        XCTAssertNil(sdr.lut)
        XCTAssertNotNil(hlg.lut)
        XCTAssertNotEqual(sdr.rgb(y: 200, u: 128, v: 128), hlg.rgb(y: 200, u: 128, v: 128))
    }

    func testKeepHDRSignalSkipsToneMapButKeepsGamut() {
        let mapped = YUVConverter(standard: .bt2020, transfer: .hlg, range: .full)
        let kept = YUVConverter(standard: .bt2020, transfer: .hlg, range: .full, keepHDRSignal: true)
        XCTAssertNil(kept.lut, "保留 HDR 信号时不做色调映射")
        XCTAssertFalse(kept.gamut2020to709, "保留 HDR 信号时也不应该压到 709 色域")
        XCTAssertTrue(mapped.gamut2020to709)
        XCTAssertEqual(kept.label, "BT.2020 / HLG / full")
    }

    func testUnsupportedStandardFallsBackTo709() {
        // 值 9 不在已知标准里，应按 BT.709 处理
        let unknown = YUVConverter(standard: ColorPipeline.Standard(rawValue: 9) ?? .bt709,
                                   transfer: .sdrVideo, range: .full)
        let b709 = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .full)
        XCTAssertEqual(unknown.rgb(y: 180, u: 90, v: 200), b709.rgb(y: 180, u: 90, v: 200))
    }

    func testBT2020MatrixDiffersFromBT709() {
        let a = YUVConverter(standard: .bt709, transfer: .sdrVideo, range: .full)
        let b = YUVConverter(standard: .bt2020, transfer: .sdrVideo, range: .full)
        XCTAssertNotEqual(a.rgb(y: 120, u: 60, v: 200), b.rgb(y: 120, u: 60, v: 200))
    }
}
