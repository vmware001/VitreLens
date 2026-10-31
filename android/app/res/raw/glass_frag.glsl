precision highp float;
uniform sampler2D u_photo;
uniform sampler2D u_blur;
uniform sampler2D u_blur2;
uniform vec2 u_res;
uniform vec4 u_panel;
uniform vec4 u_crop;
uniform float u_radius;
uniform float u_depth;
uniform float u_thickness;
uniform float u_ior;
uniform float u_rough;
uniform float u_refl;
uniform float u_trans;
uniform float u_disp;
uniform float u_warp;
uniform float u_edge;
uniform float u_segments;
uniform vec3 u_tint;
uniform float u_tintOn;
uniform float u_scale;
uniform float u_shadow;
uniform float u_white;
uniform vec3 u_opaque;
uniform float u_gloss;

float sdRoundBox(vec2 p, vec2 b, float r) {
  r = min(r, min(b.x, b.y));
  vec2 q = abs(p) - b + vec2(r);
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
float profile(float d) {
  float t = clamp(d / max(u_depth, 0.5), 0.0, 1.0);
  return sin(t * 1.5707963);
}
vec2 outwardDir(vec2 q, vec2 half_, float r) {
  float rr = min(r, min(half_.x, half_.y));
  vec2 b = max(half_ - vec2(rr), vec2(0.001));
  vec2 dd = q - clamp(q, -b, b);
  float l = length(dd);
  return l > 1e-4 ? dd / l : vec2(0.0, 1.0);
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
vec3 transmissionRay(vec3 N, float ior) {
  return refract(vec3(0.0, 0.0, -1.0), N, 1.0 / max(ior, 1.0001)) * u_thickness;
}
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
  float roughEff = clamp(u_rough, 0.0, 1.0) * clamp(u_ior * 2.0 - 2.0, 0.0, 1.0);
  float halfSpread = clamp(u_disp, 0.0, 2.0) * 0.5;
  vec3 iors = vec3(u_ior - halfSpread, u_ior, u_ior + halfSpread);
  vec3 rayR = transmissionRay(N, iors.x);
  vec3 rayG = transmissionRay(N, iors.y);
  vec3 rayB = transmissionRay(N, iors.z);

  vec2 qn = q / max(half_, vec2(1.0));
  float vfall = 1.0 - qn.y * qn.y;
  float cyl = -qn.x * vfall * 0.85 - qn.x * 0.25;
  float warpBase = min(u_thickness, half_.y * 0.7);
  float maxWarp = half_.y * 0.30;
  float warpPx = clamp(cyl * u_warp * warpBase * 0.35, -maxWarp, maxWarp);
  rayR.x += warpPx; rayG.x += warpPx; rayB.x += warpPx;

  float et = clamp(d / max(u_depth, 0.5), 0.0, 1.0);
  float edgeFall = (1.0 - et) * (1.0 - et);
  vec2 lensDir = -outwardDir(q, half_, u_radius);
  float lensAmt = u_edge * edgeFall;
  float lensDisp = clamp(u_disp, 0.0, 2.0) * 0.12;
  rayR.xy += lensDir * lensAmt * (1.0 - lensDisp);
  rayG.xy += lensDir * lensAmt;
  rayB.xy += lensDir * lensAmt * (1.0 + lensDisp);

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

  vec3 env = vec3(0.96);
  vec3 col = mix(env, transmitted, clamp(u_trans, 0.0, 1.0));
  if (u_tintOn > 0.5) col *= u_tint;
  float cosV = clamp(N.z, 0.0, 1.0);
  float fres = pow(1.0 - cosV, 5.0);
  float F = clamp((0.04 + 0.96 * fres) * u_refl, 0.0, 1.0);
  vec3 L = normalize(vec3(-0.45, -0.72, 0.55));
  float shininess = mix(180.0, 18.0, clamp(u_rough, 0.0, 1.0));
  float spec = pow(max(dot(reflect(vec3(0.0, 0.0, -1.0), N), L), 0.0), shininess);
  col = mix(col, env, F);
  col += vec3(1.0) * spec * u_gloss * (1.0 - clamp(u_rough, 0.0, 1.0) * 0.8) * 0.7;
  float sheenDir = max(0.0, dot(normalize(N.xy + vec2(1e-5)), vec2(-0.45, -0.72)));
  col += vec3(1.0) * sheenDir * smoothstep(0.0, 0.5, 1.0 - cosV) * 0.09;
  float rim = 1.0 - smoothstep(0.0, max(2.4 * u_scale, 1.0), d);
  col += vec3(1.0) * rim * 0.14 * u_refl * clamp(-N.y + 0.75, 0.0, 1.0);
  col = mix(col, u_opaque, clamp(u_white, 0.0, 1.0) * 0.92);
  gl_FragColor = vec4(mix(photo, col, mask), 1.0);
}
