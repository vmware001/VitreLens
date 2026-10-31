/* ═══════════════════════════════════════════════════════════
   app.js — 状态、交互、渲染与导出
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var Exif = window.LG.Exif;
  var Logos = window.LG.Logos;
  var Watermark = window.LG.Watermark;

  /* ── 默认值 ── */

  var DEFAULT_TEXT = {
    name: '', brand: 'none', subtitle: '', params: '', meta: ''
  };

  var SAMPLE_TEXT = {
    name: 'OPPO Find X10 Pro Max',
    brand: 'hasselblad',
    subtitle: '哈苏影像 · 光影追寻',
    params: '23mm ƒ/1.8 1/287s ISO50',
    meta: '2025.07.28 14:04:47'
  };

  var DEFAULTS = {
    showSubtitle: true, showBrand: true, showParams: true, showMeta: true,
    align: 'center', widthPct: 92, nameSize: 24, metaSize: 12.5, bottomGap: 3.8,
    font: 'sans', italic: false, nameMaterial: 'liquid', viewMode: 'detail',
    logoRight: true, logoPosPct: 100, textColor: '',
    ior: 1.46, radius: 100, bevel: 24, edgeWidth: 36, frost: 20, chroma: 6,
    spec: 28, rim: 28, trans: 98, shadow: 18, white: 0,
    warp: 0, edge: 140, segments: 128, frostTone: 0,
    format: 'jpeg', scale: 1, quality: 100
  };

  var GLASS_KEYS = ['ior', 'radius', 'bevel', 'edgeWidth', 'frost', 'chroma', 'spec', 'rim',
    'trans', 'shadow', 'white', 'warp', 'edge', 'segments', 'frostTone'];

  var GLASS_PRESETS = {
    regular: { ior: 1.46, radius: 100, bevel: 24, edgeWidth: 36, frost: 20, chroma: 6,
      spec: 28, rim: 28, trans: 98, shadow: 18, white: 0, warp: 0, edge: 140, segments: 128, frostTone: 0 },
    clear: { ior: 1.46, radius: 100, bevel: 28, edgeWidth: 44, frost: 4, chroma: 8,
      spec: 32, rim: 32, trans: 100, shadow: 14, white: 0, warp: 0, edge: 160, segments: 128, frostTone: 0 },
    frost: { ior: 1.40, radius: 100, bevel: 30, edgeWidth: 44, frost: 76, chroma: 6,
      spec: 24, rim: 30, trans: 86, shadow: 20, white: 30, warp: 0, edge: 110, segments: 128, frostTone: 0 },
    thick: { ior: 1.72, radius: 100, bevel: 72, edgeWidth: 88, frost: 8, chroma: 46,
      spec: 82, rim: 100, trans: 100, shadow: 58, white: 0, warp: 130, edge: 60, segments: 128, frostTone: 0 }
  };

  var PARAM_PRESETS = [
    { label: '23mm ƒ/1.8 1/287s ISO50', text: '23mm ƒ/1.8 1/287s ISO50' },
    { label: '35mm ƒ/1.4 1/125s ISO200', text: '35mm ƒ/1.4 1/125s ISO200' },
    { label: '85mm ƒ/1.2 1/1000s ISO64', text: '85mm ƒ/1.2 1/1000s ISO64' }
  ];

  var SUB_PRESETS = {
    leica: ['徕卡光学 · 经典传承', 'LEICA · 光影叙事'],
    hasselblad: ['哈苏影像 · 光影追寻', 'HNCS 自然色彩 · 栩栩如生'],
    zeiss: ['蔡司影像 · 超越想象', 'ZEISS · 光学之美'],
    ricoh: ['理光 GR · 街头速写', 'GR · 随身街拍'],
    sony: ['索尼影像 · 真实质感', 'Alpha · 影像之力'],
    canon: ['佳能影像 · 感动常在'],
    nikon: ['尼康影像 · 光影之美'],
    fujifilm: ['富士胶片 · 色彩哲学'],
    apple: ['Apple · 计算摄影'],
    none: ['光影记录 · 此刻']
  };

  /* ── DOM ── */

  function $(s) { return document.querySelector(s); }

  var stage = $('#stage');
  var stageScroll = $('#stageScroll');
  var shell = $('#canvasShell');
  var view = $('#view');
  var vctx = view.getContext('2d');
  var fileInput = $('#fileInput');
  var dropzone = $('#dropzone');
  var hud = $('#stageHud');
  var hudSize = $('#hudSize');
  var hudExif = $('#hudExif');
  var filmstrip = $('#filmstrip');
  var dragOverlay = $('#dragOverlay');
  var exifBadge = $('#exifBadge');
  var toastStack = $('#toasts');

  /* ── 状态 ── */

  var images = [];
  var current = 0;
  var state = Object.assign({}, DEFAULT_TEXT, DEFAULTS);
  var renderer = null;
  var rafId = 0;
  var work = document.createElement('canvas');
  var wctx = work.getContext('2d');
  var exporting = false;

  try {
    renderer = new window.LG.GlassRenderer();
  } catch (err) {
    console.error(err);
    setTimeout(function () { toast('浏览器未启用 WebGL，无法渲染玻璃效果', 'warn'); }, 600);
  }

  /* ═══════════ 工具 ═══════════ */

  function toast(msg, kind) {
    var el = document.createElement('div');
    el.className = 'toast' + (kind === 'warn' ? ' is-warn' : '');
    el.textContent = msg;
    toastStack.appendChild(el);
    setTimeout(function () {
      el.classList.add('is-out');
      setTimeout(function () { el.remove(); }, 260);
    }, 2400);
  }

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

  /* ── HEIC 支持（按需加载 libheif，约 2MB） ── */

  var heicLib = null;
  var heicLoading = null;

  function isHeic(file) {
    return /hei[cf]/i.test(file.type || '') || /\.(heic|heif|hif)$/i.test(file.name || '');
  }

  function loadHeicLib() {
    if (heicLib) return Promise.resolve(heicLib);
    if (heicLoading) return heicLoading;
    heicLoading = new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = 'js/vendor/libheif-bundle.js';
      script.onload = function () {
        if (!window.libheif) return reject(new Error('HEIC 解码库加载失败'));
        Promise.resolve(
          typeof window.libheif.HeifDecoder === 'function' ? window.libheif : window.libheif()
        ).then(function (mod) {
          heicLib = mod;
          resolve(mod);
        }, reject);
      };
      script.onerror = function () { reject(new Error('HEIC 解码库加载失败')); };
      document.head.appendChild(script);
    });
    return heicLoading;
  }

  function decodeHeic(file) {
    return loadHeicLib().then(function (mod) {
      return file.arrayBuffer().then(function (buf) {
        var images = new mod.HeifDecoder().decode(new Uint8Array(buf));
        if (!images || !images.length) throw new Error('无法解析该 HEIC 文件');

        var best = images[0], bestArea = 0;
        for (var i = 0; i < images.length; i++) {
          var a = images[i].get_width() * images[i].get_height();
          if (a > bestArea) { bestArea = a; best = images[i]; }
        }
        var w = best.get_width(), h = best.get_height();
        var canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        var ctx = canvas.getContext('2d');
        var data = ctx.createImageData(w, h);
        return new Promise(function (res, rej) {
          best.display(data, function (result) {
            if (!result) return rej(new Error('HEIC 解码失败'));
            ctx.putImageData(data, 0, 0);
            res(canvas);
          });
        });
      });
    });
  }

  function makeThumb(source, w, h) {
    var max = 220;
    var k = Math.min(1, max / Math.max(w, h));
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * k));
    c.height = Math.max(1, Math.round(h * k));
    c.getContext('2d').drawImage(source, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', 0.82);
  }

  async function loadImage(file) {
    if (isHeic(file)) {
      var canvas = await decodeHeic(file);
      return { img: canvas, thumb: makeThumb(canvas, canvas.width, canvas.height) };
    }
    return new Promise(function (resolve) {
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.decoding = 'async';
      img.onload = function () { resolve({ img: img, thumb: url }); };
      img.onerror = function () { URL.revokeObjectURL(url); resolve(null); };
      img.src = url;
    });
  }

  /* ═══════════ 文本 / EXIF ═══════════ */

  function textFromExif(exif) {
    if (!exif) return null;
    var brand = Exif.detectBrand(exif.make, exif.model) || 'none';
    var subs = SUB_PRESETS[brand] || SUB_PRESETS.none;
    var name = Exif.deviceName(exif.make, exif.model) || '';
    var params = Exif.formatParams(exif) || '';
    var meta = Exif.formatDate(exif.date);
    // 地点：只有在解析出有效坐标时才追加
    if (exif.gps && exif.gps.text) meta = (meta ? meta + '  ' : '') + exif.gps.text;

    if (!name && !params && !meta) return null;   // 什么都没读到就不要乱填
    return {
      name: name,
      brand: name ? brand : 'none',
      subtitle: name ? subs[0] : '',
      params: params,
      meta: meta
    };
  }

  function applyExifTo(img, silent) {
    var t = textFromExif(img.exif);
    if (!t) {
      if (!silent) toast('这张照片没有可用的拍摄信息，请手动填写', 'warn');
      img.text = Object.assign({}, DEFAULT_TEXT);
      Object.assign(state, img.text);
      syncTextInputs(); syncBrand(); buildSubChips(); syncExifBadge(); requestRender();
      return false;
    }
    img.text = t;
    Object.assign(state, t);
    syncTextInputs();
    syncBrand();
    buildSubChips();
    syncExifBadge();
    requestRender();
    return true;
  }

  function syncExifBadge() {
    var img = images[current];
    if (!img) { exifBadge.textContent = 'EXIF 待读取'; exifBadge.className = 'sec-hint'; return; }
    if (img.exif && (img.exif.make || img.exif.model)) {
      exifBadge.textContent = '已读取 EXIF';
      exifBadge.className = 'sec-hint is-ok';
    } else {
      exifBadge.textContent = '无 EXIF 数据';
      exifBadge.className = 'sec-hint is-warn';
    }
  }

  /* ═══════════ 渲染 ═══════════ */

  function currentImage() { return images[current] || null; }

  function fitSize(img) {
    var rect = stage.getBoundingClientRect();
    var narrow = window.innerWidth < 980;
    var padX = narrow ? 20 : 32;
    var padY = narrow ? 56 : 68;
    if (images.length > 1) padY += narrow ? 40 : 46;   // 给底部胶片条留出空间
    var availW = Math.max(200, rect.width - padX);
    var availH = Math.max(160, rect.height - padY);
    var ar = img.w / img.h;
    var w = availW, h = w / ar;
    if (h > availH) { h = availH; w = h * ar; }
    return { w: Math.max(120, Math.floor(w)), h: Math.max(120, Math.floor(h)) };
  }

  function glassOpts(s) {
    return {
      ior: clamp(s.ior, 1, 2),
      thickness: s.edgeWidth / 100,
      rough: s.frost / 100,
      disp: s.chroma / 100,
      gloss: s.spec / 100,
      refl: s.rim / 100,
      trans: s.trans / 100,
      shadow: s.shadow / 100,
      white: (s.white || 0) / 100,
      warp: (s.warp || 0) / 100,
      edge: (s.edge === undefined ? 140 : s.edge) / 100,
      segments: s.segments || 128,
      frostTone: s.frostTone || 0
    };
  }

  function requestRender() {
    if (rafId) return;
    rafId = requestAnimationFrame(function () { rafId = 0; render(); });
  }

  /** 特写视图的裁切区域（画布像素） */
  function detailCrop(lay, W, H) {
    var p = lay.panel;
    var mx = p.w * 0.02;
    var my = p.h * 1.15;
    var cx = Math.max(0, Math.min(W - 1, p.x - mx));
    var cy = Math.max(0, Math.min(H - 1, p.y - my));
    var cw = Math.min(W - cx, p.w + mx * 2);
    var ch = Math.min(H - cy, p.h + my * 2);
    return { x: cx, y: cy, w: cw, h: ch };
  }

  var DETAIL_ZOOM = 2;

  function render() {
    var img = currentImage();
    if (!img || !renderer) return;

    var fit = fitSize(img);
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var detail = state.viewMode === 'detail';
    var W = Math.round(fit.w * dpr);
    var H = Math.round(fit.h * dpr);
    var outCssW = fit.w;
    var dprEff = dpr;

    // 特写：铺满舞台宽度，但像素密度不超过原图能提供的细节
    if (detail) {
      var lay0 = Watermark.layout(W, H, state);
      if (lay0.hasContent) {
        var c0 = detailCrop(lay0, W, H);
        var ratio = c0.w / W;
        var avail = Math.max(240, stageScroll.clientWidth - 28);
        var srcW = img.w * ratio;
        dprEff = Math.min(dpr, Math.max(0.6, srcW / avail));
        outCssW = avail;
        var viewW = Math.round(avail * dprEff);
        W = Math.round(viewW / ratio);
        H = Math.max(2, Math.round(W * (img.h / img.w)));
        fit.w = W / dpr;
        fit.h = H / dpr;
      }
    }

    var lay = Watermark.layout(W, H, state);

    if (!lay.hasContent) {
      if (view.width !== W) view.width = W;
      if (view.height !== H) view.height = H;
      vctx.clearRect(0, 0, W, H);
      vctx.drawImage(img.el, 0, 0, W, H);
      $('#stageHint').hidden = false;
      shell.style.width = fit.w + 'px';
      updateExportInfo();
      return;
    }
    $('#stageHint').hidden = true;

    // 全图合成到工作画布
    if (work.width !== W) work.width = W;
    if (work.height !== H) work.height = H;

    var glCanvas;
    try {
      glCanvas = renderer.render(img.el, W, H, lay.panel, glassOpts(state));
    } catch (err) {
      console.error(err);
      toast('渲染失败：' + err.message, 'warn');
      return;
    }

    wctx.clearRect(0, 0, W, H);
    wctx.drawImage(glCanvas, 0, 0);
    Watermark.draw(wctx, lay, state);

    // 输出：整图 or 水印特写
    var outW = W, outH = H, src = null, cssW = fit.w;

    if (detail) {
      src = detailCrop(lay, W, H);
      cssW = outCssW;
      outW = Math.round(cssW * dprEff);
      outH = Math.max(80, Math.round(outW * src.h / src.w));
    }

    if (view.width !== outW) view.width = outW;
    if (view.height !== outH) view.height = outH;
    vctx.clearRect(0, 0, outW, outH);
    if (src) {
      vctx.drawImage(work, src.x, src.y, src.w, src.h, 0, 0, outW, outH);
    } else {
      vctx.drawImage(work, 0, 0);
    }

    shell.style.width = cssW + 'px';
    updateViewportState();
    updateExportInfo();
  }

  /** 更新横向滚动态与光标 */
  function updateViewportState() {
    var pannable = stageScroll.scrollWidth > stageScroll.clientWidth + 2;
    stageScroll.classList.toggle('is-pannable', pannable);
  }

  function updateExportInfo() {
    var img = currentImage();
    var el = $('#exportInfo');
    if (!el) return;
    if (!img) { el.textContent = '导入照片后显示导出尺寸'; return; }
    var w = Math.round(img.w * state.scale);
    var h = Math.round(img.h * state.scale);
    var cap = MAX_EXPORT;
    var capped = Math.max(w, h) > cap;
    if (capped) { var k = cap / Math.max(w, h); w = Math.round(w * k); h = Math.round(h * k); }
    el.textContent = '导出尺寸 ' + w + ' × ' + h + (capped ? '（已限制到上限）' : '');
  }

  /* ═══════════ 空状态效果示例 ═══════════ */

  function seeded(seed) {
    var s = seed;
    return function () {
      s = (s * 1664525 + 1013904223) % 4294967296;
      return s / 4294967296;
    };
  }

  function paintScene(ctx, W, H) {
    var rnd = seeded(20260728);
    var horizon = H * 0.63;

    // 天空
    var sky = ctx.createLinearGradient(0, 0, 0, horizon);
    sky.addColorStop(0.00, '#091320');
    sky.addColorStop(0.18, '#16243a');
    sky.addColorStop(0.36, '#2f3b56');
    sky.addColorStop(0.52, '#5b4f66');
    sky.addColorStop(0.66, '#94656a');
    sky.addColorStop(0.80, '#c9855f');
    sky.addColorStop(0.92, '#eeb175');
    sky.addColorStop(1.00, '#f8d6a2');
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, W, horizon + 1);

    // 夕阳
    var sx = W * 0.665, sy = horizon - H * 0.012;
    var glow = ctx.createRadialGradient(sx, sy, 0, sx, sy, H * 0.62);
    glow.addColorStop(0, 'rgba(255,247,224,1)');
    glow.addColorStop(0.045, 'rgba(255,231,178,0.92)');
    glow.addColorStop(0.16, 'rgba(255,198,132,0.38)');
    glow.addColorStop(0.45, 'rgba(255,172,104,0.10)');
    glow.addColorStop(1, 'rgba(255,160,96,0)');
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, W, H);

    // 云带
    ctx.save();
    if (typeof ctx.filter === 'string') ctx.filter = 'blur(' + (H * 0.02).toFixed(1) + 'px)';
    for (var i = 0; i < 11; i++) {
      var cy = H * (0.10 + i * 0.044);
      var rx = W * (0.14 + rnd() * 0.30);
      var ry = H * (0.004 + rnd() * 0.012);
      var dark = 0.20 + rnd() * 0.26;
      ctx.fillStyle = 'rgba(30,26,38,' + dark.toFixed(2) + ')';
      ctx.beginPath();
      ctx.ellipse(W * (0.05 + rnd() * 0.9), cy, rx, ry, 0, 0, Math.PI * 2);
      ctx.fill();
    }
    // 地平线附近的暖色云
    for (var j = 0; j < 5; j++) {
      ctx.fillStyle = 'rgba(255,214,168,' + (0.10 + rnd() * 0.12).toFixed(2) + ')';
      ctx.beginPath();
      ctx.ellipse(W * (0.15 + rnd() * 0.7), horizon - H * (0.03 + rnd() * 0.14),
        W * (0.10 + rnd() * 0.18), H * (0.004 + rnd() * 0.008), 0, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();

    // 水面
    var gnd = ctx.createLinearGradient(0, horizon, 0, H);
    gnd.addColorStop(0.00, '#b98a63');
    gnd.addColorStop(0.05, '#6d4c3e');
    gnd.addColorStop(0.22, '#332624');
    gnd.addColorStop(0.55, '#161114');
    gnd.addColorStop(1.00, '#08070a');
    ctx.fillStyle = gnd;
    ctx.fillRect(0, horizon, W, H - horizon);

    // 水面反光条纹
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (var k = 0; k < 46; k++) {
      var ry2 = horizon + (H - horizon) * Math.pow(rnd(), 1.5) * 0.95;
      var halfW = W * (0.004 + rnd() * 0.055) * (1 - (ry2 - horizon) / (H - horizon) * 0.55);
      var off = (rnd() - 0.5) * W * 0.12 * ((ry2 - horizon) / (H - horizon));
      ctx.fillStyle = 'rgba(255,206,150,' + (0.05 + rnd() * 0.16).toFixed(3) + ')';
      ctx.fillRect(sx + off - halfW, ry2, halfW * 2, Math.max(1, H * 0.0018));
    }
    ctx.restore();

    // 暗角
    var vig = ctx.createRadialGradient(W * 0.5, H * 0.45, H * 0.15, W * 0.5, H * 0.45, W * 0.72);
    vig.addColorStop(0, 'rgba(0,0,0,0)');
    vig.addColorStop(1, 'rgba(0,0,0,0.42)');
    ctx.fillStyle = vig;
    ctx.fillRect(0, 0, W, H);

    // 颗粒
    var noise = document.createElement('canvas');
    noise.width = 96;
    noise.height = 96;
    var nctx = noise.getContext('2d');
    var img = nctx.createImageData(96, 96);
    for (var n = 0; n < img.data.length; n += 4) {
      var v = 120 + Math.floor(rnd() * 120);
      img.data[n] = v; img.data[n + 1] = v; img.data[n + 2] = v; img.data[n + 3] = 255;
    }
    nctx.putImageData(img, 0, 0);
    ctx.save();
    ctx.globalAlpha = 0.05;
    ctx.globalCompositeOperation = 'overlay';
    var pat = ctx.createPattern(noise, 'repeat');
    ctx.fillStyle = pat;
    ctx.fillRect(0, 0, W, H);
    ctx.restore();
  }

  function renderDemo() {
    var canvas = $('#demoCanvas');
    var wrap = $('#dzDemo');
    if (!canvas || !wrap) return;
    if (!renderer) { wrap.classList.add('is-off'); return; }

    var W = 1120, H = 430;
    var bg = document.createElement('canvas');
    bg.width = W;
    bg.height = H;
    paintScene(bg.getContext('2d'), W, H);

    var demo = Object.assign({}, state, {
      name: 'OPPO Find X10 Pro Max',
      brand: 'hasselblad',
      subtitle: '哈苏影像 · 光影追寻',
      params: '23mm ƒ/1.8 1/287s ISO50',
      meta: '2025.07.28 14:04:47',
      showSubtitle: true, showBrand: true, showParams: true, showMeta: true,
      align: 'left', widthPct: 92, nameSize: 28, metaSize: 10,
      font: 'condensed', italic: true
    });

    try {
      var lay = Watermark.layout(W, H, demo);
      var glCanvas = renderer.render(bg, W, H, lay.panel, glassOpts(demo));
      canvas.width = W;
      canvas.height = H;
      var ctx = canvas.getContext('2d');
      ctx.drawImage(glCanvas, 0, 0);
      Watermark.draw(ctx, lay, demo);
    } catch (err) {
      console.error(err);
      wrap.classList.add('is-off');
    }
  }

  /* ═══════════ 图片管理 ═══════════ */

  async function addFiles(list) {
    var files = Array.prototype.slice.call(list || []).filter(function (f) {
      if (!f) return false;
      return /^image\//.test(f.type || '') || isHeic(f) ||
             /\.(jpe?g|png|webp|avif|gif|bmp)$/i.test(f.name || '');
    });
    if (!files.length) { toast('没有检测到图片文件', 'warn'); return; }

    var first = images.length === 0;
    var added = 0;

    for (var i = 0; i < files.length; i++) {
      var file = files[i];
      var loaded = null;
      try {
        loaded = await loadImage(file);
      } catch (err) {
        console.error(err);
        toast('无法读取：' + file.name + (isHeic(file) ? '（HEIC 解码失败）' : ''), 'warn');
        continue;
      }
      if (!loaded) { toast('无法读取：' + file.name, 'warn'); continue; }

      var exif = await Exif.readFile(file);
      var text = textFromExif(exif);
      var el = loaded.img;
      images.push({
        id: 'img_' + Date.now() + '_' + i,
        file: file,
        name: file.name,
        el: el,
        thumb: loaded.thumb,
        w: el.naturalWidth || el.width,
        h: el.naturalHeight || el.height,
        exif: exif,
        text: text || Object.assign({}, DEFAULT_TEXT)
      });
      added++;
    }

    if (!added) return;

    if (first) {
      select(0, true);
    } else {
      buildFilmstrip();
      updateBatchUI();
      requestRender();
    }

    var last = images[images.length - 1];
    var gotInfo = !!textFromExif(last.exif);
    toast('已导入 ' + added + ' 张照片 · ' + (gotInfo ? '已读取拍摄信息' : '未读到拍摄信息，请手动填写'));
  }

  function updateBatchUI() {
    var btn = $('#btnExportAll');
    btn.hidden = images.length < 2;
    btn.textContent = '导出全部（' + images.length + ' 张）';
  }

  function select(index, firstLoad) {
    current = clamp(index, 0, images.length - 1);
    var img = currentImage();
    if (!img) return;

    Object.assign(state, img.text);

    dropzone.hidden = true;
    shell.hidden = false;
    hud.hidden = false;
    $('#viewToggle').hidden = false;
    filmstrip.hidden = images.length < 2;
    $('#btnExport').disabled = false;
    $('#btnExport2').disabled = false;
    $('#btnRemove').disabled = false;
    updateBatchUI();

    hudSize.textContent = img.w + ' × ' + img.h;
    var e = img.exif;
    var hdr = !!(e && e.hdr);
    var motion = !!(e && e.motion);
    if (hdr) {
      var keptHdr = !!(e.motion && e.motion.preserves);
      $('#hdrNote').textContent = keptHdr
        ? 'HDR 增益图（ProXDR / Ultra HDR）已随动态照片容器一并保留。'
        : '该照片含 HDR 增益图（ProXDR / Ultra HDR），浏览器重新编码无法保留，导出为 SDR。';
    }
    $('#hudMotion').hidden = !motion;
    $('#hudMotionSep').hidden = !motion;
    if (motion) {
      var mo = e.motion;
      $('#hudMotion').textContent = mo.preserves ? '动态照片' : '动态照片（不可保留）';
      $('#hudMotion').title = mo.preserves
        ? '已内嵌动态视频，JPEG 导出可保留'
        : '该动态照片格式不受支持，导出后将丢失视频';
      $('#motionNote').hidden = false;
      $('#motionNote').textContent = mo.preserves
        ? '动态照片：用 JPEG 导出会保留动态视频（PNG 不含视频）。'
        : '动态照片：该机型封装格式暂不支持保留视频，导出为静态图。';
    } else {
      $('#motionNote').hidden = true;
    }
    $('#hudHdr').hidden = !hdr;
    $('#hudHdrSep').hidden = !hdr;
    $('#hdrNote').hidden = !hdr;
    if (hdr) {
      $('#hudHdr').title = '检测到 HDR 增益图（ProXDR / Ultra HDR），导出为 SDR';
    }
    var hudFocal = e ? Exif.formatFocal(Exif.pickFocal(e)) : '';
    hudExif.textContent = e && (e.make || e.model)
      ? (Exif.deviceName(e.make, e.model) || e.model || e.make) + (hudFocal ? ' · ' + hudFocal : '')
      : '无 EXIF';

    syncTextInputs();
    syncBrand();
    buildSubChips();
    syncExifBadge();
    buildFilmstrip();
    updateExportInfo();
    stageScroll.scrollLeft = 0;

    if (firstLoad) {
      shell.style.animation = 'none';
      void shell.offsetWidth;
      shell.style.animation = '';
    }
    requestRender();
  }

  function showEmptyState() {
    dropzone.hidden = false;
    shell.hidden = true;
    hud.hidden = true;
    filmstrip.hidden = true;
    $('#viewToggle').hidden = true;
    $('#stageHint').hidden = true;
    $('#btnExport').disabled = true;
    $('#btnExport2').disabled = true;
    $('#btnRemove').disabled = true;
    updateBatchUI();
    buildFilmstrip();
  }

  function removeImage(index) {
    if (index < 0 || index >= images.length) return;
    var removed = images[index];
    if (removed && removed.thumb && removed.thumb.indexOf('blob:') === 0) {
      try { URL.revokeObjectURL(removed.thumb); } catch (err) { /* ignore */ }
    }
    images.splice(index, 1);
    haptic(12);

    if (!images.length) {
      current = 0;
      showEmptyState();
      toast('已移除照片');
      return;
    }
    if (current > index) current -= 1;
    current = Math.max(0, Math.min(current, images.length - 1));
    select(current);
    toast('已移除照片');
  }

  function buildFilmstrip() {
    filmstrip.innerHTML = '';
    if (images.length < 2) { filmstrip.hidden = true; return; }
    filmstrip.hidden = false;

    images.forEach(function (im, i) {
      var b = document.createElement('button');
      b.className = 'thumb' + (i === current ? ' is-on' : '');
      b.title = im.name;
      var t = document.createElement('img');
      t.src = im.thumb;
      t.alt = '';
      b.appendChild(t);
      var del = document.createElement('span');
      del.className = 'thumb-del';
      del.innerHTML = '&times;';
      del.title = '移除这张';
      del.addEventListener('click', function (ev) {
        ev.stopPropagation();
        removeImage(i);
      });
      b.appendChild(del);

      b.addEventListener('click', function () { if (i !== current) select(i); });
      filmstrip.appendChild(b);
    });

    var add = document.createElement('button');
    add.className = 'thumb thumb-add';
    add.title = '继续添加';
    add.innerHTML = '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 5v14"/><path d="M5 12h14"/></svg>';
    add.addEventListener('click', function () { fileInput.click(); });
    filmstrip.appendChild(add);
  }

  /* ═══════════ 表单同步 ═══════════ */

  function syncTextInputs() {
    $('#fName').value = state.name || '';
    $('#fSub').value = state.subtitle || '';
    $('#fParams').value = state.params || '';
    $('#fMeta').value = state.meta || '';
  }

  function persistText() {
    var img = currentImage();
    if (!img) return;
    img.text = {
      name: state.name, brand: state.brand, subtitle: state.subtitle,
      params: state.params, meta: state.meta
    };
  }

  function syncBrand() {
    var btns = document.querySelectorAll('.brand-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].classList.toggle('is-on', btns[i].dataset.brand === state.brand);
    }
  }

  function buildSubChips() {
    var wrap = $('#subPresets');
    wrap.innerHTML = '';
    var list = SUB_PRESETS[state.brand] || SUB_PRESETS.none;
    list.forEach(function (txt) {
      var b = document.createElement('button');
      b.className = 'chip';
      b.textContent = txt;
      b.addEventListener('click', function () {
        state.subtitle = txt;
        $('#fSub').value = txt;
        persistText();
        requestRender();
      });
      wrap.appendChild(b);
    });

    var img = currentImage();
    if (img && img.exif && img.exif.gps) {
      var g = document.createElement('button');
      g.className = 'chip';
      g.textContent = '+ 坐标';
      g.addEventListener('click', function () {
        state.meta = (state.meta ? state.meta + '  ' : '') + img.exif.gps.text;
        $('#fMeta').value = state.meta;
        persistText();
        requestRender();
      });
      wrap.appendChild(g);
    }
  }

  function randomParams() {
    var focal = [16, 23, 24, 28, 35, 50, 70, 85, 135, 200][Math.floor(Math.random() * 10)];
    var ap = [1.4, 1.7, 1.8, 2.0, 2.8, 4.0][Math.floor(Math.random() * 6)];
    var shutter = [1 / 60, 1 / 125, 1 / 250, 1 / 500, 1 / 1000, 1 / 2000][Math.floor(Math.random() * 6)];
    var iso = [50, 64, 100, 200, 400, 800, 1600, 3200][Math.floor(Math.random() * 8)];
    return focal + 'mm ƒ/' + ap + ' 1/' + Math.round(1 / shutter) + 's ISO' + iso;
  }

  function buildParamChips() {
    var wrap = $('#paramPresets');
    wrap.innerHTML = '';
    PARAM_PRESETS.forEach(function (item) {
      var b = document.createElement('button');
      b.className = 'chip';
      b.textContent = item.label;
      b.addEventListener('click', function () {
        var val = item.text || randomParams();
        state.params = val;
        $('#fParams').value = val;
        persistText();
        requestRender();
      });
      wrap.appendChild(b);
    });
  }

  function buildBrands() {
    var wrap = $('#brands');
    wrap.innerHTML = '';
    Logos.list.forEach(function (b) {
      var btn = document.createElement('button');
      btn.className = 'brand-btn';
      btn.dataset.brand = b.id;
      btn.title = b.label;

      var canvas = document.createElement('canvas');
      drawBrandPreview(canvas, b.id, 17);
      btn.appendChild(canvas);

      var label = document.createElement('span');
      label.textContent = b.label;
      btn.appendChild(label);

      btn.addEventListener('click', function () {
        var prevBrand = state.brand;
        state.brand = b.id;
        syncBrand();
        buildSubChips();
        // 副标题仍是上一个品牌的预设时，顺带切换
        var prevList = SUB_PRESETS[prevBrand] || [];
        if (prevList.indexOf(state.subtitle) >= 0) {
          var next = (SUB_PRESETS[b.id] || SUB_PRESETS.none)[0];
          state.subtitle = next;
          $('#fSub').value = next;
        }
        persistText();
        requestRender();
      });

      wrap.appendChild(btn);
    });
  }

  function drawBrandPreview(canvas, id, h) {
    var dpr = 2;
    var ctx = canvas.getContext('2d');
    var w = id === 'none' ? h : Logos.measure(ctx, id, h);
    w = Math.max(w, 8);
    canvas.width = Math.ceil(w * dpr);
    canvas.height = Math.ceil(h * dpr);
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    if (id === 'none') {
      ctx.strokeStyle = 'rgba(255,255,255,0.55)';
      ctx.lineWidth = 1.3;
      ctx.beginPath();
      ctx.arc(w / 2, h / 2, h * 0.36, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(w / 2 - h * 0.25, h / 2 + h * 0.25);
      ctx.lineTo(w / 2 + h * 0.25, h / 2 - h * 0.25);
      ctx.stroke();
    } else {
      Logos.draw(ctx, id, 0, 0, h, '#ffffff');
    }
  }

  /* ═══════════ 控件绑定 ═══════════ */

  // 加白用对数坐标：滑轨中段对应约 16%，便于精细控制低值
  var CURVES = { white: 2.6 };

  function fromSlider(key, raw) {
    var g = CURVES[key];
    if (!g) return raw;
    return Math.round(100 * Math.pow(Math.max(0, raw) / 100, g));
  }

  function toSlider(key, val) {
    var g = CURVES[key];
    if (!g) return val;
    return 100 * Math.pow(Math.max(0, val) / 100, 1 / g);
  }

  function fmtValue(key, v) {
    if (key === 'ior') return v.toFixed(2);
    if (key === 'scale') return Math.round(v * 100) + '%';
    if (key === 'nameSize' || key === 'metaSize') return String(Math.round(v * 10) / 10);
    if (key === 'bottomGap') return (Math.round(v * 10) / 10) + '%';
    return Math.round(v) + '%';
  }

  var LENS_SCALE = 1;
  var THUMB_W = 38;

  function paintRange(input) {
    var min = parseFloat(input.min), max = parseFloat(input.max);
    var v = parseFloat(input.value);
    var r = max > min ? (v - min) / (max - min) : 0;
    r = Math.max(0, Math.min(1, r));
    input.style.setProperty('--p', (r * 100).toFixed(2) + '%');

    var wrap = input.parentNode;
    if (!wrap || !wrap.classList || !wrap.classList.contains('range-wrap')) return;

    wrap.style.setProperty('--r', r.toFixed(4));

    // 玻璃珠内的"透镜"：把轨道副本放大并平移，使珠心对准当前值
    var w = wrap.clientWidth || 300;
    var cx = THUMB_W / 2 + (w - THUMB_W) * r;
    wrap.style.setProperty('--lens-w', w + 'px');
    wrap.style.setProperty('--lens-fill', cx.toFixed(1) + 'px');
    wrap.style.setProperty('--lens-tx', (THUMB_W / 2 - cx * LENS_SCALE).toFixed(2) + 'px');
  }

  function syncViewToggle() {
    var wrap = $('#viewToggle');
    if (!wrap) return;
    wrap.querySelectorAll('button').forEach(function (b) {
      b.classList.toggle('is-on', b.dataset.view === (state.viewMode || 'detail'));
    });
  }

  function syncControls() {
    syncViewToggle();
    document.querySelectorAll('.ctrl').forEach(function (ctrl) {
      var key = ctrl.dataset.key;
      var input = ctrl.querySelector('input[type="range"]');
      var out = ctrl.querySelector('output');
      if (state[key] === undefined) return;
      input.value = toSlider(key, state[key]);
      out.textContent = fmtValue(key, state[key]);
      paintRange(input);
    });

    document.querySelectorAll('.seg').forEach(function (seg) {
      var key = seg.dataset.key;
      seg.querySelectorAll('button').forEach(function (b) {
        b.classList.toggle('is-on', b.dataset.value === String(state[key]));
      });
    });

    document.querySelectorAll('[data-toggle]').forEach(function (btn) {
      var key = btn.dataset.toggle;
      var on = !!state[key];
      if (btn.classList.contains('switch')) btn.setAttribute('aria-pressed', String(on));
      else btn.classList.toggle('is-on', on);
    });

    $('#qualityCtrl').style.display = state.format === 'jpeg' ? '' : 'none';
    if ($('#fTextColor')) $('#fTextColor').value = state.textColor || '#ffffff';
    $('#fMeta').value = state.meta || '';
  }

  /* ── 滑块：自绘玻璃轨道 + 触屏长按拖动（避免滑动页面误触） ── */

  var COARSE = !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);

  var lastHaptic = 0;
  function haptic(ms) {
    if (!navigator.vibrate) return;
    var now = Date.now();
    if (ms < 20 && now - lastHaptic < 22) return;   // 轻微节流，主要靠 2% 步进限制
    lastHaptic = now;
    try { navigator.vibrate(ms); } catch (err) { /* ignore */ }
  }
  var LONG_PRESS_MS = 300;
  var PRESS_TOLERANCE = 8;
  var DRAG_THRESHOLD = 5;

  /* ── 液态玻璃珠：用 feDisplacementMap 对珠内的轨道副本做真实折射 ── */

  var THUMB_H = 30;
  var THUMB_R = 15;

  function buildDisplacementMap(w, h) {
    var S = 2;
    var c = document.createElement('canvas');
    c.width = w * S;
    c.height = h * S;
    var ctx = c.getContext('2d');
    var img = ctx.createImageData(c.width, c.height);
    var d = img.data;
    var hw = c.width / 2;
    var hh = c.height / 2;
    var r = (h / 2) * S;

    function heightAt(x, y) {
      var px = Math.abs(x + 0.5 - hw) - (hw - r);
      var py = Math.abs(y + 0.5 - hh) - (hh - r);
      var sd = Math.min(Math.max(px, py), 0) + Math.sqrt(Math.max(px, 0) * Math.max(px, 0) + Math.max(py, 0) * Math.max(py, 0)) - r;
      var inside = Math.max(0, -sd);
      var t = Math.min(1, inside / r);
      return Math.sqrt(Math.max(0, 1 - (1 - t) * (1 - t)));
    }

    var e = 1.4;
    for (var y = 0; y < c.height; y++) {
      for (var x = 0; x < c.width; x++) {
        var gx = (heightAt(x + e, y) - heightAt(x - e, y)) / (2 * e);
        var gy = (heightAt(x, y + e) - heightAt(x, y - e)) / (2 * e);
        var i = (y * c.width + x) * 4;
        d[i] = 128 - Math.max(-1, Math.min(1, gx)) * 127;
        d[i + 1] = 128 - Math.max(-1, Math.min(1, gy)) * 127;
        d[i + 2] = 128;
        d[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return c.toDataURL('image/png');
  }

  function installThumbFilter() {
    if (document.getElementById('thumbGlassSvg')) return;
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('id', 'thumbGlassSvg');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none';
    var map = buildDisplacementMap(THUMB_W, THUMB_H);
    svg.innerHTML =
      '<defs><filter id="thumbGlass" x="-30%" y="-30%" width="160%" height="160%" color-interpolation-filters="sRGB">' +
      '<feImage href="' + map + '" x="0" y="0" width="' + THUMB_W + '" height="' + THUMB_H + '" preserveAspectRatio="none" result="map"/>' +
      '<feDisplacementMap in="SourceGraphic" in2="map" scale="9" xChannelSelector="R" yChannelSelector="G"/>' +
      '</filter></defs>';
    document.body.appendChild(svg);
  }

  function enhanceSlider(ctrl, input) {
    if (input.dataset.enhanced) return;
    input.dataset.enhanced = '1';

    var wrap = document.createElement('div');
    wrap.className = 'range-wrap';
    input.parentNode.insertBefore(wrap, input);
    wrap.appendChild(input);

    var track = document.createElement('div');
    track.className = 'range-track';
    var fill = document.createElement('div');
    fill.className = 'range-fill';
    track.appendChild(fill);
    wrap.appendChild(track);

    var thumb = document.createElement('div');
    thumb.className = 'range-thumb';
    var lens = document.createElement('div');
    lens.className = 'thumb-lens';
    var group = document.createElement('div');
    group.className = 'thumb-lens-group';
    var lensTrack = document.createElement('div');
    lensTrack.className = 'thumb-lens-track';
    var lensFill = document.createElement('div');
    lensFill.className = 'thumb-lens-fill';
    group.appendChild(lensTrack);
    group.appendChild(lensFill);
    lens.appendChild(group);
    thumb.appendChild(lens);
    var gloss = document.createElement('div');
    gloss.className = 'thumb-gloss';
    thumb.appendChild(gloss);
    var chroma = document.createElement('div');
    chroma.className = 'thumb-chroma';
    thumb.appendChild(chroma);
    wrap.appendChild(thumb);

    var hit = document.createElement('div');
    hit.className = 'range-hit';
    wrap.appendChild(hit);

    paintRange(input);

    var timer = 0;
    var dragging = false;
    var springOn = false;
    var springCur = 0;
    var springVel = 0;
    var springTarget = 0;
    var springRaf = 0;
    var lastHapticPct = null;
    var activeId = null;
    var startX = 0;
    var lastX = 0;
    var startValue = 0;
    var isTouch = false;
    var moved = false;

    function trackWidth() {
      return Math.max(1, hit.getBoundingClientRect().width - THUMB_W);
    }

    function applyRaw(v) {
      var min = parseFloat(input.min);
      var max = parseFloat(input.max);
      v = Math.max(min, Math.min(max, v));
      if (Math.abs(parseFloat(input.value) - v) < 1e-6) return;
      input.value = v;
      if (dragging && isTouch) {
        var pct = (v - min) / Math.max(1e-6, max - min) * 100;
        if (lastHapticPct === null || Math.abs(pct - lastHapticPct) >= 2) {
          lastHapticPct = pct;
          haptic(6);
        }
      }
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function springFrame(now) {
      if (!springLast) springLast = now;
      var dt = Math.min(0.05, (now - springLast) / 1000);
      springLast = now;

      var k = 620, damp = 34;
      springVel += (springTarget - springCur) * k * dt;
      springVel *= Math.exp(-damp * dt);
      springCur += springVel * dt;
      applyRaw(springCur);

      if (Math.abs(springTarget - springCur) < 2e-3 && Math.abs(springVel) < 2e-2) {
        springOn = false;
        springLast = 0;
        commit(springTarget);
        return;
      }
      springRaf = requestAnimationFrame(springFrame);
    }

    var springLast = 0;

    function kickSpring() {
      if (springOn) return;
      springOn = true;
      springLast = 0;
      springRaf = requestAnimationFrame(springFrame);
    }

    function startSpring() {
      springCur = parseFloat(input.value);
      springVel = 0;
      springTarget = springCur;
      kickSpring();
    }

    function commit(v) {
      var step = parseFloat(input.step) || 1;
      v = Math.round(v / step) * step;
      var digits = (String(step).split('.')[1] || '').length;
      var min = parseFloat(input.min);
      var max = parseFloat(input.max);
      v = parseFloat(Math.min(max, Math.max(min, v)).toFixed(digits));
      if (Math.abs(parseFloat(input.value) - v) < 1e-9) return;
      input.value = v;
      if (dragging && isTouch) {
        var pct = (v - min) / Math.max(1e-6, max - min) * 100;
        if (lastHapticPct === null || Math.abs(pct - lastHapticPct) >= 2) {
          lastHapticPct = pct;
          haptic(6);
        }
      }
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }

    /** 按绝对位置取值（仅鼠标点击跳转用） */
    function valueAt(clientX) {
      var r = hit.getBoundingClientRect();
      var min = parseFloat(input.min);
      var max = parseFloat(input.max);
      var t = (clientX - r.left - THUMB_W / 2) / trackWidth();
      t = t < 0 ? 0 : t > 1 ? 1 : t;
      return min + t * (max - min);
    }

    function begin() {
      clearTimeout(timer);
      timer = 0;
      dragging = true;
      moved = false;
      startX = lastX;
      startValue = parseFloat(input.value);
      ctrl.classList.remove('is-pressing');
      ctrl.classList.add('is-dragging');
      lastHapticPct = null;
      startSpring();
      var dm = document.querySelector('#thumbGlass feDisplacementMap');
      if (dm) dm.setAttribute('scale', '17');
      if (activeId !== null) {
        try { hit.setPointerCapture(activeId); } catch (err) { /* ignore */ }
      }
      if (isTouch) haptic(14);
    }

    function reset() {
      clearTimeout(timer);
      timer = 0;
      ctrl.classList.remove('is-pressing', 'is-dragging');
      if (activeId !== null) {
        try { hit.releasePointerCapture(activeId); } catch (err) { /* ignore */ }
      }
      dragging = false;
      activeId = null;
      moved = false;
      if (springRaf) cancelAnimationFrame(springRaf);
      springOn = false;
      springLast = 0;
      var dm2 = document.querySelector('#thumbGlass feDisplacementMap');
      if (dm2) dm2.setAttribute('scale', '9');
    }

    hit.addEventListener('pointerdown', function (e) {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      activeId = e.pointerId;
      // 有 pointerType 时以它为准（鼠标保持即时点击）；缺失时按设备指针类型兜底
      var pt = e.pointerType || (COARSE ? 'touch' : 'mouse');
      isTouch = pt !== 'mouse';
      startX = e.clientX;
      lastX = e.clientX;
      ctrl.classList.add('is-pressing');

      if (isTouch) {
        // 触屏：必须按住 300ms 才进入拖动，期间不改变数值
        timer = setTimeout(begin, LONG_PRESS_MS);
      } else {
        // 鼠标：点击轨道直接跳到该位置，然后可继续拖
        commit(valueAt(e.clientX));
        begin();
      }
    });

    hit.addEventListener('pointermove', function (e) {
      lastX = e.clientX;
      if (!dragging) {
        if (Math.abs(e.clientX - startX) > PRESS_TOLERANCE ||
            Math.abs(e.clientY - (hit.getBoundingClientRect().top + 16)) > 40) {
          clearTimeout(timer);
          timer = 0;
          ctrl.classList.remove('is-pressing');
        }
        return;
      }

      var dx = e.clientX - startX;
      if (!moved && Math.abs(dx) < DRAG_THRESHOLD) return;   // 抖动阈值
      moved = true;

      var min = parseFloat(input.min);
      var max = parseFloat(input.max);
      springTarget = startValue + (dx / trackWidth()) * (max - min);
      springTarget = Math.max(min, Math.min(max, springTarget));
      kickSpring();
    });

    hit.addEventListener('pointerup', reset);
    hit.addEventListener('pointercancel', reset);
    hit.addEventListener('lostpointercapture', reset);
    hit.addEventListener('contextmenu', function (e) { e.preventDefault(); });

    hit.addEventListener('touchmove', function (e) {
      if (dragging) e.preventDefault();
    }, { passive: false });
  }

  function bindControls() {
    document.querySelectorAll('.ctrl').forEach(function (ctrl) {
      var key = ctrl.dataset.key;
      var input = ctrl.querySelector('input[type="range"]');
      var out = ctrl.querySelector('output');
      input.min = ctrl.dataset.min;
      input.max = ctrl.dataset.max;
      input.step = ctrl.dataset.step;
      input.value = toSlider(key, state[key]);
      out.textContent = fmtValue(key, state[key]);
      paintRange(input);

      enhanceSlider(ctrl, input);

      input.addEventListener('input', function () {
        state[key] = fromSlider(key, parseFloat(input.value));
        out.textContent = fmtValue(key, state[key]);
        paintRange(input);
        if (key === 'scale') updateExportInfo();
        requestRender();
        saveSettings();
        if (GLASS_KEYS.indexOf(key) >= 0) {
          document.querySelectorAll('.preset').forEach(function (p) { p.classList.remove('is-on'); });
        }
      });
    });

    document.querySelectorAll('.seg').forEach(function (seg) {
      var key = seg.dataset.key;
      seg.querySelectorAll('button').forEach(function (b) {
        b.addEventListener('click', function () {
          if (key === 'logoRight') state[key] = b.dataset.value === 'true';
          else if (key === 'frostTone') state[key] = parseInt(b.dataset.value, 10);
          else state[key] = b.dataset.value;
          seg.querySelectorAll('button').forEach(function (x) {
            x.classList.toggle('is-on', x === b);
          });
          if (key === 'format') {
            $('#qualityCtrl').style.display = state.format === 'jpeg' ? '' : 'none';
          }
          requestRender();
          saveSettings();
        });
      });
    });

    document.querySelectorAll('[data-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var key = btn.dataset.toggle;
        state[key] = !state[key];
        if (btn.classList.contains('switch')) btn.setAttribute('aria-pressed', String(state[key]));
        else btn.classList.toggle('is-on', state[key]);
        requestRender();
        saveSettings();
      });
    });

    // 文本输入
    [['#fName', 'name'], ['#fSub', 'subtitle'], ['#fParams', 'params'], ['#fMeta', 'meta']].forEach(function (pair) {
      var input = $(pair[0]);
      input.addEventListener('input', function () {
        state[pair[1]] = input.value;
        persistText();
        requestRender();
        saveSettings();
      });
    });

    // 玻璃预设
    document.querySelectorAll('.preset').forEach(function (p) {
      p.addEventListener('click', function () {
        var preset = GLASS_PRESETS[p.dataset.preset];
        if (!preset) return;
        Object.assign(state, preset);
        haptic(10);
        document.querySelectorAll('.preset').forEach(function (x) {
          x.classList.toggle('is-on', x === p);
        });
        syncControls();
        requestRender();
        saveSettings();
      });
    });

    $('#viewToggle').addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('button[data-view]') : null;
    if (!btn) return;
    state.viewMode = btn.dataset.view;
    haptic(10);
    syncViewToggle();
    requestRender();
    stageScroll.scrollLeft = 0;
    saveSettings();
  });

  $('#btnExif').addEventListener('click', function () {
      var img = currentImage();
      if (!img) return;
      if (applyExifTo(img)) toast('已从 EXIF 重新填入拍摄信息');
    });
  }

  /* ═══════════ 导出 ═══════════ */

  function sanitize(name) {
    return String(name || 'photo').replace(/\.[^.]+$/, '').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 60);
  }

  function download(blob, filename) {
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
  }

  var MAX_EXPORT = 16384;

  function composeImage(img, s) {
    var W = Math.round(img.w * s.scale);
    var H = Math.round(img.h * s.scale);
    if (Math.max(W, H) > MAX_EXPORT) {
      var k = MAX_EXPORT / Math.max(W, H);
      W = Math.round(W * k);
      H = Math.round(H * k);
    }

    var out = document.createElement('canvas');
    out.width = W;
    out.height = H;

    // HDR（ProXDR / Ultra HDR）照片的基底通常是 Display P3，
    // 用 sRGB 画布会让增益图的计算基准错位，这里保持 P3。
    var ctx = null;
    if (img.exif && img.exif.hdr) {
      try { ctx = out.getContext('2d', { colorSpace: 'display-p3' }); } catch (err) { ctx = null; }
    }
    if (!ctx) ctx = out.getContext('2d');

    if (s.format === 'jpeg') {
      ctx.fillStyle = '#0a0a0a';
      ctx.fillRect(0, 0, W, H);
    }

    // 整图交给 2D 绘制，这样 16K 也不受 GL 纹理尺寸限制
    ctx.drawImage(img.el, 0, 0, W, H);

    var lay = Watermark.layout(W, H, s);
    if (!lay.hasContent) return out;

    drawGlassRegion(ctx, img, lay, W, H, s);
    Watermark.draw(ctx, lay, s);
    return out;
  }

  /** 只对水印附近区域做 WebGL，按 GPU 纹理上限分条渲染 */
  function drawGlassRegion(ctx, img, lay, W, H, s) {
    if (!renderer) return;
    var p = lay.panel;
    var u = lay.u;
    var mx = 40 * u;
    var my = 60 * u;

    var x = Math.max(0, Math.round(p.x - mx));
    var y = Math.max(0, Math.round(p.y - my));
    var w = Math.min(W - x, Math.round(p.w + mx * 2));
    var h = Math.min(H - y, Math.round(p.h + my * 2));
    if (w < 8 || h < 8) return;

    var stripMax = Math.min(renderer.maxTex, 8000);
    var cols = Math.ceil(w / stripMax);
    var k = 1 / s.scale;

    for (var i = 0; i < cols; i++) {
      var sx = x + i * stripMax;
      var sw = Math.min(stripMax, w - i * stripMax);
      if (sw < 4) break;

      var src = document.createElement('canvas');
      src.width = Math.round(sw);
      src.height = Math.round(h);
      src.getContext('2d').drawImage(img.el,
        sx * k, y * k, sw * k, h * k,
        0, 0, src.width, src.height);

      var glCanvas = renderer.renderCropped(src, src.width, src.height, {
        x: p.x - sx,
        y: p.y - y,
        w: p.w,
        h: p.h,
        r: p.r,
        bevel: p.bevel,
        edgeW: p.edgeW
      }, glassOpts(s), W);

      ctx.drawImage(glCanvas, Math.round(sx), Math.round(y));
    }
  }

  /** 把原始动态照片的内嵌数据（增益图 + MP4）与元数据段拼回导出的 JPEG */
  async function attachMotion(blob, motion) {
    try {
      if (!motion || !motion.xmp || !motion.trailer) return null;

      var buf = new Uint8Array(await blob.arrayBuffer());
      if (buf[0] !== 0xFF || buf[1] !== 0xD8) return null;
      var body = buf.subarray(2);

      // 复制原始元数据段（EXIF / XMP / 增益图标记 / MPF / ICC）
      var segs = (motion.metaSegs || []).slice();
      if (!segs.length) {
        var head = 'http://ns.adobe.com/xap/1.0/';
        var payloadLen = head.length + 1 + motion.xmp.length;
        var segLen = payloadLen + 2;
        if (segLen > 65533) return null;
        var payload = new Uint8Array(payloadLen);
        for (var i = 0; i < head.length; i++) payload[i] = head.charCodeAt(i);
        payload[head.length] = 0;
        payload.set(motion.xmp, head.length + 1);
        var seg = new Uint8Array(4 + payloadLen);
        seg[0] = 0xFF; seg[1] = 0xE1;
        seg[2] = (segLen >> 8) & 0xFF; seg[3] = segLen & 0xFF;
        seg.set(payload, 4);
        segs = [seg];
      }

      var metaLen = 0;
      for (var k = 0; k < segs.length; k++) metaLen += segs[k].length;
      var trailerStart = 2 + metaLen + body.length;

      // 修补 MPF：副图偏移改为相对 MPF TIFF 头的新位置
      if (motion.mpf && motion.mpf.entries.length > 1) {
        var mpfIdx = -1;
        for (var q = 0; q < segs.length; q++) {
          if (segs[q][0] === 0xFF && segs[q][1] === 0xE2 &&
              segs[q][4] === 0x4D && segs[q][5] === 0x50) { mpfIdx = q; break; }
        }
        if (mpfIdx >= 0) {
          var mpfSeg = new Uint8Array(segs[mpfIdx]);   // 拷贝，避免污染缓存
          segs[mpfIdx] = mpfSeg;

          var mpfStart = 2;
          for (var r = 0; r < mpfIdx; r++) mpfStart += segs[r].length;
          var tiffAbs = mpfStart + motion.mpf.tiff;

          var writeU32 = function (arr, pos, val) {
            if (motion.mpf.little) {
              arr[pos] = val & 0xFF;
              arr[pos + 1] = (val >>> 8) & 0xFF;
              arr[pos + 2] = (val >>> 16) & 0xFF;
              arr[pos + 3] = (val >>> 24) & 0xFF;
            } else {
              arr[pos] = (val >>> 24) & 0xFF;
              arr[pos + 1] = (val >>> 16) & 0xFF;
              arr[pos + 2] = (val >>> 8) & 0xFF;
              arr[pos + 3] = val & 0xFF;
            }
          };

          // 第 0 张 = 主图：大小即当前主图长度
          var e0 = motion.mpf.entries[0];
          if (e0 && e0.sizePos + 4 <= mpfSeg.length) writeU32(mpfSeg, e0.sizePos, trailerStart);

          // 其后依次是尾部各段（增益图在前）
          var cursor = trailerStart;
          for (var m = 1; m < motion.mpf.entries.length; m++) {
            var ent = motion.mpf.entries[m];
            if (ent.offPos + 4 > mpfSeg.length) break;
            writeU32(mpfSeg, ent.offPos, Math.max(0, cursor - tiffAbs));
            if (ent.size) cursor += ent.size;
          }
        }
      }

      var out = new Uint8Array(2 + metaLen + body.length + motion.trailer.length);
      var p = 0;
      out[p++] = 0xFF;
      out[p++] = 0xD8;
      for (var n = 0; n < segs.length; n++) {
        out.set(segs[n], p);
        p += segs[n].length;
      }
      out.set(body, p); p += body.length;
      out.set(motion.trailer, p);

      // 自检：重新解析，确认动态目录与尾部依然自洽
      var check = Exif.parse(out.buffer);
      if (!check || !check.motion || !check.motion.preserves) return null;
      if (check.motion.trailerLength !== motion.trailerLength) return null;

      return new Blob([out], { type: 'image/jpeg' });
    } catch (err) {
      console.error(err);
      return null;
    }
  }

  function toBlob(canvas, s) {
    return new Promise(function (resolve) {
      var mime = s.format === 'jpeg' ? 'image/jpeg' : 'image/png';
      var q = s.format === 'jpeg' ? s.quality / 100 : undefined;
      canvas.toBlob(function (b) { resolve(b); }, mime, q);
    });
  }

  async function exportImages(list) {
    if (!list.length || exporting || !renderer) return;
    exporting = true;
    setBusy(true);

    try {
      for (var i = 0; i < list.length; i++) {
        var img = list[i];
        var s = Object.assign({}, state, img.text);
        var canvas = composeImage(img, s);
        var blob = await toBlob(canvas, s);
        if (!blob) { toast('导出失败', 'warn'); continue; }

        var motion = img.exif && img.exif.motion;
        if (motion && motion.preserves) {
          if (s.format === 'jpeg') {
            var merged = await attachMotion(blob, motion);
            if (merged) blob = merged;
            else toast('动态视频未能保留，已导出静态图', 'warn');
          } else {
            toast('动态照片请用 JPEG 导出，PNG 不含视频', 'warn');
          }
        }
        var ext = s.format === 'jpeg' ? '.jpg' : '.png';
        download(blob, sanitize(img.name) + '-vitrelens' + ext);
        if (i < list.length - 1) await new Promise(function (r) { setTimeout(r, 260); });
      }
      toast(list.length > 1 ? '已导出 ' + list.length + ' 张照片' : '已导出图片');
    } catch (err) {
      console.error(err);
      toast('导出失败：' + err.message, 'warn');
    } finally {
      exporting = false;
      setBusy(false);
      requestRender();
    }
  }

  function setBusy(on) {
    [$('#btnExport'), $('#btnExport2'), $('#btnExportAll')].forEach(function (btn) {
      if (!btn) return;
      btn.classList.toggle('is-busy', on);
      if (on) btn.disabled = true;
      else if (btn.id !== 'btnExportAll') btn.disabled = images.length === 0;
    });
    if (!on) $('#btnExportAll').disabled = false;
  }

  /* ═══════════ 设置持久化 ═══════════ */

  var STORE_KEY = 'vitrelens.settings.v1';

  var saveTimer = 0;
  function saveSettings() {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      saveTimer = 0;
      try {
        var s = {};
        Object.keys(DEFAULTS).forEach(function (k) { s[k] = state[k]; });
        localStorage.setItem(STORE_KEY, JSON.stringify(s));
      } catch (err) { /* ignore */ }
    }, 400);
  }

  function loadSettings() {
    try {
      var raw = localStorage.getItem(STORE_KEY);
      if (!raw) return;
      var s = JSON.parse(raw);
      Object.keys(DEFAULTS).forEach(function (k) {
        if (s[k] !== undefined && typeof s[k] === typeof DEFAULTS[k]) state[k] = s[k];
      });
    } catch (err) { /* ignore */ }
  }

  function bindTabs() {
    document.querySelectorAll('.insp-rail button').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var key = btn.dataset.tab;
        document.querySelectorAll('.insp-rail button').forEach(function (b) {
          b.classList.toggle('is-on', b === btn);
        });
        document.querySelectorAll('.insp-scroll [data-page]').forEach(function (page) {
          page.classList.toggle('is-active', page.dataset.page === key);
        });
        var scroller = document.querySelector('.insp-scroll');
        if (scroller) scroller.scrollTop = 0;
        repaintAllRanges();
      });
    });
  }

  function bindColorControls() {
    var input = $('#fTextColor');
    if (!input) return;
    input.addEventListener('input', function () {
      state.textColor = input.value;
      requestRender();
      saveSettings();
    });
    $('#btnAutoColor').addEventListener('click', function () {
      state.textColor = '';
      input.value = '#ffffff';
      requestRender();
      saveSettings();
    });
  }

  /* ═══════════ 事件 ═══════════ */

  function openPicker() { fileInput.click(); }

  fileInput.addEventListener('change', function () {
    addFiles(fileInput.files);
    fileInput.value = '';
  });

  $('#btnOpen').addEventListener('click', openPicker);
  $('#btnPick').addEventListener('click', openPicker);

  $('#btnRemove').addEventListener('click', function () {
    removeImage(current);
  });

  $('#btnReset').addEventListener('click', function () {
    Object.assign(state, DEFAULTS);
    syncControls();
    document.querySelectorAll('.preset').forEach(function (p, i) {
      p.classList.toggle('is-on', i === 0);
    });
    requestRender();
    saveSettings();
    toast('已恢复默认效果参数');
  });

  $('#btnExport').addEventListener('click', function () {
    var img = currentImage();
    if (img) exportImages([img]);
  });
  $('#btnExport2').addEventListener('click', function () {
    var img = currentImage();
    if (img) exportImages([img]);
  });
  $('#btnExportAll').addEventListener('click', function () {
    exportImages(images.slice());
  });

  // 拖拽
  var dragDepth = 0;
  window.addEventListener('dragenter', function (e) {
    if (!e.dataTransfer || Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') < 0) return;
    e.preventDefault();
    dragDepth++;
    dragOverlay.classList.add('is-on');
  });
  window.addEventListener('dragover', function (e) {
    if (e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') >= 0) {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
    }
  });
  window.addEventListener('dragleave', function (e) {
    if (e.relatedTarget) return;
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) dragOverlay.classList.remove('is-on');
  });
  window.addEventListener('drop', function (e) {
    if (!e.dataTransfer) return;
    e.preventDefault();
    dragDepth = 0;
    dragOverlay.classList.remove('is-on');
    if (e.dataTransfer.files && e.dataTransfer.files.length) addFiles(e.dataTransfer.files);
  });

  // 快捷键
  window.addEventListener('keydown', function (e) {
    var mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'o') { e.preventDefault(); openPicker(); }
    if (mod && e.key.toLowerCase() === 's') {
      e.preventDefault();
      var img = currentImage();
      if (img) exportImages([img]);
    }
  });

  /* 拖动平移（仅鼠标；触摸走原生横向滚动） */
  var panning = false, panStartX = 0, panStartLeft = 0;
  stageScroll.addEventListener('pointerdown', function (e) {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    if (!stageScroll.classList.contains('is-pannable')) return;
    panning = true;
    panStartX = e.clientX;
    panStartLeft = stageScroll.scrollLeft;
    stageScroll.classList.add('is-panning');
    try { stageScroll.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
  });
  stageScroll.addEventListener('pointermove', function (e) {
    if (!panning) return;
    stageScroll.scrollLeft = panStartLeft - (e.clientX - panStartX);
  });
  function endPan() {
    panning = false;
    stageScroll.classList.remove('is-panning');
  }
  stageScroll.addEventListener('pointerup', endPan);
  stageScroll.addEventListener('pointercancel', endPan);

  function repaintAllRanges() {
    document.querySelectorAll('.ctrl input[type="range"]').forEach(function (inp) { paintRange(inp); });
  }

  window.addEventListener('resize', function () {
    requestRender();
    repaintAllRanges();
  });
  if (window.ResizeObserver) {
    new ResizeObserver(function () { requestRender(); }).observe(stage);
  }

  /* ═══════════ 启动 ═══════════ */

  loadSettings();
  installThumbFilter();
  buildBrands();
  buildParamChips();
  renderDemo();
  bindControls();
  bindTabs();
  bindColorControls();
  syncControls();
  syncBrand();
  buildSubChips();

  window.LG.app = { state: state, render: render, requestRender: requestRender, images: images };

  if (!renderer) {
    $('#btnExport').disabled = true;
    $('#btnExport2').disabled = true;
  }
})();
