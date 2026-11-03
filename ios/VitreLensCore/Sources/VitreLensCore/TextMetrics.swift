import Foundation

/// 字体角色。iOS 上用 Core Text 实现，测试里用合成度量实现。
public enum FontRole: String, Codable {
    case sans
    case condensed
    case serif
    case mono
}

/// 与 Android `Paint.FontMetrics` 同约定：ascent 为负、descent 为正，
/// 行高 = descent - ascent。
public struct FontMetrics: Equatable {
    public var ascent: Double
    public var descent: Double
    public init(ascent: Double, descent: Double) {
        self.ascent = ascent
        self.descent = descent
    }
    public var height: Double { descent - ascent }
}

/// 文字度量抽象。版式计算本身与平台无关，只依赖这几个度量值，
/// 因此同一份几何代码可以同时被 iOS（Core Text）和单元测试（合成度量）使用。
public protocol TextMeasuring {
    /// 字宽；tracking 为 em 比例（对应 Android Paint.setLetterSpacing）
    func width(_ text: String, size: Double, tracking: Double, role: FontRole, bold: Bool) -> Double
    func metrics(size: Double, role: FontRole, bold: Bool) -> FontMetrics
}

/// 合成度量：按「字宽 ≈ 字号 × 系数」估算，仅用于测试几何关系。
/// 真实字形宽度差异（SF Pro vs Roboto）靠版式上的收敛与留白吸收，
/// 不能指望两端像素级一致。
public struct SyntheticMeasurer: TextMeasuring {
    public var widthFactor: Double
    public var ascentFactor: Double
    public var descentFactor: Double
    /// 中日韩字符比拉丁字符宽
    public var cjkWidthFactor: Double

    public init(widthFactor: Double = 0.52,
                ascentFactor: Double = 0.78,
                descentFactor: Double = 0.22,
                cjkWidthFactor: Double = 1.0) {
        self.widthFactor = widthFactor
        self.ascentFactor = ascentFactor
        self.descentFactor = descentFactor
        self.cjkWidthFactor = cjkWidthFactor
    }

    public func width(_ text: String, size: Double, tracking: Double, role: FontRole, bold: Bool) -> Double {
        var units = 0.0
        for scalar in text.unicodeScalars {
            units += scalar.value > 0x2E80 ? cjkWidthFactor : 1.0
        }
        let weight = bold ? 1.04 : 1.0
        return units * size * widthFactor * weight + Double(max(0, text.count - 1)) * tracking * size
    }

    public func metrics(size: Double, role: FontRole, bold: Bool) -> FontMetrics {
        FontMetrics(ascent: -size * ascentFactor, descent: size * descentFactor)
    }
}
