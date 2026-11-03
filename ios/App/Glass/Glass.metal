#include <metal_stdlib>
using namespace metal;

// 液态玻璃 —— 逐行移植自 Android 的 glass_frag_pbr.glsl。
//
// 结构、常量、注释里的推导保持一一对应；唯一的差别是取值来源：
// GLSL 用 gl_FragCoord（左下角原点，所以自己翻了 y），Metal 直接在顶点阶段
// 生成左上角原点的 uv，片元里 p = uv * res 就是同一套坐标。

struct Uniforms {
    float4 panel;      // x, y, w, h（画布像素）
    float4 crop;       // 该块在整幅图里的归一化位置与大小
    float4 resScale;   // x=res.w, y=res.h, z=scale, w=tintOn
    float4 iorRough;   // x=ior, y=rough, z=refl, w=trans
    float4 geom;       // x=radius, y=depth, z=thickness, w=edge
    float4 extra;      // x=disp, y=warp, z=segments, w=shadow
    float4 tint;       // rgb + w=white（不透明度）
    float4 opaque;     // rgb + w=gloss
};

struct VOut {
    float4 pos [[position]];
    float2 uv;
};

vertex VOut vs_glass(uint vid [[vertex_id]]) {
    // 四点三角带，顺序：左下、右下、左上、右上
    float2 quad[4] = {
        float2(-1.0, -1.0),
        float2( 1.0, -1.0),
        float2(-1.0,  1.0),
        float2( 1.0,  1.0)
    };
    float2 xy = quad[vid];
    VOut o;
    o.pos = float4(xy, 0.0, 1.0);
    // 左上角原点：uv.y 向下增长，和 GLSL 里 (res.y - gl_FragCoord.y) 等价
    o.uv = float2((xy.x + 1.0) * 0.5, (1.0 - xy.y) * 0.5);
    return o;
}

constexpr sampler kSample(coord::normalized, address::clamp_to_edge, filter::linear);

static inline float sdRoundBox(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// 竖直剖面：sin 弧，边缘斜率有限（≈1.57），到 depth 处切线水平
static inline float profileAt(float d, float depth) {
    float t = clamp(d / max(depth, 0.5), 0.0, 1.0);
    return sin(t * 1.5707963);
}

// 圆角矩形的 2D 外法线：直边是轴向，圆角指向弧心外侧
static inline float2 outwardDir(float2 q, float2 half_, float r) {
    float rr = min(r, min(half_.x, half_.y));
    float2 b = max(half_ - float2(rr), float2(0.001));
    float2 dd = q - clamp(q, -b, b);
    float l = length(dd);
    return l > 1e-4 ? dd / l : float2(0.0, 1.0);
}

static inline float heightAt(float2 q, float2 half_, float r, float depth) {
    return profileAt(max(0.0, -sdRoundBox(q, half_, r)), depth);
}

static inline float3 normalAt(float2 q, float2 half_, float r, float e, float depth) {
    float hR = heightAt(q + float2(e, 0.0), half_, r, depth);
    float hL = heightAt(q - float2(e, 0.0), half_, r, depth);
    float hD = heightAt(q + float2(0.0, e), half_, r, depth);
    float hU = heightAt(q - float2(0.0, e), half_, r, depth);
    float2 g = float2(hR - hL, hD - hU) / (2.0 * e) * depth;
    return normalize(float3(-g, 1.0));
}

// three.js: transmissionRay = refract(-v, n, 1/ior) * thickness
static inline float3 transmissionRay(float3 N, float ior, float thickness) {
    return refract(float3(0.0, 0.0, -1.0), N, 1.0 / max(ior, 1.0001)) * thickness;
}

// 三级近似 mip：roughness 越大取越糊的那一级
static inline float3 sampleTransmission(texture2d<float> photo,
                                        texture2d<float> blur1,
                                        texture2d<float> blur2,
                                        float2 uv,
                                        float2 offPx,
                                        float2 res,
                                        float roughEff) {
    float2 base = uv + offPx / res;
    float lod = clamp(roughEff, 0.0, 1.0) * 2.0;
    if (lod <= 0.001) return photo.sample(kSample, base).rgb;
    float3 s1 = blur1.sample(kSample, base).rgb;
    if (lod <= 1.0) return mix(photo.sample(kSample, base).rgb, s1, lod);
    return mix(s1, blur2.sample(kSample, base).rgb, lod - 1.0);
}

fragment float4 fs_glass(VOut in [[stage_in]],
                         constant Uniforms &u [[buffer(0)]],
                         texture2d<float> photo [[texture(0)]],
                         texture2d<float> blur1 [[texture(1)]],
                         texture2d<float> blur2 [[texture(2)]]) {

    float2 res = u.resScale.xy;
    float uScale = u.resScale.z;
    float uRadius = u.geom.x;
    float uDepth = u.geom.y;
    float uThickness = u.geom.z;
    float uEdge = u.geom.w;
    float uIor = u.iorRough.x;
    float uRough = u.iorRough.y;
    float uRefl = u.iorRough.z;
    float uTrans = u.iorRough.w;
    float uDisp = u.extra.x;
    float uWarp = u.extra.y;
    float uSegments = u.extra.z;
    float uShadow = u.extra.w;
    float uWhite = u.tint.w;
    float uGloss = u.opaque.w;
    float uTintOn = u.resScale.w;

    float2 p = in.uv * res;
    float2 uv = in.uv;
    float3 colPhoto = photo.sample(kSample, uv).rgb;

    float2 half_ = u.panel.zw * 0.5;
    float2 c = u.panel.xy + half_;
    float2 q = p - c;
    float sdf = sdRoundBox(q, half_, uRadius);
    float mask = 1.0 - smoothstep(-1.0, 1.0, sdf);

    // 投影
    if (mask < 0.999) {
        float ssd = sdRoundBox(q - float2(0.0, 6.0 * uScale), half_, uRadius);
        float d0 = max(ssd, 0.0);
        float spread = 12.0 * uScale;
        float soft = exp(-(d0 * d0) / (2.0 * spread * spread)) * 0.55;
        float contact = exp(-d0 / (2.0 * uScale)) * 0.40;
        float sh = clamp((soft + contact) * uShadow * (1.0 - smoothstep(-2.0, 0.0, ssd)), 0.0, 0.92);
        colPhoto = mix(colPhoto, float3(0.0), sh);
    }

    if (mask <= 0.002) return float4(colPhoto, 1.0);

    float d = max(-sdf, 0.0);
    float e = max(uDepth / max(uSegments, 4.0), 0.35);
    float3 N = normalAt(q, half_, uRadius, e, uDepth);

    // three.js: roughness 先按 ior 修正
    float roughEff = clamp(uRough, 0.0, 1.0) * clamp(uIor * 2.0 - 2.0, 0.0, 1.0);

    // three.js 色散：三通道用 ior ± dispersion*0.5
    float halfSpread = clamp(uDisp, 0.0, 2.0) * 0.5;
    float3 iors = float3(uIor - halfSpread, uIor, uIor + halfSpread);

    float3 rayR = transmissionRay(N, iors.x, uThickness);
    float3 rayG = transmissionRay(N, iors.y, uThickness);
    float3 rayB = transmissionRay(N, iors.z, uThickness);

    // 横向扭曲：抛物线权重，中心导数为 0，避免折痕
    float2 qn = q / max(half_, float2(1.0));
    float vfall = 1.0 - qn.y * qn.y;
    float cyl = -qn.x * vfall * 0.85 - qn.x * 0.25;
    float warpBase = min(uThickness, half_.y * 0.7);
    float maxWarp = half_.y * 0.30;
    float warpPx = clamp(cyl * uWarp * warpBase * 0.35, -maxWarp, maxWarp);
    rayR.x += warpPx; rayG.x += warpPx; rayB.x += warpPx;

    // ── 边缘透镜 ──
    float et = clamp(d / max(uDepth, 0.5), 0.0, 1.0);
    float edgeFall = (1.0 - et) * (1.0 - et);
    float2 lensDir = -outwardDir(q, half_, uRadius);   // 指向面板内部
    float lensAmt = uEdge * edgeFall;
    float lensDisp = clamp(uDisp, 0.0, 2.0) * 0.12;
    rayR.xy += lensDir * lensAmt * (1.0 - lensDisp);
    rayG.xy += lensDir * lensAmt;
    rayB.xy += lensDir * lensAmt * (1.0 + lensDisp);

    // 越界限幅：位移以 crop 像素计，可用余量也换算成 crop 像素
    float2 photoUv = u.crop.xy + uv * u.crop.zw;
    float fullWpx = res.x / max(u.crop.z, 1e-4);
    float fullHpx = res.y / max(u.crop.w, 1e-4);
    float aL = photoUv.x * fullWpx * 0.92;
    float aR = (1.0 - photoUv.x) * fullWpx * 0.92;
    float aT = photoUv.y * fullHpx * 0.92;
    float aB = (1.0 - photoUv.y) * fullHpx * 0.92;

    float3 transmitted;
    transmitted.r = sampleTransmission(photo, blur1, blur2, uv,
                                       float2(clamp(rayR.x, -aL, aR), clamp(rayR.y, -aT, aB)), res, roughEff).r;
    transmitted.g = sampleTransmission(photo, blur1, blur2, uv,
                                       float2(clamp(rayG.x, -aL, aR), clamp(rayG.y, -aT, aB)), res, roughEff).g;
    transmitted.b = sampleTransmission(photo, blur1, blur2, uv,
                                       float2(clamp(rayB.x, -aL, aR), clamp(rayB.y, -aT, aB)), res, roughEff).b;

    // three.js: totalDiffuse = mix(diffuse, transmitted, transmission)
    float3 env = float3(0.96);
    float3 col = mix(env, transmitted, clamp(uTrans, 0.0, 1.0));

    if (uTintOn > 0.5) col *= u.tint.rgb;

    // 反射：Schlick 五次方 + 高光
    float cosV = clamp(N.z, 0.0, 1.0);
    float fres = pow(1.0 - cosV, 5.0);
    float F = clamp((0.04 + 0.96 * fres) * uRefl, 0.0, 1.0);
    float3 L = normalize(float3(-0.45, -0.72, 0.55));
    float shininess = mix(180.0, 18.0, clamp(uRough, 0.0, 1.0));
    float spec = pow(max(dot(reflect(float3(0.0, 0.0, -1.0), N), L), 0.0), shininess);
    col = mix(col, env, F);
    col += float3(1.0) * spec * uGloss * (1.0 - clamp(uRough, 0.0, 1.0) * 0.8) * 0.7;

    // 斜面受光：让低对比背景上的边缘也能看出来是玻璃
    float sheenDir = max(0.0, dot(normalize(N.xy + float2(1e-5)), float2(-0.45, -0.72)));
    col += float3(1.0) * sheenDir * smoothstep(0.0, 0.5, 1.0 - cosV) * 0.09;

    // 边缘细亮线
    float rim = 1.0 - smoothstep(0.0, max(2.4 * uScale, 1.0), d);
    col += float3(1.0) * rim * 0.14 * uRefl * clamp(-N.y + 0.75, 0.0, 1.0);

    // 「色调」档：用实色提高不透明度，实色随背景明暗在近白/近黑之间切换
    col = mix(col, u.opaque.rgb, clamp(uWhite, 0.0, 1.0) * 0.92);

    return float4(mix(colPhoto, col, mask), 1.0);
}
