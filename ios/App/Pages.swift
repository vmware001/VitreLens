import SwiftUI
import PhotosUI
import VitreLensCore

// MARK: - 通用容器

struct GlassCard<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(.ultraThinMaterial)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(
                        LinearGradient(colors: [.white.opacity(0.20), .white.opacity(0.05)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing),
                        lineWidth: 1
                    )
            )
            .shadow(color: .black.opacity(0.28), radius: 18, x: 0, y: 8)
    }
}

struct PageShell<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView(showsIndicators: false) {
            GlassCard { content }
                .padding(.bottom, 4)
        }
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .semibold))
            .tracking(1.1)
            .foregroundStyle(.white.opacity(0.36))
    }
}

struct SliderRow: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var suffix: String = "%"
    var decimals: Int = 0

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Text(label)
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                Text(String(format: "%.\(decimals)f", value) + suffix)
                    .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.75))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2.5)
                    .background(Capsule().fill(Color.white.opacity(0.10)))
            }
            Slider(value: $value, in: range)
                .tint(.white.opacity(0.9))
                .controlSize(.small)
        }
    }
}

/// 主按钮样式：浅色玻璃胶囊 + 顶部高光
struct PrimaryButtonStyle: ButtonStyle {
    var enabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14.5, weight: .semibold))
            .foregroundStyle(enabled ? Color.white : Color.white.opacity(0.35))
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(LinearGradient(
                        colors: enabled
                            ? [Color.white.opacity(0.26), Color.white.opacity(0.14)]
                            : [Color.white.opacity(0.07), Color.white.opacity(0.05)],
                        startPoint: .top, endPoint: .bottom))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.white.opacity(enabled ? 0.34 : 0.08), lineWidth: 1)
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - 内容

struct ContentPage: View {
    @EnvironmentObject private var model: AppModel
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        PageShell {
            PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                HStack(spacing: 8) {
                    Image(systemName: model.source == nil ? "photo.badge.plus" : "arrow.triangle.2.circlepath")
                        .font(.system(size: 14, weight: .medium))
                    Text(model.source == nil ? "选择照片" : "换一张照片")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.35))
                }
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .padding(.vertical, 12)
                .padding(.horizontal, 14)
                .background(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(Color.white.opacity(0.10))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.14), lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .onChange(of: pickerItem) { item in
                Task { await model.load(item: item) }
            }

            if let preview = model.preview {
                Image(uiImage: preview)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.10), lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.35), radius: 14, x: 0, y: 6)

                HStack(spacing: 6) {
                    Image(systemName: "ruler")
                        .font(.system(size: 9.5))
                    Text("\(Int(model.source?.size.width ?? 0)) × \(Int(model.source?.size.height ?? 0))")
                        .font(.system(size: 10.5, design: .monospaced))
                }
                .foregroundStyle(.white.opacity(0.36))
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 30, weight: .ultraLight))
                        .foregroundStyle(.white.opacity(0.22))
                    Text("导入一张照片开始")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.white.opacity(0.38))
                }
                .frame(maxWidth: .infinity)
                .frame(height: 220)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.white.opacity(0.03))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [5, 5]))
                        .foregroundStyle(Color.white.opacity(0.10))
                )
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

            Divider().overlay(Color.white.opacity(0.08))

            SectionLabel(text: "排版")
            SliderRow(label: "名称字号", value: $model.style.nameSize, range: 10...60)
            SliderRow(label: "参数字号", value: $model.style.metaSize, range: 6...24)
            SliderRow(label: "水印宽度", value: $model.style.widthPct, range: 40...100)
            SliderRow(label: "水印高度", value: $model.style.glassHeightPct, range: 50...300)
            SliderRow(label: "底部间距", value: $model.style.gapPct, range: 0...12, decimals: 1)
            SliderRow(label: "水平位置", value: $model.style.posXPct, range: 0...100)

            Toggle(isOn: $model.style.italic) {
                Text("斜体字形").font(.system(size: 13))
            }
            .tint(.white.opacity(0.8))

            Divider().overlay(Color.white.opacity(0.08))

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
        VStack(alignment: .leading, spacing: 5) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.42))
            TextField(label, text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 14))
                .foregroundStyle(.white)
                .padding(.vertical, 9)
                .padding(.horizontal, 11)
                .background(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .fill(Color.white.opacity(0.06))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.08), lineWidth: 1)
                )
        }
    }
}

// MARK: - 玻璃

struct GlassPage: View {
    @EnvironmentObject private var model: AppModel
    private let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    var body: some View {
        PageShell {
            HStack {
                SectionLabel(text: "预设")
                Spacer()
                statusBadge
            }

            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(GlassPreset.allCases, id: \.rawValue) { p in
                    Button {
                        withAnimation(.easeOut(duration: 0.16)) { model.apply(p) }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.title)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.white)
                            Text(p.subtitle)
                                .font(.system(size: 10))
                                .foregroundStyle(.white.opacity(0.45))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color.white.opacity(model.preset == p ? 0.16 : 0.05))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(Color.white.opacity(model.preset == p ? 0.42 : 0.07),
                                              lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }

            Divider().overlay(Color.white.opacity(0.08))

            SectionLabel(text: "材质")
            SliderRow(label: "模糊度", value: $model.style.roughPct, range: 0...100)
            SliderRow(label: "厚度", value: $model.style.thicknessPct, range: 0...100)
            SliderRow(label: "高光", value: $model.style.glossPct, range: 0...150)
            SliderRow(label: "投影", value: $model.style.shadowPct, range: 0...100)
            SliderRow(label: "不透明度", value: $model.style.whitePct, range: 0...100)

            Divider().overlay(Color.white.opacity(0.08))

            SectionLabel(text: "光学")
            SliderRow(label: "折射率", value: $model.style.ior, range: 1.0...2.0, suffix: "", decimals: 2)
            SliderRow(label: "边缘折射", value: $model.style.edgePct, range: 0...200)
            SliderRow(label: "透射率", value: $model.style.transPct, range: 0...100)
            SliderRow(label: "色散", value: $model.style.dispPct, range: 0...100)
            SliderRow(label: "艺术扭曲", value: $model.style.warpPct, range: 0...100)
        }
    }

    private var statusBadge: some View {
        let on = GlassRenderer.shared.isAvailable
        return HStack(spacing: 4) {
            Circle()
                .fill(on ? Color.green.opacity(0.8) : Color.orange.opacity(0.8))
                .frame(width: 5, height: 5)
            Text(on ? "Metal 实时折射" : "CPU 近似")
                .font(.system(size: 9.5, weight: .medium))
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.white.opacity(0.06)))
    }
}

// MARK: - 导出

struct ExportPage: View {
    @EnvironmentObject private var model: AppModel
    @State private var shareURL: URL?

    var body: some View {
        PageShell {
            Button {
                shareURL = nil
                model.export()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "square.and.arrow.up")
                    Text("导出图片")
                }
            }
            .buttonStyle(PrimaryButtonStyle(enabled: model.source != nil && !model.busy))
            .disabled(model.source == nil || model.busy)

            if let url = model.exportURL {
                Button {
                    model.saveToPhotos()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "photo.badge.arrow.down")
                        Text("保存到相册")
                    }
                }
                .buttonStyle(PrimaryButtonStyle(enabled: !model.busy))
                .disabled(model.busy)

                Button {
                    shareURL = url
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.up.on.square")
                        Text("分享")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())

                Text(url.lastPathComponent)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.34))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Divider().overlay(Color.white.opacity(0.08))

            HStack(spacing: 7) {
                Image(systemName: "info.circle")
                    .font(.system(size: 10))
                Text("iOS 端目前处理静态照片。动态照片与视频需要 AVFoundation 管线，属于后续阶段。")
                    .font(.system(size: 10.5))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(.white.opacity(0.34))
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
