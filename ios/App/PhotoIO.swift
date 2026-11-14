import Foundation
import UIKit
import ImageIO

/// 导入结果。解码、烘焙方向、生成预览代理、判断背景明暗都是重活，
/// 一律在后台线程算完，回到主线程只做赋值（评审 2.4-8）。
struct ImportedPhoto {
    let image: UIImage
    /// 预览专用小图：每次 refreshPreview 都用它，避免把 48MP 原图重采样一遍（评审 2.4-3）
    let proxy: CGImage?
    /// 背景整体偏暗（决定「色调」档实色是近白还是近黑）；nil 表示交给主线程兜底算一次
    let isDark: Bool?
    /// 源文件的 EXIF/TIFF/GPS，导出时原样带回（评审 2.4-7）
    let metadata: [String: Any]
}

/// 预览代理图。
///
/// 走 ImageIO 的缩略图解码（`CGImageSourceCreateThumbnailAtIndex`），
/// 比「先把全图解码再 `CGContextDrawImage` 缩小」快一个量级——
/// 后者对 48MP 原图要完整解码一次，前者由解码器直接按目标尺寸降采样。
enum PreviewProxy {

    /// 从原始文件数据生成（导入主路径）
    static func make(from data: Data, maxSide: CGFloat) -> CGImage? {
        guard maxSide >= 1 else { return nil }
        // 字典用 [String: Any]：NSString 键桥接到 CFDictionary 最稳，避免 CFString 键的桥接歧义
        let options: [String: Any] = [kCGImageSourceShouldCache as String: false]
        guard let src = CGImageSourceCreateWithData(data as CFData, options as CFDictionary) else {
            return nil
        }
        return make(source: src, maxSide: maxSide)
    }

    /// 从 UIImage 生成（拿不到原始数据时的兜底，例如 CI 用的合成测试图）
    static func make(image: UIImage, maxSide: CGFloat) -> CGImage? {
        let pxW = image.size.width * image.scale
        let pxH = image.size.height * image.scale
        guard pxW >= 1, pxH >= 1 else { return nil }
        let k = min(1, maxSide / max(pxW, pxH))
        let size = CGSize(width: (pxW * k).rounded(), height: (pxH * k).rounded())
        return WatermarkCanvas.resizedCGImage(image, to: size)
    }

    private static func make(source: CGImageSource, maxSide: CGFloat) -> CGImage? {
        let options: [String: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            // 与 UIImage.normalizedUp() 一致：把 EXIF 方向烘焙进像素，
            // 否则竖拍的代理图会横着显示，预览与成片方向不一致
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: Int(maxSide.rounded()),
            kCGImageSourceShouldCacheImmediately as String: true
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// 导出元数据搬运。
///
/// 旧实现用 `UIImage.jpegData` 写出，元数据全部丢失（EXIF/GPS/TIFF 都被丢掉，
/// Display P3 也被压成 sRGB）。这里只做「读容器属性 → 拼 ImageIO 属性字典」，
/// 不解码第二遍像素。
enum ImageMetadata {

    /// 读取源文件的容器属性（EXIF/TIFF/GPS…），不解码像素
    static func read(from data: Data) -> [String: Any] {
        let srcOptions: [String: Any] = [kCGImageSourceShouldCache as String: false]
        guard let src = CGImageSourceCreateWithData(data as CFData, srcOptions as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any] else {
            return [:]
        }
        return props
    }

    /// 组装写 JPEG 时用的属性字典。
    ///
    /// 保留 EXIF/TIFF/GPS；但「方向」与「像素尺寸」这两个字段与原图强绑定，
    /// 合成图的像素已经烘焙成正立、尺寸也可能不同，必须改写成真实值，
    /// 否则看图软件会再转一次方向或读出错误的尺寸。
    ///
    /// 注意：ICC 不在这里搬运。成片像素由 `WatermarkCanvas.composite` 的
    /// 8bit 渲染上下文产出，直接嵌入源 ICC（例如 P3）会让像素被按错误的
    /// 色彩空间解释，反而偏色；交给 ImageIO 按 CGImage 自身的色彩空间写。
    static func exportProperties(from source: [String: Any], image: CGImage) -> [String: Any] {
        var props: [String: Any] = [:]

        if let exif = source[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            var e = exif
            e[kCGImagePropertyExifPixelXDimension as String] = image.width
            e[kCGImagePropertyExifPixelYDimension as String] = image.height
            props[kCGImagePropertyExifDictionary as String] = e
        }
        if let tiff = source[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            var t = tiff
            t[kCGImagePropertyTIFFOrientation as String] = 1
            props[kCGImagePropertyTIFFDictionary as String] = t
        }
        if let gps = source[kCGImagePropertyGPSDictionary as String] as? [String: Any] {
            props[kCGImagePropertyGPSDictionary as String] = gps
        }

        // 像素已烘焙成正立（导入时 normalizedUp），顶层方向固定 1；
        // 宽高由 ImageIO 按 CGImage 自动写入
        props[kCGImagePropertyOrientation as String] = 1
        return props
    }
}
