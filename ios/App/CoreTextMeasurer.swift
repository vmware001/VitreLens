import UIKit
import VitreLensCore

/// 用 iOS 系统字体实现核心库的 `TextMeasuring`。
///
/// Android 用 Roboto，iOS 用 SF Pro，字形宽度天然不同；这里保证的是**度量口径**一致
/// （字号、字距、行高都按同一套规则算），所以版式规则可以复用，像素不会逐一相同。
struct CoreTextMeasurer: TextMeasuring {

    func font(size: Double, role: FontRole, bold: Bool) -> UIFont {
        let s = CGFloat(size)
        let weight: UIFont.Weight = bold ? .semibold : .regular
        switch role {
        case .mono:
            return UIFont.monospacedSystemFont(ofSize: s, weight: weight)
        case .serif:
            let base = UIFont.systemFont(ofSize: s, weight: weight)
            if let d = base.fontDescriptor.withDesign(.serif) {
                return UIFont(descriptor: d, size: s)
            }
            return base
        case .condensed:
            return UIFont.systemFont(ofSize: s, weight: weight, width: .condensed)
        case .sans:
            return UIFont.systemFont(ofSize: s, weight: weight)
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
