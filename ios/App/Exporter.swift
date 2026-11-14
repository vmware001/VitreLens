import UIKit
import Photos
import ImageIO
import UniformTypeIdentifiers
import VitreLensCore

/// 导出：按原始像素尺寸重新合成一次，保证「预览看到的版式」等于「导出结果」。
enum Exporter {

    enum Failure: LocalizedError {
        case encode
        case photoDenied
        var errorDescription: String? {
            switch self {
            case .encode: return "无法编码为 JPEG"
            case .photoDenied: return "没有相册写入权限"
            }
        }
    }

    /// JPEG 质量。保持原有的 0.95（已在评审建议的 q92~0.95 区间内），只换写出方式，不动质量语义。
    private static let jpegQuality: CGFloat = 0.95

    /// 合成 + 直接落盘。
    ///
    /// 必须和预览走同一个 `WatermarkCanvas.composite`，否则成片里没有 Metal 玻璃：
    /// 用户在「玻璃」页调的所有折射/色散/厚度参数都不会进入成片（评审 2.4-1）。
    ///
    /// - Parameters:
    ///   - imageKey: 预览用的图像身份，导出沿用同一个值，可复用已经算好的模糊纹理
    ///   - metadata: 源文件的 EXIF/TIFF/GPS（`ImageMetadata.read`），写盘时合并进 JPEG
    ///   - darkBackdrop: 背景明暗，nil 时由 composite 现场判定
    static func writeJPEG(image: UIImage,
                          style: WatermarkStyle,
                          measurer: CoreTextMeasurer,
                          imageKey: String = "export",
                          metadata: [String: Any] = [:],
                          darkBackdrop: Bool? = nil) async throws -> URL {
        return try autoreleasepool {
            // 按「原始像素」出图：scale != 1 的图不再被悄悄降采样（旧实现用 point 尺寸 + scale 1）
            let size = CGSize(width: (image.size.width * image.scale).rounded(),
                              height: (image.size.height * image.scale).rounded())
            guard size.width >= 1, size.height >= 1 else { throw Failure.encode }

            let out = WatermarkCanvas.composite(image: image,
                                                canvasSize: size,
                                                style: style,
                                                measurer: measurer,
                                                imageKey: imageKey,
                                                themeDark: darkBackdrop)
            guard let cg = out.cgImage else { throw Failure.encode }

            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd_HHmmss_SSS"
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("VitreLens_\(f.string(from: Date())).jpg")

            let props = ImageMetadata.exportProperties(from: metadata, image: cg)
            try write(cg, to: url, properties: props)
            return url
        }
    }

    /// 直接写文件。
    ///
    /// 旧实现 `jpegData()` 会先把整幅编码结果攒在内存里，再 `.atomic` 写盘（多一份整文件拷贝）；
    /// CGImageDestination 是增量编码，边压边写，48MP 时少一份几十 MB 的整块编码缓冲，
    /// 也不用把整幅数据再拷一次。
    private static func write(_ cgImage: CGImage, to url: URL, properties: [String: Any]) throws {
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL,
                                                         UTType.jpeg.identifier as CFString,
                                                         1, nil) else {
            throw Failure.encode
        }
        var props = properties
        props[kCGImageDestinationLossyCompressionQuality as String] = jpegQuality
        CGImageDestinationAddImage(dest, cgImage, props as CFDictionary)

        guard CGImageDestinationFinalize(dest) else {
            // 失败时别留一个半截文件在临时目录里
            try? FileManager.default.removeItem(at: url)
            throw Failure.encode
        }
    }

    /// 写入系统相册。只申请「添加」权限，不需要读取整库。
    static func saveToPhotos(_ url: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw Failure.photoDenied }
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            req.addResource(with: .photo, fileURL: url, options: nil)
        }
    }
}
