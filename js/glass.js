/* ═══════════════════════════════════════════════════════════
   glass.js — WebGL 液态玻璃渲染器
   思路：以圆角矩形的有符号距离场(SDF)构造斜面高度场，
        求法线 → 模拟折射位移 / 色散 / 高光 / 边缘描边。
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var VERT = [
    'attribute vec2 a_pos;',
    'void main(){ gl_Position = vec4(a_pos, 0.0, 1.0); }'
  ].join('\n');

  var FRAG = [
    'precision highp float;',
    '',
    'uniform sampler2D u_photo;',
    'uniform sampler2D u_blur;',
    'uniform vec2  u_res;',
    'uniform vec4  u_panel;',   // x, y, w, h  (px, 左上角原点)
    'uniform float u_radius;',
    'uniform float u_bevel;',
    'uniform float u_edgeW;',
    'uniform float u_white;',
    'uniform float u_ior;',
    'uniform float u_frost;',
    'uniform float u_chroma;',
    'uniform float u_spec;',
    'uniform float u_rim;',
    'uniform float u_bright;',
    'uniform float u_tint;',
    'uniform float u_shadow;',
    'uniform float u_scale;',
    'uniform float u_warp;',
    'uniform vec4  u_crop;',
    '',
    'float sdRoundBox(vec2 p, vec2 b, float r){',
    '  r = min(r, min(b.x, b.y));',
    '  vec2 q = abs(p) - b + vec2(r);',
    '  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;',
    '}',
    '',
    '// 圆角边缘的斜面高度场：d=向内距离，zr=玻璃厚度，bw=液态宽度(折射带宽度)',
    '// 圆弧剖面：边缘处陡峭、到 bw 处切线水平，高度收敛到 zr',
    'float heightAt(float d, float zr, float bw){',
    '  if (d <= 0.0) return 0.0;',
    '  float t = clamp(d / max(bw, 0.5), 0.0, 1.0);',
    '  float k = 1.0 - t;',
    '  return zr * sqrt(max(1.0 - k * k, 0.0));',
    '}',
    '',
    'float hash(vec2 p){',
    '  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);',
    '}',
    '',
    'void main(){',
    '  vec2 p = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);',
    '  vec2 uv = p / u_res;',
    '  vec3 photo = texture2D(u_photo, uv).rgb;',
    '',
    '  vec2 half_ = u_panel.zw * 0.5;',
    '  vec2 c = u_panel.xy + half_;',
    '  vec2 q = p - c;',
    '  float sdf = sdRoundBox(q, half_, u_radius);',
    '  float mask = 1.0 - smoothstep(-1.0, 1.0, sdf);',
    '',
    '  // ── 投影 ──',
    '  if (mask < 0.999){',
    '    float ssd = sdRoundBox(q - vec2(0.0, 6.0 * u_scale), half_, u_radius);',
    '    float d0 = max(ssd, 0.0);',
    '    float spread = 12.0 * u_scale;',
    '    float soft = exp(-(d0 * d0) / (2.0 * spread * spread)) * 0.55;',
    '    float contact = exp(-d0 / (2.0 * u_scale)) * 0.40;',
    '    float sh = clamp((soft + contact) * u_shadow * (1.0 - smoothstep(-2.0, 0.0, ssd)), 0.0, 0.92);',
    '    photo = mix(photo, vec3(0.0), sh);',
    '  }',
    '',
    '  if (mask <= 0.002){ gl_FragColor = vec4(photo, 1.0); return; }',
    '',
    '  float d = max(-sdf, 0.0);',
    '  float zr = max(u_bevel, 0.5);',
    '  float bw = max(u_edgeW, 0.5);',
    '  float e = max(u_scale * 0.8, 0.8);',
    '',
    '  float hR = heightAt(max(-sdRoundBox(q + vec2(e, 0.0), half_, u_radius), 0.0), zr, bw);',
    '  float hL = heightAt(max(-sdRoundBox(q - vec2(e, 0.0), half_, u_radius), 0.0), zr, bw);',
    '  float hU = heightAt(max(-sdRoundBox(q + vec2(0.0, e), half_, u_radius), 0.0), zr, bw);',
    '  float hD = heightAt(max(-sdRoundBox(q - vec2(0.0, e), half_, u_radius), 0.0), zr, bw);',
    '',
    '  vec2 grad = vec2(hR - hL, hD - hU) / (2.0 * e);',
    '  vec3 N = normalize(vec3(-grad, 1.0));',
    '',
    '  float rimT = 1.0 - clamp(d / bw, 0.0, 1.0);',
    '  float lens = smoothstep(0.0, 1.0, rimT);',
    '  vec2 gradDir = grad / max(length(grad), 1e-5);',
    '  vec2 outward = -gradDir;',
    '',
    '  // ── 折射：位移量与玻璃厚度(斜面深度)成正比，符合薄透镜近似 ──',
    '  float eta = clamp(u_ior - 1.0, 0.0, 1.2);',
    '  vec2 refr = outward * lens * eta * zr * 1.05;',
    '  refr += -(q / max(half_, vec2(1.0))) * eta * zr * 0.34 * (1.0 - lens);',
    '',
    '  // ── 横向畸变：柱面凸起 + 轻微波动，让内部产生左右方向的错动 ──',
    '  vec2 nxy = clamp(q / max(half_, vec2(1.0)), -1.0, 1.0);',
    '  float depthT = clamp(d / max(bw, 1.0), 0.0, 1.0);',
    '  float bulge = (1.0 - nxy.x * nxy.x) * (1.0 - nxy.y * nxy.y);',
    '  float wobble = sin(nxy.x * 2.7 + nxy.y * 1.3) * 0.30',
    '               + sin(nxy.x * 5.3 - nxy.y * 0.8) * 0.12;',
    '  refr.x += (-nxy.x * bulge * 1.55 + wobble * 1.25) * eta * zr * 1.35 * depthT * u_warp;',
    '  // 上下边缘再叠一点柱面压缩，左右方向的错动更明显',
    '  refr.x += -nxy.x * (1.0 - abs(nxy.y)) * eta * zr * 0.55 * u_warp;',
    '',
    // 横向色散：以水平方向为主（左右边缘红/青分离），越靠近边角越强，
    // 内部也带一点横向偏移，看起来像隔着一条玻璃砖看东西
    '  vec2 caDir = vec2(outward.x * 2.10, outward.y * 0.62);',
    '  caDir += vec2(nxy.x * 0.65, nxy.y * 0.18) * (1.0 - lens);',
    '  float caGain = 0.30 + 0.60 * bulge;',
    '  vec2 ca = caDir * lens * u_chroma * eta * zr * caGain * 1.15;',
    '  // 把位移换算到整张照片的 uv 空间，再按「离照片边缘还剩多少」限幅。',
    '  // 不限幅的话，靠近画面底/边的折射会把采样推到画面外，被 CLAMP_TO_EDGE',
    '  // 拉成一条横向拖影（实况照片贴身水印时特别明显）。',
    '  vec2 photoUv = u_crop.xy + uv * u_crop.zw;',
    '  vec2 dPhoto = (refr / u_res) * u_crop.zw;',
    '  dPhoto.x = clamp(dPhoto.x, -photoUv.x * 0.9, (1.0 - photoUv.x) * 0.9);',
    '  dPhoto.y = clamp(dPhoto.y, -photoUv.y * 0.9, (1.0 - photoUv.y) * 0.9);',
    '  vec2 baseUV = uv + dPhoto / u_crop.zw;',
    '  vec2 caUV = ca / u_res;',
    '',
    '  vec3 sharp = vec3(',
    '    texture2D(u_photo, baseUV + caUV).r,',
    '    texture2D(u_photo, baseUV).g,',
    '    texture2D(u_photo, baseUV - caUV).b',
    '  );',
    '  vec3 soft = vec3(',
    '    texture2D(u_blur, baseUV + caUV).r,',
    '    texture2D(u_blur, baseUV).g,',
    '    texture2D(u_blur, baseUV - caUV).b',
    '  );',
    '',
    '  float frost = clamp(u_frost * (0.30 + 0.70 * (1.0 - lens)), 0.0, 1.0);',
    '  vec3 col = mix(sharp, soft, frost);',
    '',
    '  col *= 1.0 + u_bright;',
    '  col = mix(col, col * vec3(0.93, 0.965, 1.045), u_tint);',
    '',
    '  // ── 高光 ──',
    '  vec3 V = vec3(0.0, 0.0, 1.0);',
    '  vec3 L1 = normalize(vec3(-0.52, -0.72, 0.58));',
    '  vec3 L2 = normalize(vec3(0.40, 0.80, 0.46));',
    '  float s1 = pow(max(dot(N, normalize(L1 + V)), 0.0), 58.0);',
    '  float s2 = pow(max(dot(N, normalize(L2 + V)), 0.0), 40.0);',
    '  col += vec3(1.0) * (s1 + s2 * 0.38) * u_spec * 0.9;',
    '',
    '  // ── 边缘描边（上亮下暗）+ 底部内侧暗部 ──',
    '  float band = 1.0 - smoothstep(0.0, 2.6 * u_scale, d);',
    '  float topBias = clamp(-N.y, 0.0, 1.0);',
    '  col += vec3(1.0) * band * u_rim * (0.14 + 0.62 * topBias);',
    '  col *= 1.0 - band * clamp(N.y, 0.0, 1.0) * 0.42 * u_rim;',
    '',
    '  // 上沿内侧柔光，强化玻璃厚度观感',
    '  float glow = exp(-d / max(6.0 * u_scale, 1.0)) * topBias;',
    '  col += vec3(0.94, 0.97, 1.0) * glow * 0.07 * u_rim;',
    '',
    '  float innerShade = clamp(N.y, 0.0, 1.0) * (1.0 - clamp(d / bw, 0.0, 1.0));',
    '  col *= 1.0 - innerShade * 0.22 * u_rim;',
    '',
    '  // ── 菲涅尔柔光 ──',
    '  float fres = pow(1.0 - clamp(N.z, 0.0, 1.0), 3.0);',
    '  col += vec3(0.92, 0.96, 1.0) * fres * 0.09;',
    '',
    '  // ── 加白：在玻璃上叠一层可控透明度的白 ──',
    '  col = mix(col, vec3(1.0), clamp(u_white, 0.0, 1.0) * 0.85);',
    '',
    '  col += (hash(p) - 0.5) / 255.0;',
    '',
    '  gl_FragColor = vec4(mix(photo, col, mask), 1.0);',
    '}'
  ].join('\n');

  var UNIFORMS = ['u_photo', 'u_blur', 'u_res', 'u_panel', 'u_radius', 'u_bevel', 'u_edgeW', 'u_white',
    'u_ior', 'u_frost', 'u_chroma', 'u_spec', 'u_rim', 'u_bright',
    'u_tint', 'u_shadow', 'u_scale', 'u_warp', 'u_crop'];

  function GlassRenderer() {
    this.canvas = document.createElement('canvas');
    var opts = {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      premultipliedAlpha: false,
      preserveDrawingBuffer: true,
      powerPreference: 'high-performance'
    };
    var gl = this.canvas.getContext('webgl', opts) || this.canvas.getContext('experimental-webgl', opts);
    if (!gl) throw new Error('当前浏览器未启用 WebGL');
    this.gl = gl;
    this.maxTex = Math.min(gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096, 8192);
    this.image = null;
    this.blurDirty = true;

    this._compile();
    this._initQuad();
    this.photoTex = this._texture();
    this.blurTex = this._texture();

    this.blurCanvas = document.createElement('canvas');
    this.blurCtx = this.blurCanvas.getContext('2d');
  }

  GlassRenderer.prototype._shader = function (type, src) {
    var gl = this.gl;
    var sh = gl.createShader(type);
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
    var gl = this.gl;
    var vs = this._shader(gl.VERTEX_SHADER, VERT);
    var fs = this._shader(gl.FRAGMENT_SHADER, FRAG);
    var prog = gl.createProgram();
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('程序链接失败: ' + gl.getProgramInfoLog(prog));
    }
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    gl.useProgram(prog);
    this.program = prog;
    this.uni = {};
    for (var i = 0; i < UNIFORMS.length; i++) {
      this.uni[UNIFORMS[i]] = gl.getUniformLocation(prog, UNIFORMS[i]);
    }
    this.aPos = gl.getAttribLocation(prog, 'a_pos');
  };

  GlassRenderer.prototype._initQuad = function () {
    var gl = this.gl;
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -1, -1, 1, -1, -1, 1,
      -1, 1, 1, -1, 1, 1
    ]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(this.aPos);
    gl.vertexAttribPointer(this.aPos, 2, gl.FLOAT, false, 0, 0);
  };

  GlassRenderer.prototype._texture = function () {
    var gl = this.gl;
    var tex = gl.createTexture();
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

    // 超出 GPU 纹理上限时先缩放，避免 texImage2D 失败
    var iw = img.naturalWidth || img.width;
    var ih = img.naturalHeight || img.height;
    var src = img;
    var limit = this.maxTex;
    if (Math.max(iw, ih) > limit) {
      var k = limit / Math.max(iw, ih);
      var c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(iw * k));
      c.height = Math.max(1, Math.round(ih * k));
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      src = c;
    }
    this.texSource = src;

    var gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    this.blurDirty = true;
  };

  GlassRenderer.prototype._buildBlur = function () {
    if (!this.image || !this.blurDirty) return;
    this._buildBlurFrom(this.image);
    this.blurDirty = false;
  };

  GlassRenderer.prototype._buildBlurFrom = function (img) {
    if (!img) return;
    var iw = img.naturalWidth || img.width;
    var ih = img.naturalHeight || img.height;
    var maxW = 420;
    var s = Math.min(1, maxW / Math.max(iw, ih));
    var w = Math.max(8, Math.round(iw * s));
    var h = Math.max(8, Math.round(ih * s));

    this.blurCanvas.width = w;
    this.blurCanvas.height = h;
    var ctx = this.blurCtx;
    ctx.clearRect(0, 0, w, h);
    var b = Math.max(3, w * 0.035);
    var hasFilter = typeof ctx.filter === 'string';
    if (hasFilter) ctx.filter = 'blur(' + b.toFixed(2) + 'px)';
    // 略微外扩绘制，避免模糊到画布外产生透明边缘
    ctx.drawImage(img, 0, 0, w, h);
    if (hasFilter) ctx.filter = 'none';

    var gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.blurTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, this.blurCanvas);
  };

  /**
   * 渲染一帧
   * @param {HTMLImageElement} img
   * @param {number} w 目标宽（px）
   * @param {number} h 目标高（px）
   * @param {object} p 面板参数 {x,y,w,h,r,bevel}
   * @param {object} o 效果参数
   */
  GlassRenderer.prototype.render = function (img, w, h, p, o) {
    var gl = this.gl;
    w = Math.max(2, Math.round(w));
    h = Math.max(2, Math.round(h));

    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.setImage(img);
    this._buildBlur();

    gl.viewport(0, 0, w, h);
    gl.useProgram(this.program);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.uniform1i(this.uni.u_photo, 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, this.blurTex);
    gl.uniform1i(this.uni.u_blur, 1);

    var u = this.uni;
    gl.uniform2f(u.u_res, w, h);
    gl.uniform4f(u.u_panel, p.x, p.y, p.w, p.h);
    gl.uniform1f(u.u_radius, p.r);
    gl.uniform1f(u.u_bevel, p.bevel);
    gl.uniform1f(u.u_edgeW, p.edgeW);
    gl.uniform1f(u.u_ior, o.ior);
    gl.uniform1f(u.u_frost, o.frost);
    gl.uniform1f(u.u_chroma, o.chroma);
    gl.uniform1f(u.u_spec, o.spec);
    gl.uniform1f(u.u_rim, o.rim);
    gl.uniform1f(u.u_bright, o.bright);
    gl.uniform1f(u.u_tint, o.tint);
    gl.uniform1f(u.u_shadow, o.shadow);
    gl.uniform1f(u.u_white, o.white || 0);
    gl.uniform1f(u.u_scale, w / 1000);

    gl.drawArrays(gl.TRIANGLES, 0, 6);
    return this.canvas;
  };

  /**
   * 渲染一块裁剪区域（导出超大图时按条带渲染，避免纹理尺寸上限）
   * @param {HTMLCanvasElement} src 已裁剪好的原始像素（尺寸 = 输出条带尺寸）
   */
  GlassRenderer.prototype.renderCropped = function (src, w, h, p, o, fullW) {
    var gl = this.gl;
    w = Math.max(2, Math.round(w));
    h = Math.max(2, Math.round(h));

    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }

    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    this.image = null;
    this._buildBlurFrom(src);

    gl.viewport(0, 0, w, h);
    gl.useProgram(this.program);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.photoTex);
    gl.uniform1i(this.uni.u_photo, 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, this.blurTex);
    gl.uniform1i(this.uni.u_blur, 1);

    var u = this.uni;
    gl.uniform2f(u.u_res, w, h);
    gl.uniform4f(u.u_panel, p.x, p.y, p.w, p.h);
    gl.uniform1f(u.u_radius, p.r);
    gl.uniform1f(u.u_bevel, p.bevel);
    gl.uniform1f(u.u_edgeW, p.edgeW);
    gl.uniform1f(u.u_ior, o.ior);
    gl.uniform1f(u.u_frost, o.frost);
    gl.uniform1f(u.u_chroma, o.chroma);
    gl.uniform1f(u.u_spec, o.spec);
    gl.uniform1f(u.u_rim, o.rim);
    gl.uniform1f(u.u_bright, o.bright);
    gl.uniform1f(u.u_tint, o.tint);
    gl.uniform1f(u.u_shadow, o.shadow);
    gl.uniform1f(u.u_white, o.white || 0);
    gl.uniform1f(u.u_scale, (fullW || w) / 1000);

    gl.drawArrays(gl.TRIANGLES, 0, 6);
    return this.canvas;
  };

  global.LG = global.LG || {};
  global.LG.GlassRenderer = GlassRenderer;
})(window);
