import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var pill

    /// 常驻预览条高度。iPhone 竖屏（667~932pt 高）下约为 23~32% 屏高，
    /// 既够看清玻璃细节，又不会把下面的控制区挤没了（评审 3.3-I1）。
    private static let previewBarHeight: CGFloat = 210

    var body: some View {
        ZStack {
            backdrop

            VStack(spacing: 12) {
                header
                pageSwitch
                // 除「内容」页外，顶部常驻一条预览：这样在「玻璃」页拖厚度/色散/边缘折射
                // 就能直接看到结果，不用来回切页。放在分页控件下方，避免切页时分页控件跳动。
                if model.page != .content, let preview = model.preview {
                    previewBar(preview)
                }
                pages
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 8)
        }
        .onChange(of: model.style) { _ in model.refreshPreview() }
        .alert("提示", isPresented: Binding(
            get: { model.message != nil },
            set: { if !$0 { model.message = nil } }
        )) {
            Button("好", role: .cancel) { model.message = nil }
        } message: {
            Text(model.message ?? "")
        }
    }

    // MARK: - 分页内容

    /// 四个页面都常驻、用透明度切换。
    /// 以前 `.id(model.page)` 会在每次切页时把整棵子树销毁重建（滚动位置、@State 全丢），
    /// 现在页面各自保留状态，只做一次淡入淡出（评审 3.3-I1）。
    private var pages: some View {
        ZStack(alignment: .top) {
            ContentPage().pageLayer(isActive: model.page == .content)
            LayoutPage().pageLayer(isActive: model.page == .layout)
            // isActive 透传给「玻璃」页：它那个 Metal 状态徽标会实例化 GlassRenderer，
            // 常驻页面里不能白白在启动时编译着色器
            GlassPage(isActive: model.page == .glass).pageLayer(isActive: model.page == .glass)
            ExportPage().pageLayer(isActive: model.page == .export)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: model.page)
    }

    /// 常驻预览条。`scaledToFit` 保比例，不裁切；固定高度只会出现透明留白。
    private func previewBar(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: .infinity)
            .frame(height: Self.previewBarHeight)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.white.opacity(0.04))
            )
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.10), lineWidth: 1)
            )
            .accessibilityLabel("水印预览")
    }

    // MARK: - 背景

    private var backdrop: some View {
        LinearGradient(
            colors: [Color(red: 0.05, green: 0.06, blue: 0.08),
                     Color(red: 0.09, green: 0.10, blue: 0.13)],
            startPoint: .top,
            endPoint: .bottom
        )
        .overlay(
            RadialGradient(colors: [Color.white.opacity(0.07), .clear],
                           center: .topLeading,
                           startRadius: 10,
                           endRadius: 420)
        )
        .overlay(
            RadialGradient(colors: [Color(red: 0.35, green: 0.45, blue: 0.75).opacity(0.10), .clear],
                           center: .bottomTrailing,
                           startRadius: 20,
                           endRadius: 460)
        )
        .ignoresSafeArea()
    }

    // MARK: - 顶栏

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 1) {
                Text("VitreLens")
                    .font(.system(size: 19, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                Text("液态玻璃水印")
                    .font(.system(size: 10.5, weight: .medium))
                    .tracking(0.4)
                    // 0.42 在 #0D0F12 上只有约 3:1，小字不达标（评审 3.3-I5）
                    .foregroundStyle(.white.opacity(0.62))
            }
            Spacer()
            if model.busy {
                ProgressView()
                    .controlSize(.small)
                    .tint(.white)
            }
        }
        .padding(.horizontal, 2)
    }

    // MARK: - 分页

    private var pageSwitch: some View {
        HStack(spacing: 2) {
            ForEach(AppModel.Page.allCases) { page in
                Button {
                    guard model.page != page else { return }
                    if reduceMotion {
                        model.page = page
                    } else {
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.84)) {
                            model.page = page
                        }
                    }
                } label: {
                    Text(page.rawValue)
                        .font(.system(size: 13, weight: model.page == page ? .semibold : .regular))
                        .foregroundStyle(model.page == page ? .white : .white.opacity(0.62))
                        // 触控目标 ≥44pt：文字本身只有 ~16pt，靠 frame 撑满整格（评审 3.3-I3）
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background {
                            if model.page == page {
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(Color.white.opacity(0.16))
                                    .matchedGeometryEffect(id: "pill", in: pill)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.white.opacity(0.10), lineWidth: 1)
        )
    }
}

private extension View {
    /// 非当前页留在视图树里（滚动位置、@State 都不丢），但不可交互、不参与无障碍。
    /// disabled 还有一层作用：切页时让隐藏页里的 TextField 失去焦点，键盘自动收起。
    func pageLayer(isActive: Bool) -> some View {
        self
            .opacity(isActive ? 1 : 0)
            .allowsHitTesting(isActive)
            .accessibilityHidden(!isActive)
            .disabled(!isActive)
    }
}

#Preview {
    ContentView().environmentObject(AppModel())
}
