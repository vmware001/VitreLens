import UIKit
import VitreLensCore

/// 用与 Android 完全相同的随包字体实现核心库的 `TextMeasuring`。
///
/// 字号、字距、行高及字体文件均一致；字体引擎的细微栅格化差异仍可能存在，
/// 但不再因为 SF Pro / Roboto 字形不同而改变水印宽度与换行收敛。
struct CoreTextMeasurer: TextMeasuring {

    func font(size: Double, role: FontRole, bold: Bool) -> UIFont {
        let s = CGFloat(size)
        let postScriptName: String
        switch role {
        case .mono: postScriptName = "JetBrainsMono-Regular"
        case .serif: postScriptName = "PlayfairDisplay-Regular"
        case .condensed: postScriptName = "Oswald-Regular"
        case .sans: postScriptName = "Inter-Regular"
        }
        if let base = UIFont(name: postScriptName, size: s) {
            guard bold else { return base }
            // Android 对随包 Regular 字体调用 Typeface.create(..., BOLD)；
            // iOS 同样请求粗体描述符，字体没有独立粗体时由系统合成。
            let traits = base.fontDescriptor.symbolicTraits.union(.traitBold)
            if let descriptor = base.fontDescriptor.withSymbolicTraits(traits) {
                return UIFont(descriptor: descriptor, size: s)
            }
            return base
        }

        // 资源异常时仍可用，不让整个导出失败；正常安装包不会进入这里。
        let weight: UIFont.Weight = bold ? .semibold : .regular
        switch role {
        case .mono: return UIFont.monospacedSystemFont(ofSize: s, weight: weight)
        case .condensed: return UIFont.systemFont(ofSize: s, weight: weight, width: .condensed)
        case .serif:
            let base = UIFont.systemFont(ofSize: s, weight: weight)
            if let d = base.fontDescriptor.withDesign(.serif) {
                return UIFont(descriptor: d, size: s)
            }
            return base
        case .sans: return UIFont.systemFont(ofSize: s, weight: weight)
        }
    }

    private func attributes(size: Double, tracking: Double, role: FontRole, bold: Bool) -> [NSAttributedString.Key: Any] {
        [
            .font: font(size: size, role: role, bold: bold),
            .kern: CGFloat(tracking * size)
        ]
    }

    func width(_ text: String, size: Double, tracking: Double, role: FontRole, bold: Bool) -> Double {
        guard !text.isEmpty else { return 0 }
        let s = NSAttributedString(string: text, attributes: attributes(size: size, tracking: tracking, role: role, bold: bold))
        return Double(s.size().width)
    }

    func metrics(size: Double, role: FontRole, bold: Bool) -> FontMetrics {
        let f = font(size: size, role: role, bold: bold)
        // Android 的 ascent 是负数，这里保持同一约定，几何算法不需要区分平台
        return FontMetrics(ascent: -Double(f.ascender), descent: Double(-f.descender))
    }
}
