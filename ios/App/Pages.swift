import SwiftUI
import PhotosUI
import VitreLensCore

// MARK: - 通用容器

struct PageShell<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) { content }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.10), lineWidth: 1))
        .foregroundStyle(.white)
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .tracking(0.6)
            .foregroundStyle(.white.opacity(0.45))
    }
}

struct SliderRow: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var suffix: String = "%"
    var decimals: Int = 0

    var body: some View {
        VStack(spacing: 4) {
            HStack {
                Text(label).font(.system(size: 13))
                Spacer()
                Text(String(format: "%.\(decimals)f\(suffix)", value))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.55))
            }
            Slider(value: $value, in: range)
                .tint(.white)
        }
    }
}

// MARK: - 内容

struct ContentPage: View {
    @EnvironmentObject private var model: AppModel
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        PageShell {
            PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                HStack {
                    Image(systemName: "photo.badge.plus")
                    Text(model.source == nil ? "选择照片" : "换一张")
                    Spacer()
                }
                .font(.system(size: 14, weight: .medium))
                .padding(.vertical, 12)
                .padding(.horizontal, 14)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.12)))
            }
            .buttonStyle(.plain)
            .onChange(of: pickerItem) { item in
                Task { await model.load(item: item) }
            }

            SectionLabel(text: "预览")

            if let preview = model.preview {
                Image(uiImage: preview)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.white.opacity(0.04))
                    .frame(height: 220)
                    .overlay(
                        Text("导入一张照片开始")
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.4))
                    )
            }

            if let source = model.source {
                Text("原始尺寸 \(Int(source.size.width)) × \(Int(source.size.height))")
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }
}

// MARK: - 版式

struct LayoutPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        PageShell {
            SectionLabel(text: "文字")
            field("机型", text: $model.style.name)
            field("副标题", text: $model.style.subtitle)
            field("参数", text: $model.style.params)
            field("时间", text: $model.style.meta)

            SectionLabel(text: "排版")
            SliderRow(label: "名称字号", value: $model.style.nameSize, range: 10...60)
            SliderRow(label: "参数字号", value: $model.style.metaSize, range: 6...24)
            SliderRow(label: "水印宽度", value: $model.style.widthPct, range: 40...100)
            SliderRow(label: "水印高度", value: $model.style.glassHeightPct, range: 50...300)
            SliderRow(label: "底部间距", value: $model.style.gapPct, range: 0...12, decimals: 1)
            SliderRow(label: "水平位置", value: $model.style.posXPct, range: 0...100)

            Toggle("斜体字形", isOn: $model.style.italic)
                .font(.system(size: 13))

            SectionLabel(text: "字体")
            Picker("字体", selection: $model.style.font) {
                Text("系统").tag("sans")
                Text("窄体").tag("condensed")
                Text("衬线").tag("serif")
                Text("等宽").tag("mono")
            }
            .pickerStyle(.segmented)
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55))
            TextField(label, text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 14))
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(0.08)))
        }
    }
}

// MARK: - 玻璃

struct GlassPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        PageShell {
            SectionLabel(text: "预设")
            HStack(spacing: 8) {
                ForEach(GlassPreset.allCases, id: \.rawValue) { p in
                    Button {
                        model.apply(p)
                    } label: {
                        Text(p.title)
                            .font(.system(size: 12, weight: .medium))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(model.preset == p ? Color.white.opacity(0.22) : Color.white.opacity(0.06)))
                    }
                    .buttonStyle(.plain)
                }
            }

            SectionLabel(text: "材质")
            SliderRow(label: "模糊度", value: $model.style.roughPct, range: 0...100)
            SliderRow(label: "厚度", value: $model.style.thicknessPct, range: 0...100)
            SliderRow(label: "高光", value: $model.style.glossPct, range: 0...150)
            SliderRow(label: "投影", value: $model.style.shadowPct, range: 0...100)
            SliderRow(label: "不透明度", value: $model.style.whitePct, range: 0...100)

            SectionLabel(text: "光学")
            SliderRow(label: "折射率", value: $model.style.ior, range: 1.0...2.0, suffix: "", decimals: 2)
            SliderRow(label: "边缘折射", value: $model.style.edgePct, range: 0...200)
            SliderRow(label: "透射率", value: $model.style.transPct, range: 0...100)
            SliderRow(label: "色散", value: $model.style.dispPct, range: 0...100)

            Text("玻璃面板目前是 Core Graphics 近似版；Metal 渲染器接入后，这里的同一组参数会直接驱动着色器。")
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.4))
        }
    }
}

// MARK: - 导出

struct ExportPage: View {
    @EnvironmentObject private var model: AppModel
    @State private var shareURL: URL?

    var body: some View {
        PageShell {
            Button {
                model.export()
                shareURL = nil
            } label: {
                HStack {
                    Image(systemName: "square.and.arrow.up")
                    Text("导出图片")
                    Spacer()
                }
                .font(.system(size: 14, weight: .semibold))
                .padding(.vertical, 13)
                .padding(.horizontal, 14)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.white.opacity(model.source == nil ? 0.06 : 0.18)))
            }
            .buttonStyle(.plain)
            .disabled(model.source == nil || model.busy)

            if let url = model.exportURL {
                Button("保存 / 分享") { shareURL = url }
                    .font(.system(size: 13))
                Text(url.lastPathComponent)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.4))
            }

            Text("iOS 端目前只处理静态照片。动态照片与视频需要 AVFoundation 管线，属于后续阶段。")
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.4))
        }
        .sheet(item: $shareURL) { url in
            ShareSheet(url: url)
        }
    }
}

extension URL: Identifiable {
    public var id: String { absoluteString }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
