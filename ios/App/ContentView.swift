import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Namespace private var pill

    var body: some View {
        ZStack {
            backdrop

            VStack(spacing: 12) {
                header
                pageSwitch

                Group {
                    switch model.page {
                    case .content: ContentPage()
                    case .layout: LayoutPage()
                    case .glass: GlassPage()
                    case .export: ExportPage()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .transition(.asymmetric(
                    insertion: .move(edge: .trailing).combined(with: .opacity),
                    removal: .opacity
                ))
                .id(model.page)
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
                    .foregroundStyle(.white.opacity(0.42))
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
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.84)) {
                        model.page = page
                    }
                } label: {
                    Text(page.rawValue)
                        .font(.system(size: 13, weight: model.page == page ? .semibold : .regular))
                        .foregroundStyle(model.page == page ? .white : .white.opacity(0.55))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
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

#Preview {
    ContentView().environmentObject(AppModel())
}
