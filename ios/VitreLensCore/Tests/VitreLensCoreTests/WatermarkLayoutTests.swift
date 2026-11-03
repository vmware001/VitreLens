import XCTest
@testable import VitreLensCore

final class WatermarkLayoutTests: XCTestCase {

    private let measurer = SyntheticMeasurer()

    private func style() -> WatermarkStyle {
        var s = WatermarkStyle()
        s.name = "OPPO Find X10 Pro Max"
        s.params = "23mm f/1.8 1/287s ISO50"
        s.meta = "2026.11.03 12:04"
        return s
    }

    func testPanelStaysInsideCanvas() {
        for (w, h) in [(1000.0, 1200.0), (3000.0, 4000.0), (4000.0, 3000.0)] {
            let L = WatermarkLayoutEngine.layout(width: w, height: h, style: style(), measurer: measurer)
            XCTAssertGreaterThanOrEqual(L.panel.x, 0, "画幅 \(w)x\(h) 左边越界")
            XCTAssertGreaterThanOrEqual(L.panel.y, 0, "画幅 \(w)x\(h) 上边越界")
            XCTAssertLessThanOrEqual(L.panel.x + L.panel.w, w + 0.001, "画幅 \(w)x\(h) 右边越界")
            XCTAssertLessThanOrEqual(L.panel.y + L.panel.h, h + 0.001, "画幅 \(w)x\(h) 下边越界")
        }
    }

    func testTextNeverExceedsPanelContentBox() {
        var s = style()
        s.widthPct = 40      // 强制收敛缩字
        let L = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        let contentRight = L.panel.x + L.panel.w - L.padX
        XCTAssertLessThanOrEqual(L.nameX + L.nameW, contentRight + 1, "名称溢出面板")
        XCTAssertLessThanOrEqual(L.paramsX + L.paramsW, contentRight + 1, "参数溢出面板")
        XCTAssertLessThanOrEqual(L.metaX + L.metaW, contentRight + 1, "时间溢出面板")
    }

    func testNarrowerWidthShrinksText() {
        var wide = style()
        wide.widthPct = 92
        var narrow = style()
        narrow.widthPct = 40

        let a = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: wide, measurer: measurer)
        let b = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: narrow, measurer: measurer)
        XCTAssertLessThan(b.nameSize, a.nameSize, "变窄后字号应缩小")
    }

    func testEmptySubtitleTakesNoSpace() {
        var s = style()
        s.subtitle = ""
        let a = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        XCTAssertFalse(a.subOn)
        XCTAssertEqual(a.subW, 0, accuracy: 0.0001)

        s.subtitle = "光影记录 · 此刻"
        let b = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        XCTAssertTrue(b.subOn)
        XCTAssertGreaterThan(b.panel.h, a.panel.h, "有副标题时面板应变高")
    }

    func testGlassHeightPercentScalesContentHeight() {
        var s = style()
        s.glassHeightPct = 100
        let full = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)

        s.glassHeightPct = 50
        let half = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)

        // panelH = contentH * pct + padY * 2，所以 50% 时正好是 (full - 2·padY)/2 + 2·padY
        let expected = (full.panel.h - full.padY * 2) * 0.5 + full.padY * 2
        XCTAssertEqual(half.panel.h, expected, accuracy: 0.001)
    }

    func testHorizontalPosition() {
        var s = style()
        s.posXPct = 0
        let left = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        s.posXPct = 100
        let right = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)

        XCTAssertEqual(left.panel.x, 38 * left.u, accuracy: 0.001)
        XCTAssertEqual(right.panel.x + right.panel.w, 1000 - 38 * right.u, accuracy: 0.001)
    }

    func testMissingLogoAssetsLeaveNoGap() {
        var s = style()
        s.showBrand = true
        let L = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        XCTAssertFalse(L.logoOn)
        XCTAssertEqual(L.logoW, 0, accuracy: 0.0001)
        XCTAssertEqual(L.sepX, 0, accuracy: 0.0001, "没有 logo 时不应画分隔线")
    }

    func testLogoAssetsExpandPanel() {
        struct FixedLogo: LogoProviding {
            func aspectRatio(brand: Int) -> Double? { 3.2 }
        }
        var s = style()
        let plain = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        let withLogo = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s,
                                                    measurer: measurer, logos: FixedLogo())
        XCTAssertTrue(withLogo.logoOn)
        XCTAssertGreaterThan(withLogo.logoW, 0)
        XCTAssertGreaterThan(withLogo.panel.w, plain.panel.w, "加了 logo 面板应更宽")
        XCTAssertGreaterThan(withLogo.sepX, 0, "有 logo 时应有分隔线")
    }

    func testDragKeepsAreaRoughlyConstant() {
        var s = style()
        s.widthPct = 80
        let base = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        let pulled = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s,
                                                  jsx: 1.0, measurer: measurer)
        let a0 = base.panel.w * base.panel.h
        let a1 = pulled.panel.w * pulled.panel.h
        XCTAssertGreaterThan(pulled.panel.w, base.panel.w, "横向拖动应变宽")
        XCTAssertEqual(a1 / a0, 1.0, accuracy: 0.06, "形变近似面积守恒")
    }

    func testManualHueProducesTextColor() {
        var s = style()
        s.textHue = 210
        s.textSat = 0.62
        s.textVal = 1
        let L = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        XCTAssertNotEqual(L.textRgb, 0xFFFFFF, "手动色相应覆盖白色文字")

        s.textHue = -1
        let auto = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: s, measurer: measurer)
        XCTAssertEqual(auto.textRgb, 0xFFFFFF)
    }

    func testGoldenGeometry() {
        // 合成度量下的固定数值，用来发现任何无意的几何改动
        let L = WatermarkLayoutEngine.layout(width: 1000, height: 1400, style: style(), measurer: measurer)
        XCTAssertEqual(L.u, 1.0, accuracy: 1e-9)
        XCTAssertEqual(L.padX, 32, accuracy: 1e-9)
        XCTAssertEqual(L.padY, 22, accuracy: 1e-9)
        XCTAssertEqual(L.nameSize, 24, accuracy: 0.5)
        XCTAssertEqual(L.metaSize, 12.5, accuracy: 0.5)
        XCTAssertEqual(L.panel.r, L.panel.h / 2, accuracy: 0.001, "radiusPct=100 时圆角为半高")
        XCTAssertEqual(L.panel.bevel, L.panel.h / 2 * 0.24, accuracy: 0.001)
    }

    func testPresetAppliesAllFields() {
        var s = WatermarkStyle()
        GlassPreset.frost.apply(to: &s)
        XCTAssertEqual(s.ior, 1.40, accuracy: 1e-9)
        XCTAssertEqual(s.roughPct, 76, accuracy: 1e-9)
        XCTAssertEqual(s.whitePct, 30, accuracy: 1e-9)
        XCTAssertEqual(s.edgePct, 110, accuracy: 1e-9)
        XCTAssertEqual(s.segments, 128)
    }
}
