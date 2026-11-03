import UIKit
import VitreLensCore

/// 无人值守渲染快照。
///
/// 设置环境变量 `VITRELENS_SNAPSHOT=1` 启动时，会用一张合成测试图把四个预设各渲染一遍，
/// 写到 Documents 后退出。CI 里配合 `xcrun simctl` 就能拿到真实像素，
/// 用来核对 iOS 与 Android 的玻璃观感是否一致——不用人肉点界面。
enum Snapshot {

    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["VITRELENS_SNAPSHOT"] == "1" else { return }

        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let size = CGSize(width: 1200, height: 1600)
        guard let bg = syntheticPhoto(size: size) else {
            try? "no-image".write(to: dir.appendingPathComponent("error.txt"), atomically: true, encoding: .utf8)
            exit(2)
        }

        for preset in GlassPreset.allCases {
            var st = WatermarkStyle()
            st.name = "OPPO Find X10 Pro Max"
            st.params = "23mm f/1.8 1/287s ISO50"
            st.meta = "2026.11.03 12:04"
            preset.apply(to: &st)

            let out = WatermarkCanvas.composite(image: bg, canvasSize: size, style: st, measurer: CoreTextMeasurer())
            if let data = out.jpegData(compressionQuality: 0.94) {
                try? data.write(to: dir.appendingPathComponent("preset-\(preset.rawValue)-\(preset.title).jpg"))
            }
        }

        // 未应用任何样式的一张，作为对照
        if let data = bg.jpegData(compressionQuality: 0.94) {
            try? data.write(to: dir.appendingPathComponent("source.jpg"))
        }

        let metal = GlassRenderer.shared.isAvailable ? "metal" : "fallback"
        try? metal.write(to: dir.appendingPathComponent("backend.txt"), atomically: true, encoding: .utf8)
        exit(0)
    }

    /// 合成测试图：渐变底 + 彩色圆 + 细网格 + 高光条。
    /// 网格线是判断折射有没有生效的关键——位移正确时线会跟着弯，位移错了会断或折角。
    static func syntheticPhoto(size: CGSize) -> UIImage? {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            let w = size.width, h = size.height

            // 斜向渐变
            let colors = [UIColor(red: 0.11, green: 0.16, blue: 0.30, alpha: 1).cgColor,
                          UIColor(red: 0.55, green: 0.38, blue: 0.30, alpha: 1).cgColor,
                          UIColor(red: 0.92, green: 0.80, blue: 0.62, alpha: 1).cgColor] as CFArray
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                  colors: colors, locations: [0, 0.55, 1]) {
                c.drawLinearGradient(g,
                                     start: CGPoint(x: 0, y: 0),
                                     end: CGPoint(x: w, y: h),
                                     options: [])
            }

            // 彩色圆：高饱和色在 BT.709 下最容易看出色散
            let circles: [(CGFloat, CGFloat, CGFloat, UIColor)] = [
                (0.22, 0.18, 0.10, UIColor(red: 0.90, green: 0.25, blue: 0.25, alpha: 0.9)),
                (0.74, 0.30, 0.13, UIColor(red: 0.20, green: 0.75, blue: 0.55, alpha: 0.9)),
                (0.35, 0.62, 0.16, UIColor(red: 0.95, green: 0.85, blue: 0.25, alpha: 0.85)),
                (0.80, 0.72, 0.09, UIColor(red: 0.35, green: 0.45, blue: 0.95, alpha: 0.9))
            ]
            for (x, y, r, color) in circles {
                c.setFillColor(color.cgColor)
                c.fillEllipse(in: CGRect(x: (x - r) * w, y: (y - r) * h,
                                         width: r * 2 * w, height: r * 2 * h))
            }

            // 细网格
            c.setStrokeColor(UIColor.white.withAlphaComponent(0.55).cgColor)
            c.setLineWidth(max(1, w / 600))
            var x: CGFloat = 0
            while x < w {
                c.move(to: CGPoint(x: x, y: 0)); c.addLine(to: CGPoint(x: x, y: h)); x += w / 24
            }
            var y: CGFloat = 0
            while y < h {
                c.move(to: CGPoint(x: 0, y: y)); c.addLine(to: CGPoint(x: w, y: y)); y += h / 24
            }
            c.strokePath()

            // 底部亮条：检查投影与边缘亮线
            c.setFillColor(UIColor.white.withAlphaComponent(0.75).cgColor)
            c.fill(CGRect(x: 0, y: h * 0.88, width: w, height: h * 0.04))
        }
    }
}
