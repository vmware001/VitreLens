import Foundation

/// 与 Android `WatermarkPainter.Style`、网页版 `LG.app.state` 一一对应的参数模型。
///
/// 字段名和默认值必须与 Android 完全一致：三端共用同一个预设 JSON 时，
/// 任何一个默认值不同都会让「同一张照片、同一个预设」在三端出现不同结果。
public struct WatermarkStyle: Codable, Equatable {

    public var name: String = ""
    public var subtitle: String = ""
    public var params: String = ""
    public var meta: String = ""

    // 默认允许副标题；subtitle 为空时 layout 视为未启用，不占空间
    public var showSubtitle: Bool = true
    public var showBrand: Bool = true
    public var showParams: Bool = true
    public var showMeta: Bool = true

    /// 0 无 / 1 徕卡 / 2 哈苏 / 3 哈苏H / 4 蔡司 / 5 理光 / 6 苹果 / 7 自定义
    public var brand: Int = 2

    public var autoText: Bool = true
    public var themeDark: Bool = false
    /// 文字色相 0~360；-1 表示不启用
    public var textHue: Double = -1
    public var textSat: Double = 0.62
    public var textVal: Double = 1.0
    /// 磨砂实色：0 自动 / 1 深色 / 2 浅色
    public var frostTone: Int = 0

    public var pixelUnits: Bool = false
    /// 水平位置：0 贴左，50 居中，100 贴右
    public var posXPct: Double = 50
    public var align: String = "center"
    /// sans / condensed / serif / mono
    public var font: String = "sans"
    /// liquid / frost / solid
    public var nameMaterial: String = "liquid"

    public var widthPct: Double = 92
    public var logoSizePct: Double = 100
    public var logoPosPct: Double = 100
    public var logoRight: Bool = true
    public var logoPos: String = "right"
    public var glassHeightPct: Double = 100
    public var gapPct: Double = 3.8
    public var nameSize: Double = 24
    public var metaSize: Double = 12.5
    public var italic: Bool = false

    // 玻璃光学参数（对齐 @specy/liquid-glass 的 GlassStyle）
    public var ior: Double = 1.46
    public var radiusPct: Double = 100
    public var depthPct: Double = 24
    public var thicknessPct: Double = 36
    public var roughPct: Double = 20
    public var dispPct: Double = 6
    public var edgePct: Double = 140
    public var warpPct: Double = 0
    public var reflPct: Double = 28
    public var transPct: Double = 98
    public var glossPct: Double = 28
    public var shadowPct: Double = 18
    public var whitePct: Double = 0
    public var tintOn: Bool = true
    public var segments: Int = 128

    public var videoGapShift: Double = 2
    public var videoWidthScale: Double = 95

    public init() {}

    /// 把空字符串归一化，避免 layout 里到处判空
    mutating func normalize() {
        if name.isEmpty { name = "" }
    }
}

/// 文字主色的 HSV → RGB。与 Android `Color.HSVToColor` 同义，
/// 只是不依赖 android.graphics，方便单元测试。
public enum HSB {
    public static func rgb(hue: Double, saturation: Double, value: Double) -> Int {
        let h = ((hue.truncatingRemainder(dividingBy: 360)) + 360)
            .truncatingRemainder(dividingBy: 360)
        let s = min(1, max(0, saturation))
        let v = min(1, max(0, value))

        let sector = h / 60
        let i = Int(floor(sector)) % 6
        let f = sector - floor(sector)
        let p = v * (1 - s)
        let q = v * (1 - s * f)
        let t = v * (1 - s * (1 - f))

        let r: Double, g: Double, b: Double
        switch i {
        case 0: (r, g, b) = (v, t, p)
        case 1: (r, g, b) = (q, v, p)
        case 2: (r, g, b) = (p, v, t)
        case 3: (r, g, b) = (p, q, v)
        case 4: (r, g, b) = (t, p, v)
        default: (r, g, b) = (v, p, q)
        }
        let ri = Int((r * 255).rounded())
        let gi = Int((g * 255).rounded())
        let bi = Int((b * 255).rounded())
        return (ri << 16) | (gi << 8) | bi
    }
}
