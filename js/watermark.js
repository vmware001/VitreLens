/* ═══════════════════════════════════════════════════════════
   watermark.js — 水印版式计算与文字绘制
   所有尺寸均以画幅宽度为基准，保证预览与导出完全一致
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var STACKS = {
    sans: '"Helvetica Neue", "Segoe UI", Arial, "PingFang SC", "Microsoft YaHei", sans-serif',
    condensed: '"Bahnschrift", "Arial Narrow", "Helvetica Neue", Arial, "PingFang SC", sans-serif',
    serif: '"Didot", "Bodoni MT", Georgia, "Times New Roman", "Songti SC", serif',
    mono: '"SF Mono", "JetBrains Mono", Consolas, "Courier New", monospace'
  };

  var measureCanvas = document.createElement('canvas');
  var mctx = measureCanvas.getContext('2d');

  function fontOf(size, stack, weight) {
    return (weight || 600) + ' ' + size.toFixed(2) + 'px ' + (STACKS[stack] || STACKS.sans);
  }

  function metrics(ctx, text, font) {
    ctx.font = font;
    var m = ctx.measureText(text || ' ');
    return {
      width: m.width,
      asc: m.actualBoundingBoxAscent || 0,
      desc: m.actualBoundingBoxDescent || 0
    };
  }

  var trackedCtx = null;
  function drawTracked(ctx, text, x, baseline, tracking, slant) {
    // 逐字绘制，保证跨浏览器一致的字距
    var chars = String(text || '').split('');
    ctx.save();
    ctx.translate(x, baseline);
    if (slant) ctx.transform(1, 0, -slant, 1, 0, 0);
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
    var cx = 0;
    for (var i = 0; i < chars.length; i++) {
      ctx.fillText(chars[i], cx, 0);
      cx += ctx.measureText(chars[i]).width + tracking;
    }
    ctx.restore();
  }

  function trackedWidth(ctx, text, font, tracking) {
    ctx.font = font;
    var chars = String(text || '').split('');
    var w = 0;
    for (var i = 0; i < chars.length; i++) w += ctx.measureText(chars[i]).width;
    return w + tracking * Math.max(0, chars.length - 1);
  }

  /**
   * 计算版式
   * @param {number} W 画幅宽
   * @param {number} H 画幅高
   * @param {object} st 参数
   */
  function layout(W, H, st) {
    var ctx = mctx;
    var u = W / 1000;

    var padX = 32 * u;
    var padY = 22 * u;
    var gapName = 0;
    var logoH = 0;

    var fonts = {
      name: { stack: st.font, weight: 600, tracking: 0 },
      sub: { stack: st.font === 'serif' ? 'serif' : 'sans', weight: 500, tracking: 0 },
      meta: { stack: 'sans', weight: 500, tracking: 0 }
    };

    var text = {
      name: (st.name || '').trim(),
      sub: (st.subtitle || '').trim(),
      params: (st.params || '').trim(),
      meta: (st.meta || '').trim()
    };

    var scale = 1;
    var result = null;

    for (var attempt = 0; attempt < 3; attempt++) {
      var nameSize = st.nameSize * u * scale;
      var subSize = nameSize * 0.42;
      var metaSize = st.metaSize * u * scale;
      var slant = st.italic ? 0.115 : 0;

      var fName = fontOf(nameSize, fonts.name.stack, 600);
      var fSub = fontOf(subSize, 'sans', 500);
      var fMeta = fontOf(metaSize, 'sans', 500);

      var nameW = trackedWidth(ctx, text.name, fName, nameSize * 0.026) + (st.italic ? nameSize * 0.09 : 0);
      var nameM = metrics(ctx, text.name, fName);

      var dotOn = st.showSubtitle && !!text.sub;
      var dotR = subSize * 0.17;
      var dotGap = subSize * 0.55;
      var subW = dotOn
        ? dotR * 2 + dotGap + trackedWidth(ctx, text.sub, fSub, subSize * 0.02) + (st.italic ? subSize * 0.1 : 0)
        : 0;
      var subM = metrics(ctx, text.sub, fSub);

      var paramsOn = st.showParams && !!text.params;
      var metaOn = st.showMeta && !!text.meta;
      var paramsW = paramsOn ? trackedWidth(ctx, text.params, fMeta, metaSize * 0.02) + (st.italic ? metaSize * 0.08 : 0) : 0;
      var metaW = metaOn ? trackedWidth(ctx, text.meta, fMeta, metaSize * 0.02) + (st.italic ? metaSize * 0.08 : 0) : 0;
      var metaM = metrics(ctx, text.meta, fMeta);
      var paramsM = metrics(ctx, text.params, fMeta);

      var leftW = Math.max(nameW, subW);
      var subGap = nameSize * 0.24;
      var nameH = nameM.asc + nameM.desc;
      var subH = subM.asc + subM.desc;
      var leftH = nameH + (dotOn ? subGap + subH : 0);

      var lineGap = metaSize * 0.62;
      var rightW = Math.max(paramsW, metaW);
      var rightH = (paramsOn ? paramsM.asc + paramsM.desc : 0)
                 + (paramsOn && metaOn ? lineGap : 0)
                 + (metaOn ? metaM.asc + metaM.desc : 0);

      var brandScale = st.brand === 'apple' ? 1.45 : (st.brand === 'leica' ? 1.35 : (st.brand === 'hasselblad' ? 1.2 : 1));
      logoH = 24 * u * scale * brandScale;
      var logoW = (st.showBrand && st.brand !== 'none') ? global.LG.Logos.measure(ctx, st.brand, logoH) : 0;

      var gapA = logoW ? nameSize * 0.55 : 0;
      var gapB = logoW ? nameSize * 1.05 : 0;
      var contentW = st.logoRight
        ? leftW + gapA + rightW + gapB + logoW
        : leftW + gapA + logoW + gapB + rightW;

      var margin = 38 * u;
      var panelW = Math.max(W * (st.widthPct / 100), contentW + padX * 2);
      panelW = Math.min(panelW, W - margin * 2);

      var availW = panelW - padX * 2;
      var needW = contentW;

      if (needW > availW + 0.5 && attempt < 2) {
        scale *= Math.max(0.55, availW / needW) * 0.985;
        continue;
      }

      var contentH = Math.max(leftH, rightH, logoW ? logoH : 0);
      var panelH = contentH * ((st.glassHeightPct === undefined ? 100 : st.glassHeightPct) / 100) + padY * 2;
      var panelX = margin;
      if (st.align === 'center') panelX = (W - panelW) / 2;
      else if (st.align === 'right') panelX = W - margin - panelW;
      var bottomGap = (st.bottomGap === undefined ? 3.8 : st.bottomGap) * W / 100;
      var panelY = Math.max(0, H - bottomGap - panelH);
      var rightEdge = panelX + panelW - padX;
      if (st.logoRight && logoW > 0) {
        var maxEdge = rightEdge - logoW - gapB;
        var minEdge = panelX + padX + leftW + gapA + rightW;
        var travel = Math.max(0, maxEdge - minEdge);
        var lfx = Math.max(0, Math.min(100, st.logoPosPct === undefined ? 100 : st.logoPosPct)) / 100;
        rightEdge = maxEdge - travel * (1 - lfx);
      }

      result = {
        W: W, H: H, u: u, scale: scale,
        hasContent: !!(text.name || (dotOn && text.sub) || (paramsOn && text.params) ||
                       (metaOn && text.meta) || (logoW > 0)),
        panel: {
          x: panelX, y: panelY, w: panelW, h: panelH,
          r: (panelH / 2) * (st.radius / 100),
          bevel: (panelH / 2) * (st.bevel / 100),
          edgeW: (panelH / 2) * ((st.edgeWidth === undefined ? 50 : st.edgeWidth) / 100)
        },
        padX: padX, padY: padY,
        name: {
          text: text.name, font: fName, size: nameSize, tracking: nameSize * 0.026,
          width: nameW, asc: nameM.asc, desc: nameM.desc,
          x: panelX + padX, baseline: 0, slant: slant
        },
        sub: {
          text: text.sub, font: fSub, size: subSize, tracking: subSize * 0.02,
          width: subW, asc: subM.asc, on: dotOn, dotR: dotR, dotGap: dotGap,
          x: panelX + padX, baseline: 0, slant: slant * 0.8
        },
        params: {
          text: text.params, font: fMeta, size: metaSize, tracking: metaSize * 0.02,
          width: paramsW, asc: paramsM.asc, on: paramsOn,
          x: rightEdge, baseline: 0, slant: slant * 0.7
        },
        meta: {
          text: text.meta, font: fMeta, size: metaSize, tracking: metaSize * 0.02,
          width: metaW, asc: metaM.asc, on: metaOn,
          x: rightEdge, baseline: 0, slant: slant * 0.7
        },
        logo: {
          id: st.brand, w: logoW, h: logoH, x: 0, y: 0, on: logoW > 0
        },
        leftW: leftW, rightW: rightW, leftH: leftH, rightH: rightH,
        colors: {
          accent: global.LG.Logos.accent(st.brand)
        }
      };

      // 垂直排布：左右两块各自居中
      var cy = panelY + panelH / 2;

      var leftTop = cy - leftH / 2;
      result.name.baseline = leftTop + nameM.asc;
      if (dotOn) result.sub.baseline = leftTop + nameH + subGap + subM.asc;

      if (paramsOn || metaOn) {
        var rightTop = cy - rightH / 2;
        var y = rightTop;
        if (paramsOn) { result.params.baseline = y + paramsM.asc; y += paramsM.asc + paramsM.desc + lineGap; }
        if (metaOn) { result.meta.baseline = y + metaM.asc; }
      }

      if (logoW) {
        var leftEnd = panelX + padX + leftW;
        var rightStart = panelX + panelW - padX - rightW;
        var space = rightStart - leftEnd;
        var gx;
        if (st.logoRight) {
          gx = rightEdge + gapB;
        } else {
          var lfx2 = Math.max(0, Math.min(100, st.logoPosPct === undefined ? 100 : st.logoPosPct)) / 100;
          var from = leftEnd + gapA;
          var span = rightStart - gapB - from - logoW;
          gx = span > 0 ? from + span * lfx2 : from;
        }
        result.logo.x = gx;
        result.logo.y = cy - logoH / 2;
      }

      break;
    }

    return result;
  }

  function makeCanvas(w, h) {
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.ceil(w));
    c.height = Math.max(1, Math.ceil(h));
    return c;
  }

  /** 逐字绘制（fill 或 stroke），用于材质蒙版 */
  function drawTrackedMode(ctx, text, x, baseline, tracking, slant, mode, lineWidth) {
    var chars = String(text || '').split('');
    ctx.save();
    ctx.translate(x, baseline);
    if (slant) ctx.transform(1, 0, -slant, 1, 0, 0);
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
    if (mode === 'stroke') ctx.lineWidth = lineWidth;
    var cx = 0;
    for (var i = 0; i < chars.length; i++) {
      if (mode === 'stroke') ctx.strokeText(chars[i], cx, 0);
      else ctx.fillText(chars[i], cx, 0);
      cx += ctx.measureText(chars[i]).width + tracking;
    }
    ctx.restore();
  }

  /**
   * 机型名绘制：支持 solid / frost / liquid 三种材质
   * 后两者以「背景像素 + 蒙版」合成，模拟玻璃字形
   */
  function drawName(ctx, lay, st) {
    var n = lay.name;
    var sl = lay.u;
    var mode = st.nameMaterial || 'solid';

    if (mode === 'solid') {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.38)';
      ctx.shadowBlur = Math.max(1.5, sl * 4.4);
      ctx.shadowOffsetY = sl * 1.0;
      ctx.fillStyle = st.textColor || 'rgba(255,255,255,0.985)';
      ctx.font = n.font;
      drawTracked(ctx, n.text, n.x, n.baseline, n.tracking, n.slant);
      ctx.restore();
      return;
    }

    var pad = Math.max(6, 14 * sl);
    var bx = Math.max(0, Math.floor(n.x - pad));
    var by = Math.max(0, Math.floor(n.baseline - n.asc - pad));
    var bw = Math.min(ctx.canvas.width - bx, Math.ceil(n.width + pad * 2));
    var bh = Math.min(ctx.canvas.height - by, Math.ceil(n.asc + n.desc + pad * 2));
    if (bw < 4 || bh < 4) return;

    var relX = n.x - bx;
    var relY = n.baseline - by;

    // 1. 字形蒙版
    var mask = makeCanvas(bw, bh);
    var mc = mask.getContext('2d');
    mc.font = n.font;
    mc.fillStyle = '#fff';
    mc.textBaseline = 'alphabetic';
    drawTrackedMode(mc, n.text, relX, relY, n.tracking, n.slant, 'fill');

    // 2. 取背景（液态玻璃带轻微位移；磨砂带模糊）
    var mat = makeCanvas(bw, bh);
    var mt = mat.getContext('2d');
    var dx = mode === 'liquid' ? 0 : 0;
    var dy = mode === 'liquid' ? -1.8 * sl : 0;
    var sx = Math.max(0, bx + dx);
    var sy = Math.max(0, by + dy);
    var sw = Math.min(ctx.canvas.width - sx, bw);
    var sh = Math.min(ctx.canvas.height - sy, bh);
    if (mode === 'frost' && typeof mt.filter === 'string') {
      mt.filter = 'blur(' + (3.4 * sl).toFixed(2) + 'px)';
    }
    mt.drawImage(ctx.canvas, sx, sy, sw, sh, 0, 0, sw, sh);
    mt.filter = 'none';

    // 3. 蒙版裁切
    mt.globalCompositeOperation = 'destination-in';
    mt.drawImage(mask, 0, 0);
    mt.globalCompositeOperation = 'source-atop';
    if (mode === 'liquid') {
      mt.save();
      if (st.textColor) mt.globalAlpha = 0.58;
      mt.fillStyle = st.textColor || 'rgba(255,255,255,0.34)';
      mt.fillRect(0, 0, bw, bh);
      mt.restore();
      mt.fillStyle = 'rgba(158,210,255,0.10)';
      mt.fillRect(0, 0, bw, bh);
    } else {
      mt.save();
      if (st.textColor) mt.globalAlpha = 0.76;
      mt.fillStyle = st.textColor || 'rgba(255,255,255,0.66)';
      mt.fillRect(0, 0, bw, bh);
      mt.restore();
      mt.fillStyle = 'rgba(226,238,255,0.14)';
      mt.fillRect(0, 0, bw, bh);
    }

    // 4. 落影后绘制玻璃字形
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,0.34)';
    ctx.shadowBlur = Math.max(1.5, sl * 4.2);
    ctx.shadowOffsetY = sl * 1.0;
    ctx.drawImage(mat, bx, by);
    ctx.restore();

    // 5. 内缘暗部（增加厚度感）
    var inner = makeCanvas(bw, bh);
    var ic = inner.getContext('2d');
    ic.font = n.font;
    ic.fillStyle = 'rgba(0,0,0,0.28)';
    drawTrackedMode(ic, n.text, relX, relY + 1.1 * sl, n.tracking, n.slant, 'fill');
    ic.globalCompositeOperation = 'destination-in';
    ic.drawImage(mask, 0, 0);
    ctx.save();
    ctx.globalAlpha = mode === 'liquid' ? 0.55 : 0.35;
    ctx.drawImage(inner, bx, by);
    ctx.restore();

    // 6. 高光描边（只保留字形内侧一半）
    var edge = makeCanvas(bw, bh);
    var ec = edge.getContext('2d');
    ec.font = n.font;
    ec.strokeStyle = mode === 'liquid' ? 'rgba(255,255,255,0.92)' : 'rgba(255,255,255,0.6)';
    drawTrackedMode(ec, n.text, relX, relY, n.tracking, n.slant, 'stroke',
      (mode === 'liquid' ? 2.6 : 2.2) * sl);
    ec.globalCompositeOperation = 'destination-in';
    ec.drawImage(mask, 0, 0);
    ctx.drawImage(edge, bx, by);
  }

  /** 绘制文字层（玻璃已由 WebGL 渲染完成） */
  function draw(ctx, lay, st) {
    if (!lay || !lay.hasContent) return;

    var shadowBlur = Math.max(1.5, lay.u * 4.4);
    var u = lay.u;

    // 机型名（材质可选）
    if (lay.name.text) drawName(ctx, lay, st);

    // 副标题（品牌色圆点 + 弱化文字）
    if (lay.sub.on) {
      var s = lay.sub;
      var dotY = s.baseline - s.size * 0.30;
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.35)';
      ctx.shadowBlur = shadowBlur * 0.6;
      ctx.fillStyle = lay.colors.accent;
      ctx.beginPath();
      ctx.arc(s.x + s.dotR, dotY, s.dotR, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();

      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.38)';
      ctx.shadowBlur = shadowBlur * 0.7;
      ctx.fillStyle = st.textColor || 'rgba(255,255,255,0.72)';
      ctx.font = s.font;
      drawTracked(ctx, s.text, s.x + s.dotR * 2 + s.dotGap, s.baseline, s.tracking, s.slant);
      ctx.restore();
    }

    // 右侧参数
    if (lay.params.on) {
      var p = lay.params;
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.38)';
      ctx.shadowBlur = shadowBlur * 0.7;
      ctx.fillStyle = st.textColor || 'rgba(255,255,255,0.90)';
      ctx.font = p.font;
      var pw = trackedWidth(ctx, p.text, p.font, p.tracking);
      drawTracked(ctx, p.text, p.x - pw, p.baseline, p.tracking, p.slant);
      ctx.restore();
    }

    if (lay.meta.on) {
      var m = lay.meta;
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.38)';
      ctx.shadowBlur = shadowBlur * 0.6;
      ctx.fillStyle = st.textColor || 'rgba(255,255,255,0.62)';
      ctx.font = m.font;
      var mw = trackedWidth(ctx, m.text, m.font, m.tracking);
      drawTracked(ctx, m.text, m.x - mw, m.baseline, m.tracking, m.slant);
      ctx.restore();
    }

    // 品牌标识
    if (lay.logo.on) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.36)';
      ctx.shadowBlur = u * 5;
      ctx.shadowOffsetY = u * 1;
      global.LG.Logos.draw(ctx, lay.logo.id, lay.logo.x, lay.logo.y, lay.logo.h, 'rgba(255,255,255,0.96)');
      ctx.restore();
    }
  }

  global.LG = global.LG || {};
  global.LG.Watermark = { layout: layout, draw: draw, stacks: STACKS };
})(window);
