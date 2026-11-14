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

    /// 模糊纹理缓存。
    ///
    /// key 里必须带图像身份：以前只有 `"b1-1400x1050"`，
    /// 两张同尺寸的照片会命中同一份模糊背景，换图后玻璃里还是上一张的虚化（评审 2.4-2）。
    private var blurCache: [String: MTLTexture] = [:]
    /// 原图纹理缓存：key = 图像身份 + 尺寸。拖参数时原图不变，没必要每帧重新上传（评审 2.4-4）
    private var photoTexCache: [String: MTLTexture] = [:]
    /// 渲染目标纹理缓存：key 只看尺寸——每次渲染都会整幅覆盖，不会残留上一张的内容
    private var outTexCache: [String: MTLTexture] = [:]
    /// 上一次渲染用的图像身份，一旦变化就整表作废
    private var lastImageKey = ""
    private let cacheLock = NSLock()

    /// 一次只允许一个渲染进 GPU。
    /// 预览与导出共用同一套纹理缓存，且可能同时发生（导出在后台跑、预览防抖到点），
    /// 并发时会拿到同一个 outTex 互相覆盖，必须串行。
    private let renderLock = NSLock()

    /// 只缓存到这个像素数以内的纹理（约 1400×1400 的预览规模）。
    /// 导出这种几十 MP 的纹理用完就放，否则常驻几百 MB 显存。
    private static let cacheMaxPixels = 4_000_000

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
    ///
    /// - Parameter imageKey: 图像身份（同一张源图必须传同一个值，换图必须换值）。
    ///   用于区分模糊/原图纹理缓存；传空串时退化为按 `CGImage` 指针区分。
    func renderGlass(photo: CGImage,
                     style: WatermarkStyle,
                     layout: WatermarkLayout,
                     themeDark: Bool = false,
                     imageKey: String = "") -> CGImage? {
        renderLock.lock()
        defer { renderLock.unlock() }
        return renderGlassLocked(photo: photo, style: style, layout: layout,
                                 themeDark: themeDark, imageKey: imageKey)
    }

    private func renderGlassLocked(photo: CGImage,
                                   style: WatermarkStyle,
                                   layout: WatermarkLayout,
                                   themeDark: Bool,
                                   imageKey: String) -> CGImage? {
        guard let device, let queue, let pipeline, let ciContext else { return nil }

        let fullW = photo.width
        let fullH = photo.height
        guard fullW > 0, fullH > 0, layout.hasContent else { return photo }

        // 图像身份变了先让上一张的所有派生缓存失效
        prepare(for: imageKey)
        let identity = imageKey.isEmpty ? "ptr-\(ObjectIdentifier(photo).hashValue)" : imageKey
        // 大图（导出）不缓存纹理：几十 MB 一份，留着只会挤爆显存
        let cacheable = fullW * fullH <= Self.cacheMaxPixels

        guard let photoTex = photoTexture(photo, device: device,
                                          key: "\(identity)-photo-\(fullW)x\(fullH)",
                                          cacheable: cacheable) else { return nil }

        // 与 Android 相同的比例：轻度模糊 sigma = w/63，强模糊 w/19
        let w = CGFloat(fullW)
        guard let blur1 = blurredTexture(photo, sigma: w / 63, ciContext: ciContext, device: device,
                                         key: "\(identity)-b1-\(fullW)x\(fullH)", cacheable: cacheable),
              let blur2 = blurredTexture(photo, sigma: w / 19, ciContext: ciContext, device: device,
                                         key: "\(identity)-b2-\(fullW)x\(fullH)", cacheable: cacheable) else { return nil }

        guard let outTex = outputTexture(device: device, width: fullW, height: fullH,
                                         cacheable: cacheable) else { return nil }

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
        // 马上要回读像素，无法用 addCompletedHandler 省掉这次等待
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

    /// 上传原图纹理（带缓存）
    private func photoTexture(_ image: CGImage, device: MTLDevice,
                              key: String, cacheable: Bool) -> MTLTexture? {
        if cacheable {
            cacheLock.lock()
            let hit = photoTexCache[key]
            cacheLock.unlock()
            if let hit { return hit }
        }

        guard let tex = makeTexture(image, device: device) else { return nil }

        if cacheable {
            cacheLock.lock()
            // 预览与导出尺寸不同，各留一份就够，多留只是白占显存
            if photoTexCache.count >= 2 { photoTexCache.removeAll() }
            photoTexCache[key] = tex
            cacheLock.unlock()
        }
        return tex
    }

    /// 渲染目标纹理（带缓存）。缓存的纹理每次都会被整幅覆盖，复用安全。
    private func outputTexture(device: MTLDevice, width w: Int, height h: Int,
                               cacheable: Bool) -> MTLTexture? {
        let key = "out-\(w)x\(h)"
        if cacheable {
            cacheLock.lock()
            let hit = outTexCache[key]
            cacheLock.unlock()
            if let hit { return hit }
        }

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                                                           width: w, height: h,
                                                           mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]
        // iOS 只支持 shared（managed 是 macOS 专属），统一内存下也能直接被 CPU 读取
        desc.storageMode = .shared
        guard let tex = device.makeTexture(descriptor: desc) else { return nil }

        if cacheable {
            cacheLock.lock()
            if outTexCache.count >= 2 { outTexCache.removeAll() }
            outTexCache[key] = tex
            cacheLock.unlock()
        }
        return tex
    }

    /// CGImage → MTLTexture。
    ///
    /// 用 malloc 的缓冲直接给 CGContext 画，省掉 Swift 数组的桥接与一次整体拷贝；
    /// 上传后立刻 free，峰值只有「一张图」而不是「数组 + 纹理存储」两份。
    private func makeTexture(_ image: CGImage, device: MTLDevice) -> MTLTexture? {
        let w = image.width, h = image.height
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                                                           width: w, height: h, mipmapped: false)
        desc.usage = .shaderRead
        desc.storageMode = .shared
        guard let tex = device.makeTexture(descriptor: desc) else { return nil }

        let bytesPerRow = w * 4
        let byteCount = bytesPerRow * h
        guard let base = malloc(byteCount) else { return nil }

        // 单独一层作用域：CGContext 先析构，再 free 缓冲，避免任何延迟写入落到已释放内存
        guard drawBitmap(image, into: base, width: w, height: h, bytesPerRow: bytesPerRow) else {
            free(base)
            return nil
        }

        tex.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0,
                    withBytes: UnsafeRawPointer(base), bytesPerRow: bytesPerRow)
        free(base)
        return tex
    }

    private func drawBitmap(_ image: CGImage, into base: UnsafeMutableRawPointer,
                            width w: Int, height h: Int, bytesPerRow: Int) -> Bool {
        guard let ctx = CGContext(data: base, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: bytesPerRow,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                    | CGBitmapInfo.byteOrder32Little.rawValue) else { return false }
        // 位图上下文的第一行就是图像顶行，直接画即可（不要翻转），
        // 这样纹理行 0 = 画面顶部，与着色器 uv.y=0 的约定一致。
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return true
    }

    /// 按 Android 的高斯金字塔思路：先降采样到 sigma 约 2.5px，再做高斯。
    /// 返回低分辨率纹理，着色器用 LINEAR 采样放大，观感与全分辨率高斯一致。
    ///
    /// - Parameter cacheable: 大于约 4MP 的导出纹理不缓存（几十 MB 一份，留着会挤爆显存）。
    private func blurredTexture(_ image: CGImage, sigma: CGFloat,
                                ciContext: CIContext, device: MTLDevice,
                                key: String, cacheable: Bool) -> MTLTexture? {
        let cacheKey = "\(key)-\(Int(sigma))"
        if cacheable {
            cacheLock.lock()
            if let hit = blurCache[cacheKey] { cacheLock.unlock(); return hit }
            cacheLock.unlock()
        }

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
        // 关键：模糊纹理要按「降采样后的尺寸」创建。
        // CIImage 在 clampedToExtent() 之后 extent 会变成无限大，旧代码用 src.extent
        // 裁切，结果 extent 恒等于整幅图 —— 48MP 导出时 blur1/blur2 都是全尺寸纹理
        // （各约 190MB），一张图就多占近 400MB 显存。这里先在降采样图上取 extent。
        let scaledExtent = img.extent.integral
        let g = CIFilter.gaussianBlur()
        g.inputImage = img.clampedToExtent()
        g.radius = Float(s)
        guard let blurred = g.outputImage?.cropped(to: scaledExtent) else { return nil }

        guard let cg = ciContext.createCGImage(blurred, from: scaledExtent),
              let tex = makeTexture(cg, device: device) else { return nil }

        if cacheable {
            cacheLock.lock()
            if blurCache.count > 8 { blurCache.removeAll() }
            blurCache[cacheKey] = tex
            cacheLock.unlock()
        }
        return tex
    }

    /// 图像身份变化时清空所有派生缓存（模糊、原图纹理、渲染目标）。
    ///
    /// `WatermarkCanvas.composite` 每次都会把当前图像身份带下来，所以即使调用方
    /// 忘了显式 `clearCache()`，换图后也不会复用上一张的模糊背景。
    private func prepare(for imageKey: String) {
        guard !imageKey.isEmpty else { return }
        cacheLock.lock()
        defer { cacheLock.unlock() }
        guard lastImageKey != imageKey else { return }
        blurCache.removeAll()
        photoTexCache.removeAll()
        outTexCache.removeAll()
        lastImageKey = imageKey
    }

    /// 换图 / 退出页面时主动清空（`shared` 是跨页面单例，不清理会一直留着上一张的纹理）
    func clearCache() {
        cacheLock.lock()
        blurCache.removeAll()
        photoTexCache.removeAll()
        outTexCache.removeAll()
        lastImageKey = ""
        cacheLock.unlock()
    }

    // MARK: - 回读

    private func cgImage(from texture: MTLTexture, width w: Int, height h: Int) -> CGImage? {
        let bytesPerRow = w * 4
        let byteCount = bytesPerRow * h
        // shared 纹理，直接 getBytes 到 malloc 缓冲
        guard let base = malloc(byteCount) else { return nil }
        texture.getBytes(base, bytesPerRow: bytesPerRow,
                         from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)

        // 把这块内存直接交给 CGDataProvider（release 回调里 free），
        // 不再 `Data(data)` 复制整幅图——48MP 时这一份拷贝就是 192MB（评审 2.4-4）。
        // 若 CGImage 创建失败，provider 释放时会自动 free，不会泄漏也不会双释放。
        guard let provider = CGDataProvider(dataInfo: base,
                                            data: UnsafeRawPointer(base),
                                            size: byteCount,
                                            releaseData: { info, _, _ in
                                                free(info)
                                            }) else {
            free(base)
            return nil
        }

        // 纹理第 0 行就是画面顶行，CGImage 也是第 0 行在上，
        // 两边约定一致，不需要再翻一次——多翻一次会让照片正、面板反。
        let alphaInfo: CGBitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue)
            .union(CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue))
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
