import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 12) {
                header

                Group {
                    switch model.page {
                    case .content: ContentPage()
                    case .layout: LayoutPage()
                    case .glass: GlassPage()
                    case .export: ExportPage()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
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

    private var header: some View {
        VStack(spacing: 10) {
            HStack {
                Text("VitreLens")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                Spacer()
                if model.busy {
                    ProgressView().scaleEffect(0.8)
                }
            }

            HStack(spacing: 6) {
                ForEach(AppModel.Page.allCases) { page in
                    Button {
                        withAnimation(.easeOut(duration: 0.18)) { model.page = page }
                    } label: {
                        Text(page.rawValue)
                            .font(.system(size: 13, weight: .medium))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(model.page == page
                                          ? Color.white.opacity(0.18)
                                          : Color.white.opacity(0.05))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .stroke(Color.white.opacity(model.page == page ? 0.28 : 0.08), lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(.bottom, 4)
    }
}

#Preview {
    ContentView().environmentObject(AppModel())
}
