import Foundation

/// 内置玻璃预设。数值逐项取自 Android `ControlPanel.applyPreset`，
/// 顺序与那里的 `float[]` 完全一致，方便逐字段核对。
public enum GlassPreset: Int, CaseIterable {
    case standard = 0   // 常规
    case clear = 1      // 清透
    case frost = 2      // 磨砂
    case thick = 3      // 厚玻璃

    public var title: String {
        switch self {
        case .standard: return "常规"
        case .clear: return "清透"
        case .frost: return "磨砂"
        case .thick: return "厚玻璃"
        }
    }

    // ior, radius, depth, thickness, rough, disp, refl, trans, gloss, shadow, white, warp, segments, edge
    private var values: (Double, Double, Double, Double, Double, Double, Double,
                         Double, Double, Double, Double, Double, Int, Double) {
        switch self {
        case .standard:
            // 系统默认的「透明」档：模糊负责材质、厚度克制、反射与色散都收着
            return (1.46, 100, 24, 36, 20, 6, 28, 98, 28, 18, 0, 0, 128, 140)
        case .clear:
            // 基本清晰，全靠边缘折射认出来
            return (1.46, 100, 28, 44, 4, 8, 32, 100, 32, 14, 0, 0, 128, 160)
        case .frost:
            // 「色调」档：不透明度提高，实色随背景明暗在近白/近黑之间切换
            return (1.40, 100, 30, 44, 76, 6, 30, 86, 24, 20, 30, 0, 128, 110)
        case .thick:
            // 强风格预设：depth 已经很大，边缘折射收着
            return (1.72, 100, 72, 88, 8, 46, 100, 100, 82, 58, 0, 130, 128, 60)
        }
    }

    /// 把预设写进样式对象（等价于 Android 的 applyPreset）
    public func apply(to style: inout WatermarkStyle) {
        let v = values
        style.ior = v.0
        style.radiusPct = v.1
        style.depthPct = v.2
        style.thicknessPct = v.3
        style.roughPct = v.4
        style.dispPct = v.5
        style.reflPct = v.6
        style.transPct = v.7
        style.glossPct = v.8
        style.shadowPct = v.9
        style.whitePct = v.10
        style.warpPct = v.11
        style.segments = v.12
        style.edgePct = v.13
        style.tintOn = true
    }
}
