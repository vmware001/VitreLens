import Foundation

/// 视频色彩管线中与平台无关的部分：HDR→SDR 色调映射、BT.2020→BT.709 色域转换。
///
/// 这些公式直接对应 Android `YuvConverter`，逐行移植；数值必须一致，
/// 否则同一段 HLG 视频在两端会呈现不同的亮度与饱和度。
public enum ColorPipeline {

    /// Android `MediaFormat.COLOR_TRANSFER_*`
    public enum Transfer: Int {
        case linear = 1
        case sdrVideo = 3
        case pq = 6        // SMPTE ST 2084
        case hlg = 7       // ARIB STD-B67

        public var isHDR: Bool { self == .pq || self == .hlg }
    }

    /// Android `MediaFormat.COLOR_STANDARD_*`（注意 BT.601 PAL 是 2，不是 4）
    public enum Standard: Int {
        case bt709 = 1
        case bt601PAL = 2
        case bt601NTSC = 4
        case bt2020 = 6
    }

    /// Android `MediaFormat.COLOR_RANGE_*`：1 = limited，2 = full
    public enum Range: Int {
        case limited = 1
        case full = 2
    }

    // MARK: - 色调映射

    /// HDR 传输曲线 → sRGB，256 项查找表。
    ///
    /// HLG 用 ARIB STD-B67 的反 OETF，白点取 82% 信号（实测与标准 HLG→sRGB 对齐）；
    /// PQ 用 SMPTE ST 2084，参考白取 203nit。归一后做一次 0.9 起点、0.35 斜率的软拐点压缩，
    /// 避免高光直接切平，再用 sRGB 曲线重新编码。
    public static func toneMapLUT(transfer: Transfer) -> [UInt8] {
        let hlgA = 0.17883277, hlgB = 0.28466892, hlgC = 0.55991073
        let hlgWhite = (exp((0.82 - hlgC) / hlgA) + hlgB) / 12.0
        let pqM1 = 0.1593017578125, pqM2 = 78.84375
        let pqC1 = 0.8359375, pqC2 = 18.8515625, pqC3 = 18.6875

        var table = [UInt8](repeating: 0, count: 256)
        for i in 0..<256 {
            let e = Double(i) / 255.0
            var lin: Double
            if transfer == .hlg {
                lin = e <= 0.5
                    ? (e * e / 3.0)
                    : ((exp((e - hlgC) / hlgA) + hlgB) / 12.0)
                lin /= hlgWhite
            } else {
                let p = pow(e, 1.0 / pqM2)
                let den = pqC2 - pqC3 * p
                let num = max(p - pqC1, 0.0)
                let nits = den > 1e-9 ? pow(num / den, 1.0 / pqM1) * 10000.0 : 10000.0
                lin = nits / 203.0
            }
            if lin < 0 { lin = 0 }
            // 软拐点：0.9 以上才收敛，起点定在 0.8 会把天空、白墙一起推成纯白
            if lin > 0.9 { lin = 0.9 + 0.1 * (1.0 - exp(-(lin - 0.9) / 0.35)) }
            if lin > 1 { lin = 1 }

            let s = lin <= 0.0031308 ? 12.92 * lin : 1.055 * pow(lin, 1 / 2.4) - 0.055
            let v = Int((s * 255.0).rounded())
            table[i] = UInt8(max(0, min(255, v)))
        }
        return table
    }

    // MARK: - 色域

    /// sRGB → 线性光，定点 12 位（×4096）
    public static let srgbToLinear: [Int] = {
        (0..<256).map { i in
            let s = Double(i) / 255.0
            let v = s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
            return Int((v * 4096.0).rounded())
        }
    }()

    /// 线性光 → sRGB，定点 12 位（长度 4097，索引即 0…4096）
    public static let linearToSrgb: [UInt8] = {
        (0...4096).map { i in
            let v = Double(i) / 4096.0
            let s = v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1 / 2.4) - 0.055
            return UInt8(max(0, min(255, Int((s * 255.0).rounded()))))
        }
    }()

    /// 在线性光里做 BT.2020 → BT.709，定点 12 位，避免浮点热点。
    /// 返回打包的 0xRRGGBB。
    public static func gamutPack(r: Int, g: Int, b: Int) -> Int {
        let rl = srgbToLinear[clamp8(r)]
        let gl = srgbToLinear[clamp8(g)]
        let bl = srgbToLinear[clamp8(b)]
        var nr = (6801 * rl - 2407 * gl - 298 * bl + 2048) >> 12
        var ng = (-510 * rl + 4640 * gl - 34 * bl + 2048) >> 12
        var nb = (-75 * rl - 412 * gl + 4581 * bl + 2048) >> 12
        nr = max(0, min(4096, nr))
        ng = max(0, min(4096, ng))
        nb = max(0, min(4096, nb))
        return (Int(linearToSrgb[nr]) << 16) | (Int(linearToSrgb[ng]) << 8) | Int(linearToSrgb[nb])
    }

    /// YCbCr → RGB 的定点系数。未标注时按 BT.709 处理（绝大多数素材 HD 以上）。
    public struct YUVMatrix {
        public var yScale: Int
        public var rCr: Int
        public var gCb: Int
        public var gCr: Int
        public var bCb: Int
        public var yOffset: Int

        public init(standard: Standard, range: Range) {
            var std = standard
            if std != .bt601PAL && std != .bt601NTSC && std != .bt2020 { std = .bt709 }

            // 全范围时偏移 0、增益 1024；有限范围时 Y 压到 16…235
            let full = range == .full
            yOffset = full ? 0 : 16
            yScale = full ? 1024 : 1192

            // 与 Android 完全相同的整数系数。gCb / gCr 存正值，
            // 使用时按 g = yScale·Y - gCb·Cb - gCr·Cr 相减。
            switch std {
            case .bt2020:
                if full { rCr = 1510; gCb = 169; gCr = 585; bCb = 1926 }
                else { rCr = 1719; gCb = 192; gCr = 666; bCb = 2193 }
            case .bt601PAL, .bt601NTSC:
                if full { rCr = 1436; gCb = 352; gCr = 731; bCb = 1815 }
                else { rCr = 1634; gCb = 401; gCr = 832; bCb = 2065 }
            default:
                if full { rCr = 1613; gCb = 192; gCr = 479; bCb = 1900 }
                else { rCr = 1836; gCb = 218; gCr = 546; bCb = 2163 }
            }
        }
    }

    private static func clamp8(_ v: Int) -> Int { max(0, min(255, v)) }
}

/// 单个像素的 YCbCr → RGB 转换器。与 Android `YuvConverter` 的实例语义一致：
/// 构造时固定色彩标准／传输函数／范围，之后逐像素调用 `rgb(y:u:v:)`。
public struct YUVConverter {

    public let matrix: ColorPipeline.YUVMatrix
    /// nil 表示源本身是 SDR，无需色调映射
    public let lut: [UInt8]?
    public let gamut2020to709: Bool
    public let hdrSource: Bool
    public let label: String

    public init(standard: ColorPipeline.Standard,
                transfer: ColorPipeline.Transfer,
                range: ColorPipeline.Range,
                keepHDRSignal: Bool = false) {

        matrix = ColorPipeline.YUVMatrix(standard: standard, range: range)
        hdrSource = transfer.isHDR
        lut = (hdrSource && !keepHDRSignal) ? ColorPipeline.toneMapLUT(transfer: transfer) : nil
        gamut2020to709 = standard == .bt2020 && !keepHDRSignal

        let stdName: String
        switch standard {
        case .bt2020: stdName = "BT.2020"
        case .bt601PAL, .bt601NTSC: stdName = "BT.601"
        default: stdName = "BT.709"
        }
        let xferName: String
        switch transfer {
        case .hlg: xferName = "HLG"
        case .pq: xferName = "PQ"
        case .linear: xferName = "linear"
        default: xferName = "SDR"
        }
        label = "\(stdName) / \(xferName) / \(range == .full ? "full" : "limited")"
    }

    /// 返回 0xRRGGBB
    public func rgb(y: Int, u: Int, v: Int) -> Int {
        let m = matrix
        let c = y - m.yOffset
        let d = u - 128
        let e = v - 128
        var r = (m.yScale * c + m.rCr * e + 512) >> 10
        var g = (m.yScale * c - m.gCb * d - m.gCr * e + 512) >> 10
        var b = (m.yScale * c + m.bCb * d + 512) >> 10
        r = max(0, min(255, r))
        g = max(0, min(255, g))
        b = max(0, min(255, b))
        if let lut = lut {
            r = Int(lut[r])
            g = Int(lut[g])
            b = Int(lut[b])
        }
        return gamut2020to709
            ? ColorPipeline.gamutPack(r: r, g: g, b: b)
            : (r << 16) | (g << 8) | b
    }
}
