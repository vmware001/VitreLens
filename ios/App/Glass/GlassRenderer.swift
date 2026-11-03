import Foundation
import Metal
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import VitreLensCore

/// Metal 版液态玻璃。参数换算逐项对齐 Android `GlassRenderer`，
/// 着色器见 Glass.metal（移植自 glass_frag_pbr.glsl）。
///
/// 与 Android 的差别只有一处：那边是分条带渲染再拼接（为了控制显存），
/// iOS 一次渲染整幅画布，`crop` 恒为 (0,0,1,1)，其余公式完全相同。
final class GlassRenderer {

    static let shared = GlassRenderer()

    private let device: MTLDevice?
    private let queue: MTLCommandQueue?
    private let pipeline: MTLRenderPipelineState?
    private let ciContext: CIContext?

    /// 模糊纹理缓存：同一张图、同一尺寸、同一 sigma 不必重复计算
    private var blurCache: [String: MTLTexture] = [:]
    private let cacheLock = NSLock()

    private init() {
        let dev = MTLCreateSystemDefaultDevice()
        device = dev
        queue = dev?.makeCommandQueue()
        ciContext = dev.map { CIContext(mtlDevice: $0, options: [.cacheIntermediates: false]) }
        pipeline = Self.makePipeline(device: dev)
    }

    var isAvailable: Bool { pipeline != nil }

    private static func makePipeline(device: MTLDevice?) -> MTLRenderPipelineState? {
        guard let device, let library = device.makeDefaultLibrary(),
              let vfn = library.makeFunction(name: "vs_glass"),
              let ffn = library.makeFunction(name: "fs_glass") else { return nil }
        let desc = MTLRenderPipelineDescriptor()
        desc.vertexFunction = vfn
        desc.fragmentFunction = ffn
        desc.colorAttachments[0].pixelFormat = .bgra8Unorm
        return try? device.makeRenderPipelineState(descriptor: desc)
    }

    // MARK: - 对外入口

    /// 返回「照片 + 玻璃面板」的合成图；文字层由调用方再往上画。
    /// 失败时返回 nil，调用方回退到 Core Graphics 近似版本。
    func renderGlass(photo: CGImage,
                     style: WatermarkStyle,
                     layout: WatermarkLayout,
                     themeDark: Bool = false) -> CGImage? {
        guard let device, let queue, let pipeline, let ciContext else { return nil }

        let fullW = photo.width
        let fullH = photo.height
        guard fullW > 0, fullH > 0, layout.hasContent else { return photo }

        guard let photoTex = makeTexture(photo, device: device) else { return nil }

        // 与 Android 相同的比例：轻度模糊 sigma = w/63，强模糊 w/19
        let w = CGFloat(fullW)
        guard let blur1 = blurredTexture(photo, sigma: w / 63, ciContext: ciContext, device: device,
                                         key: "b1-\(fullW)x\(fullH)"),
              let blur2 = blurredTexture(photo, sigma: w / 19, ciContext: ciContext, device: device,
                                         key: "b2-\(fullW)x\(fullH)") else { return nil }

        let outDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                                                               width: fullW, height: fullH,
                                                               mipmapped: false)
        outDesc.usage = [.renderTarget, .shaderRead]
        // iOS 只支持 shared（managed 是 macOS 专属），统一内存下也能直接被 CPU 读取
        outDesc.storageMode = .shared
        guard let outTex = device.makeTexture(descriptor: outDesc) else { return nil }

        var uniforms = makeUniforms(style: style, layout: layout, fullW: fullW, fullH: fullH, themeDark: themeDark)

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = outTex
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)

        guard let cmd = queue.makeCommandBuffer(),
              let enc = cmd.makeRenderCommandEncoder(descriptor: pass) else { return nil }
        enc.setRenderPipelineState(pipeline)
        enc.setFragmentBytes(&uniforms, length: MemoryLayout<GlassUniforms>.stride, index: 0)
        enc.setFragmentTexture(photoTex, index: 0)
        enc.setFragmentTexture(blur1, index: 1)
        enc.setFragmentTexture(blur2, index: 2)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        enc.endEncoding()
        cmd.commit()
        cmd.waitUntilCompleted()

        return cgImage(from: outTex, width: fullW, height: fullH)
    }

    // MARK: - Uniform

    private func makeUniforms(style st: WatermarkStyle,
                              layout L: WatermarkLayout,
                              fullW: Int, fullH: Int,
                              themeDark: Bool) -> GlassUniforms {
        var u = GlassUniforms()
        let uu = st.pixelUnits ? 1.0 : Double(fullW) / 1000.0

        u.panel = SIMD4<Float>(Float(L.panel.x), Float(L.panel.y), Float(L.panel.w), Float(L.panel.h))
        u.crop = SIMD4<Float>(0, 0, 1, 1)
        u.resScale = SIMD4<Float>(Float(fullW), Float(fullH), Float(uu), st.tintOn ? 1 : 0)

        // 像素单位时 depth / thickness 直接是 px；否则按面板高度百分比换算
        let depthPx = st.pixelUnits
            ? max(2, st.depthPct)
            : max(2, L.panel.h * 0.5 * (st.depthPct / 100))
        let edgePx = st.pixelUnits
            ? max(0, st.edgePct)
            : min(max(0, st.edgePct / 100) * depthPx * 2, L.panel.h * 0.45)
        let thickPx = st.pixelUnits
            ? max(1, st.thicknessPct)
            : max(1, st.thicknessPct / 100 * L.panel.h * 1.1)

        u.iorRough = SIMD4<Float>(Float(st.ior), Float(st.roughPct / 100),
                                  Float(st.reflPct / 100), Float(st.transPct / 100))
        u.geom = SIMD4<Float>(Float(L.panel.r), Float(depthPx), Float(thickPx), Float(edgePx))
        u.extra = SIMD4<Float>(Float(st.dispPct / 100), Float(st.warpPct / 100),
                               Float(max(4, st.segments)), Float(st.shadowPct / 100))

        // frostTone：0 自动（按背景明暗）、1 强制深色、2 强制浅色
        let darkTone = st.frostTone == 1 || (st.frostTone == 0 && themeDark)
        let opaque: SIMD3<Float> = darkTone
            ? SIMD3<Float>(0.07, 0.07, 0.08)
            : SIMD3<Float>(0.97, 0.97, 0.98)
        // 描边色近中性，让玻璃靠折射和背景出颜色，而不是一层冷色滤镜
        u.tint = SIMD4<Float>(0.99, 0.99, 1.0, Float(st.whitePct / 100))
        u.opaque = SIMD4<Float>(opaque.x, opaque.y, opaque.z, Float(st.glossPct / 100))
        return u
    }

    // MARK: - 纹理

    private func makeTexture(_ image: CGImage, device: MTLDevice) -> MTLTexture? {
        let w = image.width, h = image.height
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                                                           width: w, height: h, mipmapped: false)
        desc.usage = .shaderRead
        desc.storageMode = .shared
        guard let tex = device.makeTexture(descriptor: desc) else { return nil }

        let bytesPerRow = w * 4
        var data = [UInt8](repeating: 0, count: bytesPerRow * h)
        let ok: Bool = data.withUnsafeMutableBytes { raw -> Bool in
            guard let base = raw.baseAddress,
                  let ctx = CGContext(data: base, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: bytesPerRow,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                        | CGBitmapInfo.byteOrder32Little.rawValue) else { return false }
            // 翻成左上角原点，与着色器的 uv 约定一致
            ctx.translateBy(x: 0, y: CGFloat(h))
            ctx.scaleBy(x: 1, y: -1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        tex.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, withBytes: data, bytesPerRow: bytesPerRow)
        return tex
    }

    /// 按 Android 的高斯金字塔思路：先降采样到 sigma 约 2.5px，再做高斯。
    /// 返回低分辨率纹理，着色器用 LINEAR 采样放大，观感与全分辨率高斯一致。
    private func blurredTexture(_ image: CGImage, sigma: CGFloat,
                                ciContext: CIContext, device: MTLDevice,
                                key: String) -> MTLTexture? {
        let cacheKey = "\(key)-\(Int(sigma))"
        cacheLock.lock()
        if let hit = blurCache[cacheKey] { cacheLock.unlock(); return hit }
        cacheLock.unlock()

        let src = CIImage(cgImage: image)
        var img = src
        var s = max(0.6, sigma)
        // 逐步减半，直到 sigma 接近 2.5
        while s > 2.5 {
            let f = CIFilter.lanczosScaleTransform()
            f.inputImage = img
            f.scale = 0.5
            f.aspectRatio = 1
            if let out = f.outputImage { img = out } else { break }
            s *= 0.5
        }
        let g = CIFilter.gaussianBlur()
        g.inputImage = img.clampedToExtent()
        g.radius = Float(s)
        guard let blurred = g.outputImage?.cropped(to: src.extent) else { return nil }

        let w = max(1, Int(blurred.extent.width)), h = max(1, Int(blurred.extent.height))
        guard let cg = ciContext.createCGImage(blurred, from: CGRect(x: 0, y: 0, width: w, height: h)),
              let tex = makeTexture(cg, device: device) else { return nil }

        cacheLock.lock()
        if blurCache.count > 8 { blurCache.removeAll() }
        blurCache[cacheKey] = tex
        cacheLock.unlock()
        return tex
    }

    func clearCache() {
        cacheLock.lock()
        blurCache.removeAll()
        cacheLock.unlock()
    }

    // MARK: - 回读

    private func cgImage(from texture: MTLTexture, width w: Int, height h: Int) -> CGImage? {
        let bytesPerRow = w * 4
        var data = [UInt8](repeating: 0, count: bytesPerRow * h)
        texture.getBytes(&data, bytesPerRow: bytesPerRow,
                         from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)

        // 渲染目标是左上角原点（着色器直接输出），CGImage 需要自下而上，
        // 所以这里翻转行序，而不是在着色器里再翻一次。
        var flipped = [UInt8](repeating: 0, count: data.count)
        for y in 0..<h {
            let src = y * bytesPerRow
            let dst = (h - 1 - y) * bytesPerRow
            flipped[dst..<(dst + bytesPerRow)] = data[src..<(src + bytesPerRow)]
        }

        let alphaInfo: CGBitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue)
            .union(CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue))
        guard let provider = CGDataProvider(data: Data(flipped) as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: alphaInfo, provider: provider, decode: nil,
                       shouldInterpolate: true, intent: .defaultIntent)
    }
}

/// 与 Glass.metal 中 `Uniforms` 的字段顺序、类型严格一一对应（8 个 float4）
struct GlassUniforms {
    var panel = SIMD4<Float>(repeating: 0)
    var crop = SIMD4<Float>(repeating: 0)
    var resScale = SIMD4<Float>(repeating: 0)
    var iorRough = SIMD4<Float>(repeating: 0)
    var geom = SIMD4<Float>(repeating: 0)
    var extra = SIMD4<Float>(repeating: 0)
    var tint = SIMD4<Float>(repeating: 0)
    var opaque = SIMD4<Float>(repeating: 0)
}
