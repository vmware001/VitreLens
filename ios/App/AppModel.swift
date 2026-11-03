import SwiftUI
import PhotosUI
import VitreLensCore

/// 应用状态。样式、照片、预设、导出结果都收在这里，
/// 与 Android 的 MainActivity + ControlPanel 共享同一份 WatermarkStyle。
@MainActor
final class AppModel: ObservableObject {

    enum Page: String, CaseIterable, Identifiable {
        case content = "内容"
        case layout = "版式"
        case glass = "玻璃"
        case export = "导出"
        var id: String { rawValue }
    }

    @Published var page: Page = .content
    @Published var style = WatermarkStyle()
    @Published var preset: GlassPreset? = .standard
    @Published var source: UIImage?
    @Published var preview: UIImage?
    @Published var exportURL: URL?
    @Published var busy = false
    @Published var message: String?

    let measurer = CoreTextMeasurer()

    init() {
        GlassPreset.standard.apply(to: &style)

        // CI / 设计校对用：直接塞一张合成测试图，省去手动选照片
        if let demo = ProcessInfo.processInfo.environment["VITRELENS_DEMO"], demo != "0" {
            style.name = "OPPO Find X10 Pro Max"
            style.params = "23mm f/1.8 1/287s ISO50"
            style.meta = "2026.11.03 12:04"
            if let img = Snapshot.syntheticPhoto(size: CGSize(width: 1200, height: 1600)) {
                source = img
                refreshPreview()
            }
            switch demo {
            case "layout": page = .layout
            case "glass": page = .glass
            case "export": page = .export
            default: page = .content
            }
        }
    }

    func apply(_ p: GlassPreset) {
        var s = style
        p.apply(to: &s)
        style = s
        preset = p
        refreshPreview()
    }

    /// 任何参数变化后重新合成预览。预览与实际导出走同一套 WatermarkCanvas，
    /// 只是尺寸不同——这是 Android 端一直坚持的做法，避免「预览好看、导出不对」。
    func refreshPreview() {
        guard let source else {
            preview = nil
            return
        }
        let maxSide: CGFloat = 1400
        let w = source.size.width, h = source.size.height
        let k = min(1, maxSide / max(w, h))
        let size = CGSize(width: (w * k).rounded(), height: (h * k).rounded())
        preview = WatermarkCanvas.composite(image: source,
                                            canvasSize: size,
                                            style: style,
                                            measurer: measurer)
    }

    func load(item: PhotosPickerItem?) async {
        guard let item else { return }
        busy = true
        defer { busy = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                message = "这张照片无法读取"
                return
            }
            source = image.normalizedUp()
            refreshPreview()
        } catch {
            message = "导入失败：\(error.localizedDescription)"
        }
    }

    func export() {
        guard let image = source else { return }
        busy = true
        message = nil
        // 值类型先拷出来，避免在后台任务里访问 MainActor 隔离的属性
        let st = style
        Task.detached(priority: .userInitiated) {
            do {
                let url = try await Exporter.writeJPEG(image: image,
                                                       style: st,
                                                       measurer: CoreTextMeasurer())
                await MainActor.run {
                    self.exportURL = url
                    self.busy = false
                    self.message = "已生成 \(url.lastPathComponent)"
                }
            } catch {
                await MainActor.run {
                    self.busy = false
                    self.message = "导出失败：\(error.localizedDescription)"
                }
            }
        }
    }
}

extension UIImage {
    /// 把 EXIF 方向烘焙进像素，后续所有绘制都用统一坐标系
    func normalizedUp() -> UIImage {
        guard imageOrientation != .up else { return self }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
