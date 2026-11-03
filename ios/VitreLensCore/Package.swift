// swift-tools-version:5.9
import PackageDescription

// 平台无关的核心逻辑：版式几何、色彩管线、MP4 结构解析。
// 不依赖 UIKit / CoreGraphics / Metal，因此可以在 Windows、Linux、macOS 上直接跑单元测试，
// iOS 应用与单元测试共用同一份源码，避免两端算法漂移。
let package = Package(
    name: "VitreLensCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "VitreLensCore", targets: ["VitreLensCore"])
    ],
    targets: [
        .target(name: "VitreLensCore", path: "Sources/VitreLensCore"),
        .testTarget(
            name: "VitreLensCoreTests",
            dependencies: ["VitreLensCore"],
            path: "Tests/VitreLensCoreTests"
        )
    ]
)
