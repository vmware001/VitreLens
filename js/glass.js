/* ═══════════════════════════════════════════════════════════
   glass.js — WebGL 液态玻璃渲染器（与 Android 同一套 PBR 参数）
   圆角矩形高度场 + 厚度透射 + 边缘透镜 + Schlick 反射。
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var VERT = 'attribute vec2 a_pos;void main(){gl_Position=vec4(a_pos,0.0,1.0);}';

  var FRAG = `precision highp float;
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
}`;

  var UNIFORMS = ['u_photo', 'u_blur', 'u_blur2', 'u_res', 'u_panel', 'u_crop',
    'u_radius', 'u_depth', 'u_thickness', 'u_ior', 'u_rough', 'u_refl', 'u_trans',
    'u_disp', 'u_warp', 'u_edge', 'u_segments', 'u_tint', 'u_tintOn', 'u_scale',
    'u_shadow', 'u_white', 'u_opaque', 'u_gloss'];

  function GlassRenderer() {
    this.canvas = document.createElement('canvas');
    var opts = { alpha: false, antialias: false, depth: false, stencil: false,
      premultipliedAlpha: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' };
    var gl = this.canvas.getContext('webgl', opts) || this.canvas.getContext('experimental-webgl', opts);
    if (!gl) throw new Error('当前浏览器未启用 WebGL');
    this.gl = gl;
    this.maxTex = Math.min(gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096, 8192);
    this.image = null;
    this.blurDirty = true;
    this.darkTone = false;
    this._compile();
    this._initQuad();
    this.photoTex = this._texture();
    this.blurTex = this._texture();
    this.blurTex2 = this._texture();
    this.blurCanvas = document.createElement('canvas');
    this.blurCanvas2 = document.createElement('canvas');
    this.lumCanvas = document.createElement('canvas');
  }

  GlassRenderer.prototype._shader = function (type, src) {
    var gl = this.gl, sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      var log = gl.getShaderInfoLog(sh);
      gl.deleteShader(sh);
      throw new Error('着色器编译失败: ' + log);
    }
    return sh;
  };

  GlassRenderer.prototype._compile = function () {
    var gl = this.gl, vs = this._shader(gl.VERTEX_SHADER, VERT), fs = this._shader(gl.FRAGMENT_SHADER, FRAG);
    var prog = gl.createProgram();
    gl.attachShader(prog, vs); gl.attachShader(prog, fs); gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error('程序链接失败: ' + gl.getProgramInfoLog(prog));
    gl.deleteShader(vs); gl.deleteShader(fs); gl.useProgram(prog);
    this.program = prog;
    this.uni = {};
    for (var i = 0; i < UNIFORMS.length; i++) this.uni[UNIFORMS[i]] = gl.getUniformLocation(prog, UNIFORMS[i]);
    this.aPos = gl.getAttribLocation(prog, 'a_pos');
  };

  GlassRenderer.prototype._initQuad = function () {
    var gl = this.gl, buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, -1,1, 1,-1, 1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(this.aPos);
    gl.vertexAttribPointer(this.aPos, 2, gl.FLOAT, false, 0, 0);
  };

  GlassRenderer.prototype._texture = function () {
    var gl = this.gl, tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    return tex;
  };

  GlassRenderer.prototype.setImage = function (img) {
    if (this.image === img) return;
    this.image = img;
    this.texSource = null;
    var iw = img.naturalWidth || img.width, ih = img.naturalHeight || img.height;
    var src = img, limit = this.maxTex;
    if (Math.max(iw, ih) > limit) {
      var k = limit / Math.max(iw, ih), c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(iw * k)); c.height = Math.max(1, Math.round(ih * k));
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height); src = c;
    }
    this.texSource = src;
    var gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    this.blurDirty = true;
  };

  GlassRenderer.prototype._blurInto = function (img, canvas, tex, ratio) {
    var iw = img.naturalWidth || img.width, ih = img.naturalHeight || img.height;
    var s = Math.min(1, 420 / Math.max(iw, ih));
    var w = Math.max(8, Math.round(iw * s)), h = Math.max(8, Math.round(ih * s));
    canvas.width = w; canvas.height = h;
    var ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    if (typeof ctx.filter === 'string') ctx.filter = 'blur(' + Math.max(1, w * ratio).toFixed(2) + 'px)';
    ctx.drawImage(img, 0, 0, w, h);
    if (typeof ctx.filter === 'string') ctx.filter = 'none';
    var gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
  };

  GlassRenderer.prototype._buildBlurFrom = function (img) {
    if (!img) return;
    this._blurInto(img, this.blurCanvas, this.blurTex, 1 / 63);
    this._blurInto(img, this.blurCanvas2, this.blurTex2, 1 / 19);
    this.lumCanvas.width = this.lumCanvas.height = 16;
    var ctx = this.lumCanvas.getContext('2d');
    ctx.drawImage(img, 0, 0, 16, 16);
    try {
      var d = ctx.getImageData(0, 0, 16, 16).data, sum = 0;
      for (var i = 0; i < d.length; i += 4) sum += d[i] * .2126 + d[i+1] * .7152 + d[i+2] * .0722;
      this.darkTone = sum / (d.length / 4) / 255 < .48;
    } catch (e) { this.darkTone = false; }
  };

  GlassRenderer.prototype._buildBlur = function () {
    if (!this.image || !this.blurDirty) return;
    this._buildBlurFrom(this.image);
    this.blurDirty = false;
  };

  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }

  GlassRenderer.prototype._render = function (w, h, p, o, fullW) {
    var gl = this.gl;
    w = Math.max(2, Math.round(w)); h = Math.max(2, Math.round(h));
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
    gl.viewport(0, 0, w, h); gl.useProgram(this.program);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.photoTex); gl.uniform1i(this.uni.u_photo, 0);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, this.blurTex); gl.uniform1i(this.uni.u_blur, 1);
    gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, this.blurTex2); gl.uniform1i(this.uni.u_blur2, 2);

    var u = this.uni, depth = Math.max(2, p.bevel || 2);
    gl.uniform2f(u.u_res, w, h);
    gl.uniform4f(u.u_panel, p.x, p.y, p.w, p.h);
    gl.uniform4f(u.u_crop, 0, 0, 1, 1);
    gl.uniform1f(u.u_radius, p.r);
    gl.uniform1f(u.u_depth, depth);
    gl.uniform1f(u.u_thickness, Math.max(1, p.h * 1.1 * num(o.thickness, .36)));
    gl.uniform1f(u.u_ior, num(o.ior, 1.46));
    gl.uniform1f(u.u_rough, num(o.rough, .2));
    gl.uniform1f(u.u_refl, num(o.refl, .28));
    gl.uniform1f(u.u_trans, num(o.trans, .98));
    gl.uniform1f(u.u_disp, num(o.disp, .06));
    gl.uniform1f(u.u_warp, num(o.warp, 0));
    gl.uniform1f(u.u_edge, Math.min(Math.max(0, num(o.edge, 1.4)) * depth * 2, p.h * .45));
    gl.uniform1f(u.u_segments, num(o.segments, 128));
    gl.uniform3f(u.u_tint, .99, .99, 1);
    gl.uniform1f(u.u_tintOn, 1);
    gl.uniform1f(u.u_scale, (fullW || w) / 1000);
    gl.uniform1f(u.u_shadow, num(o.shadow, .18));
    gl.uniform1f(u.u_white, num(o.white, 0));
    var dark = o.frostTone === 1 || (o.frostTone !== 2 && this.darkTone);
    if (dark) gl.uniform3f(u.u_opaque, .07, .07, .08);
    else gl.uniform3f(u.u_opaque, .97, .97, .98);
    gl.uniform1f(u.u_gloss, num(o.gloss, .28));
    gl.drawArrays(gl.TRIANGLES, 0, 6);
    return this.canvas;
  };

  GlassRenderer.prototype.render = function (img, w, h, p, o) {
    this.setImage(img);
    this._buildBlur();
    return this._render(w, h, p, o, w);
  };

  GlassRenderer.prototype.renderCropped = function (src, w, h, p, o, fullW) {
    var gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    this.image = null;
    this._buildBlurFrom(src);
    return this._render(w, h, p, o, fullW);
  };

  global.LG = global.LG || {};
  global.LG.GlassRenderer = GlassRenderer;
})(window);
