import UIKit
import Photos
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

    static func writeJPEG(image: UIImage,
                          style: WatermarkStyle,
                          measurer: CoreTextMeasurer) async throws -> URL {
        let size = CGSize(width: image.size.width, height: image.size.height)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true

        let out = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
            WatermarkCanvas.draw(canvasSize: size, style: style, measurer: measurer)
        }

        guard let data = out.jpegData(compressionQuality: 0.95) else { throw Failure.encode }

        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("VitreLens_\(f.string(from: Date())).jpg")
        try data.write(to: url, options: .atomic)
        return url
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
