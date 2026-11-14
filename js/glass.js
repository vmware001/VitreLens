/* ═══════════════════════════════════════════════════════════
   glass.js — WebGL 液态玻璃渲染器（与 Android 同一套 PBR 参数）
   圆角矩形高度场 + 厚度透射 + 边缘透镜 + Schlick 反射。
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var VERT = 'attribute vec2 a_pos;void main(){gl_Position=vec4(a_pos,0.0,1.0);}';

  // 注意：这段 GLSL 是 Android 端的唯一真源（android/tools/extract_shaders.py 会导出成
  // res/raw/*.glsl）。采样语义（uv = 画布坐标 / u_res）不能单独改这里，否则下次构建
  // Android 就会拿到「按 u_crop 偏移采样」的着色器而 Java 侧仍上传条带纹理 —— 玻璃直接错位。
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

  // 预览纹理长边上限。8192 的 50MP 原图常驻显存约 192MiB，预览根本用不到那个精度；
  // 导出走独立实例（见 app.js 的 getExportRenderer），不受这个上限影响。
  var PREVIEW_TEX_MAX = 4096;

  function GlassRenderer(maxTexLimit) {
    this.canvas = document.createElement('canvas');
    // preserveDrawingBuffer:false —— 每帧 render 和随后的 drawImage 在同一个任务里完成，
    // 浏览器不需要额外保留/拷贝一份 backbuffer（移动端少一类 context lost）。
    var opts = { alpha: false, antialias: false, depth: false, stencil: false,
      premultipliedAlpha: false, preserveDrawingBuffer: false, powerPreference: 'high-performance' };
    var gl = this.canvas.getContext('webgl', opts) || this.canvas.getContext('experimental-webgl', opts);
    if (!gl) throw new Error('当前浏览器未启用 WebGL');
    this.gl = gl;
    this.maxTex = Math.min(gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096, maxTexLimit || PREVIEW_TEX_MAX);
    this.image = null;
    this.blurDirty = true;
    this.darkTone = false;
    this._imgId = 0;
    this._winKey = '';
    this._compile();
    this._initQuad();
    this.photoTex = this._texture();
    this.blurTex = this._texture();
    this.blurTex2 = this._texture();
    this.blurCanvas = document.createElement('canvas');
    this.blurCanvas2 = document.createElement('canvas');
    this.lumCanvas = document.createElement('canvas');
    this.winCanvas = document.createElement('canvas');
    this.lastClip = null;
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

  /** 记为「当前图」：换图才重算背景明暗（每帧 getImageData 会拖住 GPU 队列） */
  GlassRenderer.prototype.setImage = function (img) {
    if (this.image === img) return false;
    this.image = img;
    this._imgId++;
    this._winKey = '';
    this.blurDirty = true;
    this._measureTone(img);
    return true;
  };

  GlassRenderer.prototype._measureTone = function (img) {
    var c = this.lumCanvas;
    if (c.width !== 16 || c.height !== 16) { c.width = 16; c.height = 16; }
    var ctx = c.getContext('2d');
    ctx.clearRect(0, 0, 16, 16);
    ctx.drawImage(img, 0, 0, 16, 16);
    try {
      var d = ctx.getImageData(0, 0, 16, 16).data, sum = 0;
      for (var i = 0; i < d.length; i += 4) sum += d[i] * .2126 + d[i+1] * .7152 + d[i+2] * .0722;
      this.darkTone = sum / (d.length / 4) / 255 < .48;
    } catch (e) { this.darkTone = false; }
  };

  GlassRenderer.prototype._uploadPhoto = function (src, w, h) {
    var gl = this.gl;
    var limit = this.maxTex;
    var sw = w || src.width, sh = h || src.height;
    if (Math.max(sw, sh) > limit) {
      // 只有整幅上传（demo）会超上限；预览窗口本身已经和视口同量级
      var k = limit / Math.max(sw, sh), c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(sw * k)); c.height = Math.max(1, Math.round(sh * k));
      c.getContext('2d').drawImage(src, 0, 0, c.width, c.height);
      src = c;
    }
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
  };

  /**
   * 预览纹理：只上传「可见窗口 + 折射边距」，尺寸和可见像素同量级。
   * ratioRef 让模糊半径按「整幅宽度」而不是窗口宽度算 —— 否则水印调窄时磨砂会变细腻。
   */
  GlassRenderer.prototype._uploadWindow = function (img, cw, ch, tex, ratioRef) {
    var key = this._imgId + '|' + cw + 'x' + ch + '|' +
      Math.round(tex.x) + ',' + Math.round(tex.y) + ',' + Math.round(tex.w) + ',' + Math.round(tex.h);
    if (key === this._winKey) return;
    this._winKey = key;
    var c = this.winCanvas;
    if (c.width !== cw || c.height !== ch) { c.width = cw; c.height = ch; }
    var ctx = c.getContext('2d');
    ctx.clearRect(0, 0, cw, ch);
    ctx.drawImage(img, tex.x, tex.y, tex.w, tex.h, 0, 0, cw, ch);
    this._uploadPhoto(c, cw, ch);
    this._buildBlurFrom(c, ratioRef);
  };

  GlassRenderer.prototype._blurInto = function (img, canvas, tex, ratio) {
    var iw = img.naturalWidth || img.width, ih = img.naturalHeight || img.height;
    var s = Math.min(1, 420 / Math.max(iw, ih));
    var w = Math.max(8, Math.round(iw * s)), h = Math.max(8, Math.round(ih * s));
    // 尺寸没变就不要赋值：给 canvas.width 赋同样的值也会清空并重建后备缓冲
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
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

  GlassRenderer.prototype._buildBlurFrom = function (img, ratioRef) {
    if (!img) return;
    var rf = ratioRef && isFinite(ratioRef) && ratioRef > 0 ? ratioRef : 1;
    this._blurInto(img, this.blurCanvas, this.blurTex, rf / 63);
    this._blurInto(img, this.blurCanvas2, this.blurTex2, rf / 19);
  };

  GlassRenderer.prototype._buildBlur = function () {
    if (!this.image || !this.blurDirty) return;
    this._buildBlurFrom(this.image, 1);
    this.blurDirty = false;
  };

  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }
  function clampInt(v, a, b) { return Math.max(a, Math.min(b, v)); }

  /**
   * 真正绘制。clip 为 null 时铺满整块画布；否则只画 clip 区域（其余像素保持旧内容）。
   * 着色器用 gl_FragCoord + u_res 推 uv，所以挪动 viewport 就能只着色一条带 ——
   * 这正是「特写只渲染水印包围盒」的实现方式，不需要改 GLSL。
   * growOnly：预览专用。绘制缓冲只增不减（窗口尺寸随「水印宽度」连续变化，
   * 逐帧重分配 0.3MP 的缓冲是拖动掉帧的主因）；此时窗口放在帧缓冲左下角，
   * 保证 gl_FragCoord 与 u_res 的相对关系不变。
   */
  GlassRenderer.prototype._render = function (w, h, p, o, fullW, clip, growOnly) {
    var gl = this.gl;
    w = Math.max(2, Math.round(w)); h = Math.max(2, Math.round(h));
    var fbW = w, fbH = h;
    if (growOnly) {
      fbW = Math.max(this.canvas.width, w);
      fbH = Math.max(this.canvas.height, h);
    }
    if (this.canvas.width !== fbW || this.canvas.height !== fbH) {
      this.canvas.width = fbW; this.canvas.height = fbH;
    }

    var cx = 0, cy = 0, cw = w, ch = h;
    this.lastClip = null;
    this.lastFb = null;
    if (clip) {
      cx = clampInt(Math.round(clip.x), 0, w);
      cy = clampInt(Math.round(clip.y), 0, h);
      cw = clampInt(Math.round(clip.w), 0, w - cx);
      ch = clampInt(Math.round(clip.h), 0, h - cy);
      if (cw < 2 || ch < 2) return this.canvas;      // 水印完全在可视区外
    }
    this.lastClip = { x: cx, y: cy, w: cw, h: ch };
    // 画布源矩形要加的 y 偏移：growOnly 时窗口贴在帧缓冲底部
    this.lastFb = { dy: fbH - h };
    // 视口按「窗口自己的底部原点」换算（窗口永远贴在帧缓冲底边，故与画布高度无关）
    gl.viewport(cx, h - (cy + ch), cw, ch);
    gl.useProgram(this.program);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.photoTex); gl.uniform1i(this.uni.u_photo, 0);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, this.blurTex); gl.uniform1i(this.uni.u_blur, 1);
    gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, this.blurTex2); gl.uniform1i(this.uni.u_blur2, 2);

    var u = this.uni, depth = Math.max(2, p.bevel || 2);
    gl.uniform2f(u.u_res, w, h);
    gl.uniform4f(u.u_panel, p.x, p.y, p.w, p.h);
    // u_crop 恒为整幅：预览改由「窗口纹理 + viewport 裁剪」实现只渲条带（见 _uploadWindow），
    // 导出的条带纹理同样按 (0,0,1,1) 采样 —— 与 Android 侧上传条带纹理的做法保持一致。
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

  /** 整幅渲染（空状态示例用；画布本身就是整幅） */
  GlassRenderer.prototype.render = function (img, w, h, p, o) {
    if (this.image !== img) {
      this.image = img;
      this._imgId++;
      this._winKey = '';
      this._measureTone(img);
      this._uploadPhoto(img);
      this._buildBlurFrom(img, 1);
    }
    return this._render(w, h, p, o, w, null);
  };

  /**
   * 预览：只渲染水印条带。
   * tex = { x, y, w, h } 纹理窗口（原图像素，已含折射/阴影边距）；
   * cw/ch = 纹理窗口的输出尺寸；panel/clip 都是画布坐标（= 窗口坐标 + 纹理原点偏移）。
   * 返回画布上有效的矩形（画布坐标，null 表示水印在可视区外）。
   */
  GlassRenderer.prototype.renderWindow = function (img, cw, ch, panel, o, fullW, tex, ratioRef, clip) {
    this.setImage(img);
    this._uploadWindow(img, cw, ch, tex, ratioRef);
    this._render(cw, ch, panel, o, fullW, clip, true);
    var lc = this.lastClip;
    if (!lc) return null;
    // 源矩形按画布自上而下的坐标给；wx/wy 是它在窗口里的位置（调用方再减纹理原点偏移）
    return { sx: lc.x, sy: this.lastFb.dy + lc.y, w: lc.w, h: lc.h, wx: lc.x, wy: lc.y };
  };

  /** 导出条带：原图像素直接当纹理上传（和预览共用渲染代码，但不碰预览的纹理状态） */
  GlassRenderer.prototype.renderCropped = function (src, w, h, p, o, fullW) {
    this._uploadPhoto(src, w, h);
    this._buildBlurFrom(src, 1);
    this.blurDirty = false;
    return this._render(w, h, p, o, fullW, null);
  };

  global.LG = global.LG || {};
  global.LG.GlassRenderer = GlassRenderer;
})(window);
