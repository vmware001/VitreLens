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

    /// 预览长边上限（像素）。1400 足够 3x 屏整屏显示，再大只是浪费
    private static let previewMaxSide: CGFloat = 1400

    // MARK: - 当前照片的缓存

    /// 当前照片的身份。玻璃的模糊/原图纹理缓存靠它区分图像，
    /// 换图必须换值，否则玻璃里会残留上一张的虚化背景（评审 2.4-2）。
    private var sourceID = UUID().uuidString
    /// 预览代理图：每次刷新都拿它渲染，不再把 48MP 原图重采样一遍（评审 2.4-3）
    private var previewProxy: CGImage?
    /// 背景明暗只在导入时算一次
    private var sourceIsDark = false
    /// 源文件 EXIF/TIFF/GPS，导出时带回
    private var sourceMetadata: [String: Any] = [:]

    init() {
        GlassPreset.standard.apply(to: &style)

        // CI / 设计校对用：直接塞一张合成测试图，省去手动选照片
        if let demo = ProcessInfo.processInfo.environment["VITRELENS_DEMO"], demo != "0" {
            style.name = "OPPO Find X10 Pro Max"
            style.params = "23mm f/1.8 1/287s ISO50"
            style.meta = "2026.11.03 12:04"
            if let img = Snapshot.syntheticPhoto(size: CGSize(width: 1200, height: 1600)) {
                adopt(ImportedPhoto(image: img,
                                    proxy: PreviewProxy.make(image: img, maxSide: Self.previewMaxSide),
                                    isDark: nil,
                                    metadata: [:]))
            }
            switch demo {
            case "layout": page = .layout
            case "glass": page = .glass
            case "export": page = .export
            default: page = .content
            }
        }
    }

    /// 换一张源图。所有派生缓存（预览代理、模糊纹理）同时作废。
    private func adopt(_ photo: ImportedPhoto) {
        sourceID = UUID().uuidString
        source = photo.image
        previewProxy = photo.proxy
        let probe = photo.proxy ?? photo.image.cgImage
        sourceIsDark = photo.isDark ?? (probe.map { WatermarkCanvas.isDarkBackdrop($0) } ?? false)
        sourceMetadata = photo.metadata
        // 旧图的纹理缓存已在导入的后台任务里 clearCache()；
        // 即便某条路径漏了，渲染侧 prepare(for:) 也会按图像身份自动作废旧缓存（双保险）
        refreshPreview()
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
    ///
    /// 渲染放到后台并做 60ms 防抖：拖动滑块时每次变化都同步渲染 1400px 的图，
    /// 主线程会被卡住，手感直接废掉。
    private var previewTask: Task<Void, Never>?
    private var previewGeneration = 0

    func refreshPreview(immediate: Bool = false) {
        guard let img = source else {
            preview = nil
            return
        }
        let st = style
        previewGeneration += 1
        let generation = previewGeneration
        previewTask?.cancel()

        // 优先用导入时生成好的代理图：省掉每次刷新把全分辨率原图重采样一次
        let base: UIImage
        let size: CGSize
        if let proxy = previewProxy {
            base = UIImage(cgImage: proxy)
            size = CGSize(width: proxy.width, height: proxy.height)
        } else {
            let maxSide = Self.previewMaxSide
            let k = min(1, maxSide / max(img.size.width, img.size.height))
            size = CGSize(width: (img.size.width * k).rounded(),
                          height: (img.size.height * k).rounded())
            base = img
        }
        let key = sourceID
        let dark = sourceIsDark

        previewTask = Task { [weak self] in
            if !immediate {
                try? await Task.sleep(nanoseconds: 60_000_000)
            }
            guard !Task.isCancelled else { return }
            // 串行渲染 + generation 过滤：拖动时同一时刻只有一个渲染在跑，
            // 排队中的旧请求直接丢弃（`Task.cancel()` 取消不了已经在跑的合成工作）
            let accepted = await PreviewRenderer.shared.begin(generation: generation)
            guard accepted else { return }
            let out = await PreviewRenderer.shared.render(base,
                                                         canvasSize: size,
                                                         style: st,
                                                         imageKey: key,
                                                         themeDark: dark)
            guard !Task.isCancelled, let self, generation == self.previewGeneration else { return }
            self.preview = out
        }
    }

    /// 导出结果直接写入系统相册
    func saveToPhotos() {
        guard let url = exportURL else { return }
        busy = true
        Task {
            do {
                try await Exporter.saveToPhotos(url)
                message = "已保存到相册"
            } catch {
                message = "保存失败：\(error.localizedDescription)"
            }
            busy = false
        }
    }

    func load(item: PhotosPickerItem?) async {
        guard let item else { return }
        busy = true
        defer { busy = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                message = "这张照片无法读取"
                return
            }
            // 烘焙方向（48MP 要重栅格化一次）、生成预览代理、读元数据都是重活，
            // 全部放后台，避免导入时主线程卡 200~400ms（评审 2.4-8）
            let maxSide = Self.previewMaxSide
            let imported = await Task.detached(priority: .userInitiated) { () -> ImportedPhoto? in
                autoreleasepool {
                    guard let raw = UIImage(data: data) else { return nil }
                    let normalized = raw.normalizedUp()
                    let proxy = PreviewProxy.make(from: data, maxSide: maxSide)
                        ?? PreviewProxy.make(image: normalized, maxSide: maxSide)
                    let dark = (proxy ?? normalized.cgImage)
                        .map { WatermarkCanvas.isDarkBackdrop($0) }
                    // 换图后必须让玻璃的模糊/纹理缓存失效，否则新图会命中旧图的虚化背景。
                    // 放在后台做：首次访问 GlassRenderer 会编译 Metal 管线，不该卡主线程。
                    GlassRenderer.shared.clearCache()
                    return ImportedPhoto(image: normalized,
                                         proxy: proxy,
                                         isDark: dark,
                                         metadata: ImageMetadata.read(from: data))
                }
            }.value

            guard let imported else {
                message = "这张照片无法读取"
                return
            }
            adopt(imported)
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
        let key = sourceID
        let meta = sourceMetadata
        let dark = sourceIsDark
        Task.detached(priority: .userInitiated) {
            do {
                let url = try await Exporter.writeJPEG(image: image,
                                                       style: st,
                                                       measurer: CoreTextMeasurer(),
                                                       imageKey: key,
                                                       metadata: meta,
                                                       darkBackdrop: dark)
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

/// 预览渲染串行器。
///
/// 以前用 `Task.detached` 在后台渲染，但 `cancel()` 取消不了 detached 任务：
/// 拖动滑块时会有 2~4 个全链路渲染并发叠加，GPU/CPU 互相抢（评审 2.4-6）。
/// 改用 actor 之后同一时刻只有一个渲染在跑，并用 generation 丢弃排队中的旧请求。
actor PreviewRenderer {

    static let shared = PreviewRenderer()

    private var latestGeneration = 0

    init() {}

    /// 只有不旧于已接受的最新请求才继续渲染；返回 false 表示已有更新的预览在路上
    func begin(generation: Int) -> Bool {
        guard generation >= latestGeneration else { return false }
        latestGeneration = generation
        return true
    }

    func render(_ image: UIImage,
                canvasSize: CGSize,
                style: WatermarkStyle,
                imageKey: String,
                themeDark: Bool) -> UIImage {
        WatermarkCanvas.composite(image: image,
                                  canvasSize: canvasSize,
                                  style: style,
                                  measurer: CoreTextMeasurer(),
                                  imageKey: imageKey,
                                  themeDark: themeDark)
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
