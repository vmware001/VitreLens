import UIKit
import VitreLensCore

/// 水印绘制。预览与导出调用同一个 `draw`，尺寸不同而已。
///
/// 玻璃面板目前是 Core Graphics 近似（半透明底 + 描边 + 投影）。
/// Metal 版会替换 `drawPanel`，但文字、Logo、分隔线的几何与绘制不再变动。
enum WatermarkCanvas {

    /// 把水印画到当前 UIKit 绘图上下文的 (0,0)-(canvasSize) 区域
    static func draw(canvasSize: CGSize, style: WatermarkStyle, measurer: CoreTextMeasurer) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let L = WatermarkLayoutEngine.layout(width: Double(canvasSize.width),
                                             height: Double(canvasSize.height),
                                             style: style,
                                             measurer: measurer)
        guard L.hasContent else { return }

        drawPanel(L, style: style, in: c)
        drawTexts(L, style: style, measurer: measurer)
    }

    /// 合成完整图片（照片 + 玻璃 + 文字）
    ///
    /// 有 Metal 时走真实折射渲染，文字仍由 Core Graphics 画在最上层；
    /// 取不到 Metal 设备时回退到 Core Graphics 近似面板，保证任何设备都能导出。
    static func composite(image: UIImage,
                          canvasSize: CGSize,
                          style: WatermarkStyle,
                          measurer: CoreTextMeasurer) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true

        let L = WatermarkLayoutEngine.layout(width: Double(canvasSize.width),
                                             height: Double(canvasSize.height),
                                             style: style,
                                             measurer: measurer)

        // 预览与导出都必须先缩放到画布尺寸再渲染，否则玻璃参数（按像素算）会不一致
        let photo = resizedCGImage(image, to: canvasSize)
        let glassImage: CGImage? = {
            guard let photo, L.hasContent else { return nil }
            return GlassRenderer.shared.renderGlass(photo: photo,
                                                    style: style,
                                                    layout: L,
                                                    themeDark: isDarkBackdrop(photo))
        }()

        return UIGraphicsImageRenderer(size: canvasSize, format: format).image { _ in
            if let glassImage {
                UIImage(cgImage: glassImage).draw(in: CGRect(origin: .zero, size: canvasSize))
                drawTexts(L, style: style, measurer: measurer)
            } else {
                image.draw(in: CGRect(origin: .zero, size: canvasSize))
                draw(canvasSize: canvasSize, style: style, measurer: measurer)
            }
        }
    }

    /// 缩放到目标尺寸的 CGImage（预览用，导出时尺寸相同则走快速路径）
    static func resizedCGImage(_ image: UIImage, to size: CGSize) -> CGImage? {
        let w = Int(size.width.rounded()), h = Int(size.height.rounded())
        guard w > 0, h > 0 else { return nil }
        if let cg = image.cgImage, cg.width == w, cg.height == h { return cg }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let scaled = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return scaled.cgImage
    }

    /// 背景整体偏暗时，「色调」档的实色应取近黑——对应系统的深色外观。
    /// 用 16×16 缩略图取平均亮度，够快也够稳。
    static func isDarkBackdrop(_ image: CGImage) -> Bool {
        let n = 16
        var data = [UInt8](repeating: 0, count: n * n * 4)
        guard let ctx = CGContext(data: &data, width: n, height: n, bitsPerComponent: 8,
                                  bytesPerRow: n * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return false
        }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
        var sum = 0.0
        for i in stride(from: 0, to: data.count, by: 4) {
            // 感知亮度
            let r = Double(data[i]), g = Double(data[i + 1]), b = Double(data[i + 2])
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        return sum / Double(n * n) < 0.45
    }

    // MARK: - 玻璃面板（Core Graphics 近似版）

    private static func drawPanel(_ L: WatermarkLayout, style st: WatermarkStyle, in c: CGContext) {
        let rect = CGRect(x: L.panel.x, y: L.panel.y, width: L.panel.w, height: L.panel.h)
        let radius = CGFloat(L.panel.r)
        let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)

        let opacity = CGFloat(max(0, min(100, st.whitePct)) / 100)
        let frost = CGFloat(max(0, min(100, st.roughPct)) / 100)

        c.saveGState()
        c.setShadow(offset: CGSize(width: 0, height: CGFloat(L.u) * 6),
                    blur: CGFloat(L.u) * 18,
                    color: UIColor.black.withAlphaComponent(CGFloat(st.shadowPct) / 100 * 0.5).cgColor)
        // 底色：磨砂越重越接近实色，透射越高越接近全透明
        let base = 0.06 + frost * 0.26 + opacity * 0.55
        c.setFillColor(UIColor.white.withAlphaComponent(min(0.92, base)).cgColor)
        c.addPath(path)
        c.fillPath()
        c.restoreGState()

        // 内侧高光描边，模拟玻璃边缘受光
        c.saveGState()
        c.addPath(path)
        c.setStrokeColor(UIColor.white.withAlphaComponent(0.55 * CGFloat(st.reflPct) / 100 + 0.12).cgColor)
        c.setLineWidth(max(0.6, CGFloat(L.u) * 2))
        c.strokePath()
        c.restoreGState()
    }

    // MARK: - 文字层

    private static func drawTexts(_ L: WatermarkLayout, style st: WatermarkStyle, measurer: CoreTextMeasurer) {
        let textColor = UIColor(rgb: L.textRgb)
        let shadow = L.darkText
            ? UIColor.white.withAlphaComponent(0.25)
            : UIColor.black.withAlphaComponent(0.4)

        if !st.name.isEmpty {
            let f = measurer.font(size: L.nameSize, role: role(st.font), bold: true)
            draw(st.name,
                 x: L.nameX,
                 baseline: L.nameBaseline,
                 fontSize: L.nameSize,
                 tracking: 0.026,
                 font: f,
                 color: textColor,
                 alpha: 1,
                 slant: L.slant,
                 shadow: shadow)
        }

        if L.subOn {
            let f = measurer.font(size: L.subSize, role: role(st.font), bold: false)
            // 副标题前面那个小圆点
            if let c = UIGraphicsGetCurrentContext() {
                c.saveGState()
                c.setFillColor(textColor.withAlphaComponent(0.8).cgColor)
                let cx = CGFloat(L.subX + L.dotR)
                let cy = CGFloat(L.subBaseline - L.subSize * 0.34)
                c.fillEllipse(in: CGRect(x: cx - CGFloat(L.dotR), y: cy - CGFloat(L.dotR),
                                         width: CGFloat(L.dotR) * 2, height: CGFloat(L.dotR) * 2))
                c.restoreGState()
            }
            draw(st.subtitle,
                 x: L.subX + L.dotR * 2 + L.dotGap,
                 baseline: L.subBaseline,
                 fontSize: L.subSize,
                 tracking: 0.02,
                 font: f,
                 color: textColor,
                 alpha: 0.8,
                 slant: L.slant * 0.8,
                 shadow: shadow)
        }

        let metaFont = measurer.font(size: L.metaSize, role: .sans, bold: false)
        if L.paramsOn {
            draw(st.params,
                 x: L.paramsX,
                 baseline: L.paramsBaseline,
                 fontSize: L.metaSize,
                 tracking: 0.02,
                 font: metaFont,
                 color: textColor,
                 alpha: 1,
                 slant: L.slant * 0.7,
                 shadow: shadow)
        }
        if L.metaOn {
            draw(st.meta,
                 x: L.metaX,
                 baseline: L.metaBaseline,
                 fontSize: L.metaSize,
                 tracking: 0.02,
                 font: metaFont,
                 color: textColor,
                 alpha: 0.65,
                 slant: L.slant * 0.7,
                 shadow: shadow)
        }

        // Logo 与参数之间的分隔竖线
        if L.sepX > 0, let c = UIGraphicsGetCurrentContext() {
            c.saveGState()
            c.setStrokeColor(textColor.withAlphaComponent(0.35).cgColor)
            c.setLineWidth(max(0.5, CGFloat(L.u) * 1.4))
            c.move(to: CGPoint(x: L.sepX, y: L.sepTop))
            c.addLine(to: CGPoint(x: L.sepX, y: L.sepBottom))
            c.strokePath()
            c.restoreGState()
        }
    }

    private static func role(_ name: String) -> FontRole {
        FontRole(rawValue: name) ?? .sans
    }

    /// 以基线为锚点绘制一段文字；斜体用切变模拟，和 Android 的 canvas.skew 对应。
    private static func draw(_ text: String,
                             x: Double,
                             baseline: Double,
                             fontSize: Double,
                             tracking: Double,
                             font: UIFont,
                             color: UIColor,
                             alpha: CGFloat,
                             slant: Double,
                             shadow: UIColor) {
        guard !text.isEmpty, let c = UIGraphicsGetCurrentContext() else { return }
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color.withAlphaComponent(alpha),
            .kern: CGFloat(tracking * fontSize),
            .shadow: {
                let s = NSShadow()
                s.shadowColor = shadow
                s.shadowBlurRadius = CGFloat(max(1, fontSize * 0.14))
                s.shadowOffset = CGSize(width: 0, height: CGFloat(fontSize * 0.04))
                return s
            }()
        ]
        let str = NSAttributedString(string: text, attributes: attrs)
        let top = CGFloat(baseline) - font.ascender

        c.saveGState()
        c.translateBy(x: CGFloat(x), y: top)
        if slant != 0 {
            c.concatenate(CGAffineTransform(a: 1, b: 0, c: CGFloat(-slant), d: 1, tx: 0, ty: 0))
        }
        str.draw(at: .zero)
        c.restoreGState()
    }
}

extension UIColor {
    convenience init(rgb: Int) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}
