import Foundation

/// 品牌图形来源。公开构建里没有任何自绘品牌图标，`aspectRatio` 返回 nil，
/// 于是 logoW = 0、不占位——与 Android 公开版的行为一致。
public protocol LogoProviding {
    func aspectRatio(brand: Int) -> Double?
}

public struct NoLogos: LogoProviding {
    public init() {}
    public func aspectRatio(brand: Int) -> Double? { nil }
}

public struct Panel: Equatable {
    public var x: Double = 0
    public var y: Double = 0
    public var w: Double = 0
    public var h: Double = 0
    public var r: Double = 0
    public var bevel: Double = 0
    public var edgeW: Double = 0
}

/// 版式计算结果。字段名保持与 Android `WatermarkPainter.Layout` 一致，
/// 便于两端对照调试。
public struct WatermarkLayout: Equatable {
    public var panel = Panel()
    public var padX: Double = 0
    public var padY: Double = 0
    public var u: Double = 0
    public var nameSize: Double = 0
    public var metaSize: Double = 0
    public var subSize: Double = 0
    public var slant: Double = 0
    public var nameX: Double = 0
    public var nameBaseline: Double = 0
    public var nameW: Double = 0
    public var subX: Double = 0
    public var subBaseline: Double = 0
    public var subW: Double = 0
    public var dotR: Double = 0
    public var dotGap: Double = 0
    public var paramsX: Double = 0
    public var paramsBaseline: Double = 0
    public var paramsW: Double = 0
    public var metaX: Double = 0
    public var metaBaseline: Double = 0
    public var metaW: Double = 0
    public var paramsOn: Bool = false
    public var metaOn: Bool = false
    public var subOn: Bool = false
    public var logoX: Double = 0
    public var logoY: Double = 0
    public var logoW: Double = 0
    public var logoH: Double = 0
    public var logoOn: Bool = false
    public var darkText: Bool = false
    public var darkLogo: Bool = false
    public var textRgb: Int = 0xFFFFFF
    public var sepX: Double = 0
    public var sepTop: Double = 0
    public var sepBottom: Double = 0
    public var hasContent: Bool = false
    public init() {}
}

public enum WatermarkLayoutEngine {

    private static let trackName = 0.026
    private static let trackMeta = 0.02
    private static let trackSub = 0.02

    /// 与 Android 完全一致的版式计算。
    ///
    /// - Parameters:
    ///   - jsx/jsY: 水平／垂直形变 -1~1，仅预览拖动手势使用，导出恒为 0
    ///   - jp: 长按程度 0~1，整块鼓起
    public static func layout(width W: Double,
                              height H: Double,
                              style st: WatermarkStyle,
                              jsx: Double = 0,
                              jsY: Double = 0,
                              jp: Double = 0,
                              measurer: TextMeasuring = SyntheticMeasurer(),
                              logos: LogoProviding = NoLogos(),
                              darkText: Bool = false) -> WatermarkLayout {

        var L = WatermarkLayout()
        var st = st
        st.normalize()

        L.u = W / 1000
        if st.textHue >= 0 {
            L.textRgb = HSB.rgb(hue: st.textHue, saturation: st.textSat, value: st.textVal)
        }

        let u = L.u
        L.padX = 32 * u
        L.padY = 22 * u
        L.slant = st.italic ? 0.115 : 0
        L.darkText = darkText
        L.darkLogo = darkText

        var scale = 1.0
        // 最多收敛 6 次：水印变窄（比如视频用 95% 宽度）时，一次缩不够会溢出
        for attempt in 0..<6 {
            let nameSize = st.nameSize * u * scale
            let metaSize = st.metaSize * u * scale
            let subSize = nameSize * 0.42

            let nameW = measurer.width(st.name, size: nameSize, tracking: trackName, role: fontRole(st.font), bold: true)
                + (st.italic ? nameSize * 0.09 : 0)
            let fmN = measurer.metrics(size: nameSize, role: fontRole(st.font), bold: true)
            let nameH = fmN.height

            let subOn = st.showSubtitle && !st.subtitle.isEmpty
            let dotR = subSize * 0.17
            let dotGap = subSize * 0.55
            let subW = subOn
                ? dotR * 2 + dotGap
                    + measurer.width(st.subtitle, size: subSize, tracking: trackSub, role: fontRole(st.font), bold: false)
                    + (st.italic ? subSize * 0.1 : 0)
                : 0
            let fmS = measurer.metrics(size: subSize, role: fontRole(st.font), bold: false)
            let subH = fmS.height

            let subGap = nameSize * 0.24
            let leftW = max(nameW, subW)
            let leftH = nameH + (subOn ? subGap + subH : 0)

            let paramsOn = st.showParams && !st.params.isEmpty
            let metaOn = st.showMeta && !st.meta.isEmpty
            let paramsW = paramsOn
                ? measurer.width(st.params, size: metaSize, tracking: trackMeta, role: .sans, bold: false)
                    + (st.italic ? metaSize * 0.08 : 0)
                : 0
            let metaW = metaOn
                ? measurer.width(st.meta, size: metaSize, tracking: trackMeta, role: .sans, bold: false)
                    + (st.italic ? metaSize * 0.08 : 0)
                : 0
            let fmM = measurer.metrics(size: metaSize, role: .sans, bold: false)
            let lineH = fmM.height
            let lineGap = metaSize * 0.62

            // 哈苏是 10:1 的横排字标，按普通字号算高度只有 0.1 倍，小到看不清。
            // Logo 高度固定以 24u 为基准（等于默认字号），不跟机型字号变。
            let brandScale: Double
            switch st.brand {
            case 6: brandScale = 1.45      // Apple 小尺寸预览需要更大轮廓
            case 1: brandScale = 1.35      // Leica 同理
            case 2: brandScale = 1.2
            default: brandScale = 1.0
            }
            let logoH = 24 * u * scale * brandScale * (st.logoSizePct / 100)
            let logoW = st.showBrand ? logoWidth(brand: st.brand, height: logoH, logos: logos) : 0

            let gapA = logoW > 0 ? nameSize * 0.55 : 0
            // logo 与参数之间留出竖线的呼吸空间，否则线会紧贴两边
            let gapB = logoW > 0 ? nameSize * 1.05 : 0
            let rightW = max(paramsW, metaW)
            let rightH = (paramsOn ? lineH : 0)
                + (paramsOn && metaOn ? lineGap : 0)
                + (metaOn ? lineH : 0)
            let contentW = st.logoRight
                ? leftW + gapA + rightW + gapB + logoW
                : leftW + gapA + logoW + gapB + rightW

            let margin = 38 * u
            var panelW = max(W * (st.widthPct / 100), contentW + L.padX * 2)
            panelW = min(panelW, W - margin * 2)
            let availW = panelW - L.padX * 2

            if contentW > availW + 0.5 && attempt < 5 {
                // 一次就按比例缩到位（再留 2% 余量）
                scale *= min(0.97, availW / contentW * 0.98)
                continue
            }

            let contentH = max(max(leftH, rightH), logoW > 0 ? logoH : 0)
            var panelH = contentH * (st.glassHeightPct / 100) + L.padY * 2
            // 水平位置自由调节：0 贴左，100 贴右，50 居中
            let minX = margin
            let maxX = max(minX, W - margin - panelW)
            let fx = max(0, min(100, st.posXPct)) / 100
            var panelX = minX + (maxX - minX) * fx

            let bottomGap = st.gapPct * W / 100
            var panelY = max(0, H - bottomGap - panelH)

            // 拖动手感：像拉一块橡皮泥，左右拖变长变细、上下拖变短变宽，总面积守恒
            if jsx != 0 || jsY != 0 || jp != 0 {
                let baseW = panelW, baseH = panelH
                var cx = panelX + baseW / 2
                var cy = panelY + baseH / 2

                let ax = abs(jsx), ay = abs(jsY)
                let stretch = max(0.55, min(1.9, 1 + ax * 0.24 - ay * 0.22))
                let wScale = stretch * (1 + jp * 0.14)
                let hScale = (1 / stretch) * (1 + jp * 0.26)
                panelW = baseW * wScale
                panelH = baseH * hScale

                // 位移只用到剩余空隙：拉伸时一侧本来就会顶到边
                let slackX = max(0, (W - panelW) / 2) * 0.9
                let slackY = max(0, (H - panelH) / 2) * 0.9
                cx += max(-slackX, min(slackX, jsx * baseW * 0.06))
                cy += max(-slackY, min(slackY, jsY * baseH * 0.18))
                panelX = cx - panelW / 2
                panelY = cy - panelH / 2
            }

            L.panel.x = panelX
            L.panel.y = panelY
            L.panel.w = panelW
            L.panel.h = panelH
            L.panel.r = (panelH / 2) * (st.radiusPct / 100)
            L.panel.bevel = (panelH / 2) * (st.depthPct / 100)
            L.panel.edgeW = (panelH / 2) * (st.depthPct / 100)

            L.nameSize = nameSize
            L.metaSize = metaSize
            L.subSize = subSize
            L.nameW = nameW
            L.subW = subW
            L.dotR = dotR
            L.dotGap = dotGap
            L.subOn = subOn
            L.paramsW = paramsW
            L.metaW = metaW
            L.paramsOn = paramsOn
            L.metaOn = metaOn
            L.logoW = logoW
            L.logoH = logoH
            L.logoOn = logoW > 0
            L.hasContent = !st.name.isEmpty || subOn || paramsOn || metaOn || L.logoOn

            let cy = panelY + panelH / 2
            let leftTop = cy - leftH / 2
            L.nameX = panelX + L.padX
            L.nameBaseline = leftTop - fmN.ascent
            if subOn {
                L.subBaseline = leftTop + nameH + subGap - fmS.ascent
                L.subX = panelX + L.padX
            }

            // 右侧块的右边界。Logo 在参数右边时二者组成整体，
            // logoPosPct 控制整体在剩余空间中的位置，而不是把 Logo 钉死在右缘。
            var rightEdge = panelX + panelW - L.padX
            if st.logoRight && logoW > 0 {
                let maxEdge = rightEdge - logoW - gapB
                let minEdge = panelX + L.padX + leftW + gapA + rightW
                let travel = max(0, maxEdge - minEdge)
                let lfx = max(0, min(100, st.logoPosPct)) / 100
                rightEdge = maxEdge - travel * (1 - lfx)
            }

            if paramsOn || metaOn {
                let rightTop = cy - rightH / 2
                var y = rightTop
                if paramsOn {
                    L.paramsBaseline = y - fmM.ascent
                    L.paramsX = rightEdge - paramsW
                    y += lineH + lineGap
                }
                if metaOn {
                    L.metaBaseline = y - fmM.ascent
                    L.metaX = rightEdge - metaW
                }
            }

            if L.logoOn {
                let leftEnd = panelX + L.padX + leftW
                let rightStart = panelX + panelW - L.padX - rightW
                let space = rightStart - leftEnd
                var gx: Double
                if st.logoRight {
                    // 贴住面板右内边界，参数已经让出 logoW + gapB 的宽度
                    gx = rightEdge + gapB
                } else {
                    // 0 紧贴名称，100 紧贴参数；两端各留 gapA / gapB
                    let lfx = max(0, min(100, st.logoPosPct)) / 100
                    let from = leftEnd + gapA
                    let span = rightStart - gapB - from - logoW
                    gx = span > 0 ? from + span * lfx : from
                }
                L.logoX = gx
                L.logoY = cy - logoH / 2

                // 白色短竖线永远落在 logo 与参数的正中
                if rightW > 0 && (st.logoRight || space > logoW) {
                    L.sepX = st.logoRight
                        ? (rightEdge + gx) / 2
                        : (gx + logoW + rightStart) / 2
                    let half = min(contentH, nameSize * 1.3) * 0.5
                    L.sepTop = cy - half
                    L.sepBottom = cy + half
                }
            }
            return L
        }
        return L
    }

    private static func fontRole(_ name: String) -> FontRole {
        FontRole(rawValue: name) ?? .sans
    }

    /// 与 Android `logoWidth` 一致：没有素材就返回 0，不留空白占位
    private static func logoWidth(brand: Int, height h: Double, logos: LogoProviding) -> Double {
        guard brand != 0, let aspect = logos.aspectRatio(brand: brand) else { return 0 }
        let maxW = h * (brand == 2 ? 5.5 : 2.2)
        return min(maxW, h * aspect)
    }
}
