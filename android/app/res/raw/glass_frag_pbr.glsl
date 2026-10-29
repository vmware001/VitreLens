precision highp float;

// 液态玻璃 —— 移植 three.js MeshPhysicalMaterial 的 transmission / dispersion。
//
// 参考对象（@specy/liquid-glass）的做法：html2canvas 截一张背景图 → 铺在一个
// 挤出成型的圆角玻璃体下方 → 交给 three.js 的物理材质做透射。
// three.js 内部（transmission_fragment.glsl.js）的核心是：
//
//   vec3 getVolumeTransmissionRay( n, v, thickness, ior, modelMatrix ) {
//       vec3 refractionVector = refract( -v, normalize( n ), 1.0 / ior );
//       return normalize( refractionVector ) * thickness * modelScale;
//   }
//   float applyIorToRoughness( roughness, ior ) {
//       return roughness * clamp( ior * 2.0 - 2.0, 0.0, 1.0 );
//   }
//   vec4 getTransmissionSample( fragCoord, roughness, ior ) {
//       float lod = log2( transmissionSamplerSize.x ) * applyIorToRoughness( roughness, ior );
//       return textureLod( transmissionSamplerMap, fragCoord.xy, lod );
//   }
//   float halfSpread = dispersion * 0.5;
//   vec3 iors = vec3( ior - halfSpread, ior, ior + halfSpread );
//   fragCoord.xy += transmissionRay.xy;
//   transmitted.r = getTransmissionSample( fragCoord, roughness, iors.x ).r;
//   transmitted.g = getTransmissionSample( fragCoord, roughness, iors.y ).g;
//   transmitted.b = getTransmissionSample( fragCoord, roughness, iors.z ).b;
//   totalDiffuse = mix( diffuse, transmitted, transmission );
//
// 逐条对上：光线沿折射方向走「厚度」这么长的路程（不除 cos），位移直接加到 uv；
// 粗糙度按 ior 修正后选模糊层级；色散用 ±disp*0.5 的绝对折射率偏移。
// 几何用解析高度场代替网格（水印是规则圆角矩形，不需要三角面）。

uniform sampler2D u_photo;   // 清晰背景（≈ mip0）
uniform sampler2D u_blur;    // 中等模糊（≈ mip1）
uniform sampler2D u_blur2;   // 强模糊（≈ mip2）
uniform vec2  u_res;
uniform vec4  u_panel;
uniform vec4  u_crop;

uniform float u_radius;
uniform float u_depth;       // 收边宽度 = 玻璃体倒角（px）
uniform float u_thickness;   // 玻璃厚度 = 光线在玻璃里走过的路程（px）
uniform float u_ior;
uniform float u_rough;
uniform float u_refl;
uniform float u_trans;
uniform float u_disp;
uniform float u_warp;
uniform float u_segments;
uniform vec3  u_tint;
uniform float u_tintOn;

uniform float u_scale;
uniform float u_shadow;
uniform float u_white;
uniform float u_gloss;

float sdRoundBox(vec2 p, vec2 b, float r) {
    r = min(r, min(b.x, b.y));
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// 竖直剖面：水滴状边缘 —— 从边缘平滑升到顶面，法线连续。
// 原来用 sqrt(1-(1-t)^2)：这是四分之一圆，t=0 处导数为无穷，
// 法线瞬间变成水平，深度一大就在边缘折出直角（看起来像被切了一刀）。
// 改用 sin 弧：边缘斜率有限（≈1.57），中段最陡，到 depth 处切线水平。
float profile(float d) {
    float t = clamp(d / max(u_depth, 0.5), 0.0, 1.0);
    return sin(t * 1.5707963);
}

float heightAt(vec2 q, vec2 half_, float r) {
    return profile(max(0.0, -sdRoundBox(q, half_, r)));
}

vec3 normalAt(vec2 q, vec2 half_, float r, float e) {
    float hR = heightAt(q + vec2(e, 0.0), half_, r);
    float hL = heightAt(q - vec2(e, 0.0), half_, r);
    float hD = heightAt(q + vec2(0.0, e), half_, r);
    float hU = heightAt(q - vec2(0.0, e), half_, r);
    vec2 g = vec2(hR - hL, hD - hU) / (2.0 * e) * u_depth;
    return normalize(vec3(-g, 1.0));
}

// three.js: transmissionRay = refract(-v, n, 1/ior) * thickness
vec3 transmissionRay(vec3 N, float ior) {
    return refract(vec3(0.0, 0.0, -1.0), N, 1.0 / max(ior, 1.0001)) * u_thickness;
}

// 三级近似 mip：roughness 越大，取越模糊的那一级
vec3 sampleTransmission(vec2 uv, vec2 offPx, float roughEff) {
    vec2 base = uv + offPx / u_res;
    float lod = clamp(roughEff, 0.0, 1.0) * 2.0;
    if (lod <= 0.001) return texture2D(u_photo, base).rgb;
    vec3 s1 = texture2D(u_blur, base).rgb;
    if (lod <= 1.0) return mix(texture2D(u_photo, base).rgb, s1, lod);
    return mix(s1, texture2D(u_blur2, base).rgb, lod - 1.0);
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);
    vec2 uv = p / u_res;
    vec3 photo = texture2D(u_photo, uv).rgb;

    vec2 half_ = u_panel.zw * 0.5;
    vec2 c = u_panel.xy + half_;
    vec2 q = p - c;
    float sdf = sdRoundBox(q, half_, u_radius);
    float mask = 1.0 - smoothstep(-1.0, 1.0, sdf);

    // 投影
    if (mask < 0.999) {
        float ssd = sdRoundBox(q - vec2(0.0, 6.0 * u_scale), half_, u_radius);
        float d0 = max(ssd, 0.0);
        float spread = 12.0 * u_scale;
        float soft = exp(-(d0 * d0) / (2.0 * spread * spread)) * 0.55;
        float contact = exp(-d0 / (2.0 * u_scale)) * 0.40;
        float sh = clamp((soft + contact) * u_shadow * (1.0 - smoothstep(-2.0, 0.0, ssd)), 0.0, 0.92);
        photo = mix(photo, vec3(0.0), sh);
    }

    if (mask <= 0.002) { gl_FragColor = vec4(photo, 1.0); return; }

    float d = max(-sdf, 0.0);
    float e = max(u_depth / max(u_segments, 4.0), 0.35);
    vec3 N = normalAt(q, half_, u_radius, e);

    // three.js: roughness 先按 ior 修正
    float roughEff = clamp(u_rough, 0.0, 1.0) * clamp(u_ior * 2.0 - 2.0, 0.0, 1.0);

    // three.js 色散：三通道用 ior ± dispersion*0.5（允许到 2.0，色散才够明显）
    float halfSpread = clamp(u_disp, 0.0, 2.0) * 0.5;
    vec3 iors = vec3(u_ior - halfSpread, u_ior, u_ior + halfSpread);

    vec3 rayR = transmissionRay(N, iors.x);
    vec3 rayG = transmissionRay(N, iors.y);
    vec3 rayB = transmissionRay(N, iors.z);

    // 横向扭曲：隔着玻璃砖看东西时左右方向的错动。
    // 用归一化坐标算一个柱面压缩量，上下边缘最强，中间基本不动。
    vec2 qn = q / max(half_, vec2(1.0));
    float cyl = -qn.x * (1.0 - abs(qn.y)) * 0.85 - qn.x * 0.25;
    // 上限取面板高度的一部分。不加限制时位移与厚度成正比，
    // 厚度调大后能到几百像素，画面里的直线会被横向扯成直角。
    float maxWarp = half_.y * 0.35;
    float warpPx = clamp(cyl * u_warp * u_thickness * 0.55, -maxWarp, maxWarp);
    rayR.x += warpPx; rayG.x += warpPx; rayB.x += warpPx;

    // 越界限幅：位移以 crop 像素计，可用余量也换算成 crop 像素
    vec2 photoUv = u_crop.xy + uv * u_crop.zw;
    float fullWpx = u_res.x / max(u_crop.z, 1e-4);
    float fullHpx = u_res.y / max(u_crop.w, 1e-4);
    float aL = photoUv.x * fullWpx * 0.92;
    float aR = (1.0 - photoUv.x) * fullWpx * 0.92;
    float aT = photoUv.y * fullHpx * 0.92;
    float aB = (1.0 - photoUv.y) * fullHpx * 0.92;

    vec3 transmitted;
    transmitted.r = sampleTransmission(uv, vec2(clamp(rayR.x, -aL, aR), clamp(rayR.y, -aT, aB)), roughEff).r;
    transmitted.g = sampleTransmission(uv, vec2(clamp(rayG.x, -aL, aR), clamp(rayG.y, -aT, aB)), roughEff).g;
    transmitted.b = sampleTransmission(uv, vec2(clamp(rayB.x, -aL, aR), clamp(rayB.y, -aT, aB)), roughEff).b;

    // three.js: totalDiffuse = mix(diffuse, transmitted, transmission)
    vec3 env = vec3(0.86, 0.90, 0.96);
    vec3 col = mix(env, transmitted, clamp(u_trans, 0.0, 1.0));

    if (u_tintOn > 0.5) col *= u_tint;

    // 反射：菲涅尔 + 高光（roughness 影响高光锐度）
    float cosV = clamp(N.z, 0.0, 1.0);
    float fres = pow(1.0 - cosV, 4.0);
    float F = clamp((0.04 + 0.96 * fres) * u_refl, 0.0, 1.0);
    vec3 L = normalize(vec3(-0.45, -0.72, 0.55));
    float shininess = mix(180.0, 18.0, clamp(u_rough, 0.0, 1.0));
    float spec = pow(max(dot(reflect(vec3(0.0, 0.0, -1.0), N), L), 0.0), shininess);
    col = mix(col, env, F);
    col += vec3(1.0) * spec * u_gloss * (1.0 - clamp(u_rough, 0.0, 1.0) * 0.8) * 1.1;

    // 边缘细亮线
    float rim = 1.0 - smoothstep(0.0, max(2.4 * u_scale, 1.0), d);
    col += vec3(1.0) * rim * 0.26 * u_refl * clamp(-N.y + 0.75, 0.0, 1.0);

    col = mix(col, vec3(1.0), clamp(u_white, 0.0, 1.0) * 0.85);

    gl_FragColor = vec4(mix(photo, col, mask), 1.0);
}
