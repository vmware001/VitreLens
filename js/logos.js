/* ═══════════════════════════════════════════════════════════
   logos.js — 相机品牌标识绘制（纯 Canvas 矢量，随分辨率缩放）
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var SANS = '"Helvetica Neue", "Segoe UI", Arial, "PingFang SC", sans-serif';
  var GROTESK = '"Bahnschrift", "Arial Narrow", "Helvetica Neue", Arial, sans-serif';
  var SERIF = '"Bodoni MT", Didot, Georgia, "Times New Roman", serif';
  var SCRIPT = '"Segoe Script", "Brush Script MT", Georgia, serif';

  var BRANDS = [
    { id: 'leica', label: '徕卡', accent: '#E8402F' },
    { id: 'hasselblad', label: '哈苏', accent: '#E3A45C' },
    { id: 'zeiss', label: '蔡司', accent: '#3D7BE0' },
    { id: 'ricoh', label: '理光', accent: '#E23B3B' },
    { id: 'sony', label: '索尼', accent: '#FFFFFF' },
    { id: 'canon', label: '佳能', accent: '#E2373F' },
    { id: 'nikon', label: '尼康', accent: '#FFD400' },
    { id: 'fujifilm', label: '富士', accent: '#E60012' },
    { id: 'apple', label: 'Apple', accent: '#FFFFFF' },
    { id: 'none', label: '无', accent: '#FFFFFF' }
  ];

  /* ── 小工具 ── */

  function capBaseline(ctx, h, top) {
    var m = ctx.measureText('H');
    var asc = m.actualBoundingBoxAscent || h * 0.72;
    var desc = m.actualBoundingBoxDescent || 0;
    return top + (h - (asc + desc)) / 2 + asc;
  }

  /** 逐字绘制以获得精确字距，返回总宽度 */
  function tracked(ctx, text, x, y, tracking, align) {
    var chars = String(text).split('');
    var widths = [], total = 0, i;
    for (i = 0; i < chars.length; i++) {
      widths.push(ctx.measureText(chars[i]).width);
      total += widths[i];
    }
    total += tracking * Math.max(0, chars.length - 1);

    var cx = align === 'center' ? x - total / 2 : align === 'right' ? x - total : x;
    var prev = ctx.textAlign;
    ctx.textAlign = 'left';
    for (i = 0; i < chars.length; i++) {
      ctx.fillText(chars[i], cx, y);
      cx += widths[i] + tracking;
    }
    ctx.textAlign = prev;
    return total;
  }

  function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  /* ── 各品牌绘制器 ──
     每个绘制器接收 (ctx, x, top, h, color)，x 为左端、top 为顶部，
     h 为标识高度；绘制器自身也需要在 measure 时设定相同字体。 */

  var PAINTERS = {
    leica: {
      width: function (h) { return h * 1.02; },
      font: function (h) { return 'italic 700 ' + (h * 0.40).toFixed(2) + 'px ' + SCRIPT; },
      draw: function (ctx, x, top, h, color) {
        var cx = x + h * 0.51, cy = top + h * 0.5, r = h * 0.5;
        ctx.fillStyle = '#D6001C';
        ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fill();

        ctx.font = this.font(h);
        ctx.fillStyle = '#FFFFFF';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('Leica', cx, cy + h * 0.015);
        ctx.textAlign = 'left';
        ctx.textBaseline = 'alphabetic';
      }
    },

    hasselblad: {
      width: function (h) { return h * 0.78; },
      font: function () { return ''; },
      draw: function (ctx, x, top, h, color) {
        var w = h * 0.78;
        var bw = w * 0.215;          // 竖笔画宽度
        ctx.save();
        ctx.translate(x, top);
        ctx.transform(1, 0, -0.13, 1, 0, 0);   // 斜体切变
        ctx.fillStyle = color || '#FFFFFF';

        // 左竖
        ctx.beginPath();
        ctx.moveTo(0, 0); ctx.lineTo(bw, 0);
        ctx.lineTo(bw, h); ctx.lineTo(0, h);
        ctx.closePath(); ctx.fill();

        // 右竖
        ctx.beginPath();
        ctx.moveTo(w - bw, 0); ctx.lineTo(w, 0);
        ctx.lineTo(w, h); ctx.lineTo(w - bw, h);
        ctx.closePath(); ctx.fill();

        // 斜横梁（右高左低，略超出竖笔画）
        var t = h * 0.155;
        var yL = h * 0.585, yR = h * 0.435;
        var x0 = -bw * 0.05, x1 = w + bw * 0.05;
        ctx.beginPath();
        ctx.moveTo(x0, yL); ctx.lineTo(x1, yR);
        ctx.lineTo(x1, yR + t); ctx.lineTo(x0, yL + t);
        ctx.closePath(); ctx.fill();

        ctx.restore();
      }
    },

    zeiss: {
      width: function (h) { return h * 1.0; },
      font: function (h) { return '700 ' + (h * 0.222).toFixed(2) + 'px ' + SANS; },
      draw: function (ctx, x, top, h, color) {
        var w = h;
        ctx.save();
        ctx.shadowColor = 'rgba(0,0,0,0.28)';
        ctx.shadowBlur = h * 0.16;
        ctx.shadowOffsetY = h * 0.03;
        ctx.fillStyle = '#0B4EA2';
        roundRect(ctx, x, top, w, h, h * 0.13);
        ctx.fill();
        ctx.restore();

        ctx.font = this.font(h);
        ctx.fillStyle = '#FFFFFF';
        ctx.textBaseline = 'alphabetic';
        var bl = capBaseline(ctx, h, top);
        tracked(ctx, 'ZEISS', x + w / 2, bl, h * 0.012, 'center');
      }
    },

    ricoh: {
      width: function (h) { return h * 1.66; },
      font: function (h) { return '700 ' + (h * 0.44).toFixed(2) + 'px ' + GROTESK; },
      draw: function (ctx, x, top, h, color) {
        ctx.font = this.font(h);
        ctx.fillStyle = color || '#FFFFFF';
        var bl = capBaseline(ctx, h, top);
        tracked(ctx, 'RICOH', x, bl, h * 0.075, 'left');
      }
    },

    sony: {
      width: function (h) { return h * 1.58; },
      font: function (h) { return '700 ' + (h * 0.42).toFixed(2) + 'px ' + SANS; },
      draw: function (ctx, x, top, h, color) {
        ctx.font = this.font(h);
        ctx.fillStyle = color || '#FFFFFF';
        var bl = capBaseline(ctx, h, top);
        tracked(ctx, 'SONY', x, bl, h * 0.10, 'left');
      }
    },

    canon: {
      width: function (h) { return h * 1.48; },
      font: function (h) { return 'italic 700 ' + (h * 0.50).toFixed(2) + 'px ' + SERIF; },
      draw: function (ctx, x, top, h, color) {
        ctx.font = this.font(h);
        ctx.fillStyle = color || '#FFFFFF';
        var bl = capBaseline(ctx, h, top);
        ctx.fillText('Canon', x, bl);
      }
    },

    nikon: {
      width: function (h) { return h * 1.42; },
      font: function (h) { return '600 ' + (h * 0.46).toFixed(2) + 'px ' + SERIF; },
      draw: function (ctx, x, top, h, color) {
        ctx.font = this.font(h);
        ctx.fillStyle = color || '#FFFFFF';
        var bl = capBaseline(ctx, h, top);
        ctx.fillText('Nikon', x, bl);
      }
    },

    fujifilm: {
      width: function (h) { return h * 1.84; },
      font: function (h) { return '700 ' + (h * 0.32).toFixed(2) + 'px ' + SANS; },
      draw: function (ctx, x, top, h, color) {
        ctx.fillStyle = color || '#FFFFFF';
        var bl = capBaseline(ctx, h, top);
        ctx.font = this.font(h);
        var tw = tracked(ctx, 'FUJIFILM', x + h * 0.24, bl, h * 0.03, 'left');
        ctx.fillStyle = '#E60012';
        roundRect(ctx, x, top + h * 0.36, h * 0.16, h * 0.16, h * 0.03);
        ctx.fill();
        void tw;
      }
    },

    apple: {
      width: function (h) { return h * 0.84; },
      font: function () { return ''; },
      draw: function (ctx, x, top, h, color) {
        var w = h * 0.84;
        var cw = Math.max(16, Math.ceil(w));
        var ch = Math.max(16, Math.ceil(h));
        var off = document.createElement('canvas');
        off.width = cw;
        off.height = ch;
        var o = off.getContext('2d');
        o.scale(cw, ch);
        o.fillStyle = color || '#FFFFFF';

        o.beginPath();
        o.moveTo(0.50, 0.305);
        o.bezierCurveTo(0.445, 0.245, 0.335, 0.245, 0.283, 0.315);
        o.bezierCurveTo(0.196, 0.415, 0.208, 0.615, 0.271, 0.775);
        o.bezierCurveTo(0.318, 0.885, 0.408, 0.955, 0.50, 0.955);
        o.bezierCurveTo(0.592, 0.955, 0.682, 0.885, 0.729, 0.775);
        o.bezierCurveTo(0.792, 0.615, 0.804, 0.415, 0.717, 0.315);
        o.bezierCurveTo(0.665, 0.245, 0.555, 0.245, 0.50, 0.305);
        o.closePath();
        o.fill();

        o.globalCompositeOperation = 'destination-out';
        o.beginPath();
        o.arc(0.855, 0.560, 0.205, 0, Math.PI * 2);
        o.fill();

        o.globalCompositeOperation = 'source-over';
        o.beginPath();
        o.ellipse(0.566, 0.208, 0.128, 0.050, -0.60, 0, Math.PI * 2);
        o.fill();

        ctx.drawImage(off, x, top, w, h);
      }
    },

    none: {
      width: function () { return 0; },
      font: function () { return ''; },
      draw: function () { }
    }
  };

  function get(id) {
    return PAINTERS[id] ? id : 'none';
  }

  function measure(ctx, id, h) {
    var p = PAINTERS[get(id)];
    return p.width(h);
  }

  function draw(ctx, id, x, top, h, color) {
    var p = PAINTERS[get(id)];
    if (!p || !h) return 0;
    ctx.save();
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
    p.draw(ctx, x, top, h, color);
    ctx.restore();
    return p.width(h);
  }

  global.LG = global.LG || {};
  global.LG.Logos = { list: BRANDS, draw: draw, measure: measure, accent: function (id) {
    for (var i = 0; i < BRANDS.length; i++) if (BRANDS[i].id === id) return BRANDS[i].accent;
    return '#FFFFFF';
  } };
})(window);
