/* ═══════════════════════════════════════════════════════════
   exif.js — 轻量 JPEG EXIF 解析（无依赖）
   读取机型 / 光圈 / 快门 / ISO / 焦距 / 拍摄时间 / GPS
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var TYPE_SIZE = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8 };

  var T = {
    Make: 0x010F, Model: 0x0110, Orientation: 0x0112,
    DateTime: 0x0132, ExifIFD: 0x8769, GPSIFD: 0x8825
  };
  var E = {
    ExposureTime: 0x829A, FNumber: 0x829D, ISO: 0x8827, ISO2: 0x8833,
    DateTimeOriginal: 0x9003, DateTimeDigitized: 0x9004,
    FocalLength: 0x920A, Focal35: 0xA405, LensModel: 0xA434,
    PixelX: 0xA002, PixelY: 0xA003
  };
  var G = { LatRef: 1, Lat: 2, LonRef: 3, Lon: 4, Alt: 6 };

  function readValue(view, off, type, count, little) {
    var i, out = [];
    switch (type) {
      case 2: { // ASCII
        var s = '';
        for (i = 0; i < count; i++) {
          var c = view.getUint8(off + i);
          if (c === 0) break;
          s += String.fromCharCode(c);
        }
        return s.trim();
      }
      case 3: // SHORT
        for (i = 0; i < count; i++) out.push(view.getUint16(off + i * 2, little));
        return count === 1 ? out[0] : out;
      case 4: // LONG
        for (i = 0; i < count; i++) out.push(view.getUint32(off + i * 4, little));
        return count === 1 ? out[0] : out;
      case 5: // RATIONAL
        for (i = 0; i < count; i++) {
          var n = view.getUint32(off + i * 8, little);
          var d = view.getUint32(off + i * 8 + 4, little);
          out.push(d === 0 ? 0 : n / d);
        }
        return count === 1 ? out[0] : out;
      case 9: // SLONG
        for (i = 0; i < count; i++) out.push(view.getInt32(off + i * 4, little));
        return count === 1 ? out[0] : out;
      case 10: // SRATIONAL
        for (i = 0; i < count; i++) {
          var sn = view.getInt32(off + i * 8, little);
          var sd = view.getInt32(off + i * 8 + 4, little);
          out.push(sd === 0 ? 0 : sn / sd);
        }
        return count === 1 ? out[0] : out;
      default: { // BYTE / UNDEFINED / FLOAT …
        for (i = 0; i < count; i++) out.push(view.getUint8(off + i));
        return count === 1 ? out[0] : out;
      }
    }
  }

  function readIFD(view, dir, tiff, little) {
    var tags = {};
    if (dir < 8 || dir + 2 > view.byteLength) return tags;
    var n = view.getUint16(dir, little);
    if (n > 512) return tags;
    for (var i = 0; i < n; i++) {
      var e = dir + 2 + i * 12;
      if (e + 12 > view.byteLength) break;
      var tag = view.getUint16(e, little);
      var type = view.getUint16(e + 2, little);
      var count = view.getUint32(e + 4, little);
      var unit = TYPE_SIZE[type];
      if (!unit) continue;
      var size = unit * count;
      var dataOff = size > 4 ? tiff + view.getUint32(e + 8, little) : e + 8;
      if (dataOff + size > view.byteLength) continue;
      try { tags[tag] = readValue(view, dataOff, type, count, little); } catch (err) { /* skip */ }
    }
    return tags;
  }

  function readTiff(view, tiff) {
    if (tiff + 8 > view.byteLength) return null;
    var order = view.getUint16(tiff, false);
    if (order !== 0x4949 && order !== 0x4D4D) return null;
    var little = order === 0x4949;

    var ifd0Off = view.getUint32(tiff + 4, little);
    var ifd0 = readIFD(view, tiff + ifd0Off, tiff, little);

    var exif = ifd0[T.ExifIFD] ? readIFD(view, tiff + ifd0[T.ExifIFD], tiff, little) : {};
    var gps = ifd0[T.GPSIFD] ? readIFD(view, tiff + ifd0[T.GPSIFD], tiff, little) : {};

    var out = {
      make: str(ifd0[T.Make]),
      model: str(ifd0[T.Model]),
      lens: str(exif[E.LensModel]),
      fNumber: sane(ratio(exif[E.FNumber]), 0.7, 64),
      exposure: sane(ratio(exif[E.ExposureTime]), 0.00001, 120),
      iso: sane(num(exif[E.ISO]) || num(exif[E.ISO2]), 10, 409600),
      focal: sane(ratio(exif[E.FocalLength]), 0.5, 3000),
      focal35: sane(ratio(exif[E.Focal35]), 1, 5000),
      date: str(exif[E.DateTimeOriginal]) || str(exif[E.DateTimeDigitized]) || str(ifd0[T.DateTime]),
      width: num(exif[E.PixelX]),
      height: num(exif[E.PixelY]),
      gps: null
    };

    var latDeg = dmsValue(gps[G.Lat]);
    var lonDeg = dmsValue(gps[G.Lon]);
    var hasCoord = Math.abs(latDeg) <= 90 && Math.abs(lonDeg) <= 180 &&
                   !(Math.abs(latDeg) < 0.0001 && Math.abs(lonDeg) < 0.0001);
    if (hasCoord) {
      var lat = dms(gps[G.Lat], str(gps[G.LatRef]) || (latDeg < 0 ? 'S' : 'N'));
      var lon = dms(gps[G.Lon], str(gps[G.LonRef]) || (lonDeg < 0 ? 'W' : 'E'));
      if (lat && lon) out.gps = { lat: lat, lon: lon, text: lat + ' ' + lon };
    }

    return out;
  }

  function str(v) {
    return typeof v === 'string' ? v.replace(/\u0000/g, '').trim() : '';
  }
  function num(v) {
    return typeof v === 'number' && isFinite(v) && v > 0 ? v : 0;
  }

  /** 合理性校验，避免把错误字段写进水印 */
  function sane(v, min, max) {
    return typeof v === 'number' && isFinite(v) && v >= min && v <= max ? v : 0;
  }

  /** 兼容部分软件把 RATIONAL 写成 SHORT[2] 的情况 */
  function ratio(v) {
    if (typeof v === 'number') return v;
    if (Array.isArray(v)) {
      if (v.length >= 2) return v[1] ? v[0] / v[1] : 0;
      if (v.length === 1) return v[0];
    }
    return 0;
  }

  /** [deg, min, sec] + 半球 → 39°34'37"N */
  function dmsValue(arr) {
    if (!Array.isArray(arr) || arr.length < 2) return 999;
    var d = arr[0] || 0, m = arr[1] || 0, sec = arr.length > 2 ? (arr[2] || 0) : 0;
    var v = d + m / 60 + sec / 3600;
    // 分/秒越界视为无效
    if (m < 0 || m >= 60 || sec < 0 || sec >= 60) return 999;
    return v;
  }

  function dms(arr, ref) {
    if (!Array.isArray(arr) || arr.length < 2) return '';
    var d = Math.floor(arr[0]);
    var m = Math.floor(arr[1]);
    var s = arr.length > 2 ? Math.round(arr[2]) : 0;
    if (s === 60) { s = 0; m += 1; }
    if (m === 60) { m = 0; d += 1; }
    var r = (ref || '').toUpperCase().charAt(0);
    if (r !== 'N' && r !== 'S' && r !== 'E' && r !== 'W') r = '';
    return d + '°' + pad(m) + "'" + pad(s) + '"' + r;
  }
  function pad(n) { return n < 10 ? '0' + n : String(n); }

  /** 解析 ArrayBuffer；同时探测 HDR 增益图 */
  function parse(buffer) {
    try {
      var view = new DataView(buffer);
      if (view.byteLength < 4 || view.getUint16(0, false) !== 0xFFD8) return null;

      var exifOff = -1;
      var hdr = false;
      var xmp = null;
      var gainSeg = null;
      var metaSegs = [];
      var mpf = null;
      var sosOff = -1;
      var off = 2;

      while (off + 4 <= view.byteLength) {
        if (view.getUint8(off) !== 0xFF) { off++; continue; }
        var code = view.getUint8(off + 1);
        if (code === 0xD8 || code === 0x01 || (code >= 0xD0 && code <= 0xD7)) { off += 2; continue; }
        if (code === 0xDA) { sosOff = off; break; }
        if (code === 0xD9) break;
        var len = view.getUint16(off + 2, false);
        if (len < 2) break;

        if (code === 0xE1) {
          if (off + 10 <= view.byteLength &&
              view.getUint32(off + 4, false) === 0x45786966 &&
              exifOff < 0) {
            exifOff = off + 10;
            metaSegs.push(copySeg(view, off, len));
          }
          if (!xmp && isXmp(view, off, len)) {
            xmp = readXmp(view, off, len);
            metaSegs.push(copySeg(view, off, len));
          }
          if (!hdr && detectHdr(view, off, len)) hdr = true;
        } else if (code === 0xE2) {
          if (isMpf(view, off, len)) {
            var mpfBytes = copySeg(view, off, len);
            mpf = parseMpf(mpfBytes);
            metaSegs.push(mpfBytes);
          }
          if (!hdr && hasGainMap(view, off, len)) hdr = true;
          if (!gainSeg && isGainMarker(view, off, len)) {
            gainSeg = copySeg(view, off, len);
            metaSegs.push(gainSeg);
          }
        }
        off += 2 + len;
      }

      if (exifOff < 0) {
        if (!hdr && !xmp) return null;
        return { hdr: hdr, make: '', model: '', lens: '', fNumber: 0, exposure: 0,
                 iso: 0, focal: 0, focal35: 0, date: '', width: 0, height: 0, gps: null,
                 motion: readMotion(view, sosOff, xmp) };
      }
      var parsed = readTiff(view, exifOff);
      if (parsed) {
        parsed.hdr = hdr;
        parsed.motion = readMotion(view, sosOff, xmp);
        if (parsed.motion) {
          parsed.motion.hdrSeg = gainSeg;
          parsed.motion.metaSegs = metaSegs;
          parsed.motion.mpf = mpf;
        }
      }
      return parsed;
    } catch (err) {
      return null;
    }
  }

  /* ── HEIF / HEIC：从 meta 容器里取 Exif 数据项 ── */

  function boxAt(view, off, end) {
    if (off + 8 > end) return null;
    var size = view.getUint32(off, false);
    var type = '';
    for (var i = 0; i < 4; i++) type += String.fromCharCode(view.getUint8(off + 4 + i));
    var header = 8;
    if (size === 1) {
      if (off + 16 > end) return null;
      size = view.getUint32(off + 8, false) * 4294967296 + view.getUint32(off + 12, false);
      header = 16;
    } else if (size === 0) {
      size = end - off;
    }
    if (size < header) return null;
    return { start: off, end: Math.min(end, off + size), header: header, type: type };
  }

  function findBox(view, start, end, type) {
    var p = start;
    while (p + 8 <= end) {
      var b = boxAt(view, p, end);
      if (!b) return null;
      if (b.type === type) return b;
      if (b.end <= p) return null;
      p = b.end;
    }
    return null;
  }

  function readN(view, p, n) {
    var v = 0;
    for (var i = 0; i < n; i++) v = v * 256 + view.getUint8(p + i);
    return v;
  }

  function readIinf(view, box) {
    var p = box.start + box.header;
    var version = view.getUint8(p);
    p += 4;
    var count = version === 0 ? view.getUint16(p, false) : view.getUint32(p, false);
    p += version === 0 ? 2 : 4;

    var ids = [];
    var end = box.end;
    for (var i = 0; i < count && p + 8 <= end; i++) {
      var b = boxAt(view, p, end);
      if (!b) break;
      if (b.type === 'infe') {
        var q = b.start + b.header;
        var v = view.getUint8(q);
        q += 4;
        var id = v >= 3 ? view.getUint32(q, false) : view.getUint16(q, false);
        q += v >= 3 ? 4 : 2;
        q += 2;
        var t = '';
        for (var k = 0; k < 4 && q + k < b.end; k++) t += String.fromCharCode(view.getUint8(q + k));
        if (t === 'Exif') ids.push(id);
      }
      if (b.end <= p) break;
      p = b.end;
    }
    return ids;
  }

  function readIloc(view, box, targets) {
    var p = box.start + box.header;
    var version = view.getUint8(p);
    p += 4;
    var b1 = view.getUint8(p++);
    var offsetSize = b1 >> 4, lengthSize = b1 & 15;
    var b2 = view.getUint8(p++);
    var baseOffsetSize = b2 >> 4;
    var indexSize = (version === 1 || version === 2) ? (b2 & 15) : 0;
    var count = version < 2 ? view.getUint16(p, false) : view.getUint32(p, false);
    p += version < 2 ? 2 : 4;

    var out = {};
    for (var i = 0; i < count && p < box.end; i++) {
      var id = version < 2 ? view.getUint16(p, false) : view.getUint32(p, false);
      p += version < 2 ? 2 : 4;
      var method = 0;
      if (version === 1 || version === 2) { method = view.getUint16(p, false) & 15; p += 2; }
      p += 2; // data_reference_index
      var base = readN(view, p, baseOffsetSize); p += baseOffsetSize;
      var extents = view.getUint16(p, false); p += 2;

      for (var e = 0; e < extents; e++) {
        if (indexSize > 0) p += indexSize;
        var eOff = readN(view, p, offsetSize); p += offsetSize;
        var eLen = readN(view, p, lengthSize); p += lengthSize;
        if (targets.indexOf(id) >= 0 && method === 0 && !out[id]) {
          out[id] = { offset: base + eOff, length: eLen };
        }
      }
    }
    return out;
  }

  /** 解析 HEIC/HEIF 的 EXIF（TIFF 块） */
  function parseHeif(buffer) {
    try {
      var view = new DataView(buffer);
      var ftyp = boxAt(view, 0, view.byteLength);
      if (!ftyp || ftyp.type !== 'ftyp') return null;

      var meta = findBox(view, 0, view.byteLength, 'meta');
      if (!meta) return null;

      var childStart = meta.start + meta.header + 4;   // FullBox: version + flags
      var iinf = findBox(view, childStart, meta.end, 'iinf');
      var iloc = findBox(view, childStart, meta.end, 'iloc');
      if (!iinf || !iloc) return null;

      var ids = readIinf(view, iinf);
      if (!ids.length) return null;
      var locs = readIloc(view, iloc, ids);
      if (!locs[ids[0]]) return null;

      var loc = locs[ids[0]];
      if (loc.offset + loc.length > view.byteLength || loc.length < 12) return null;

      var tiffRel = view.getUint32(loc.offset, false);
      var tiffStart = loc.offset + 4 + tiffRel;
      if (tiffStart + 8 > view.byteLength) return null;

      var parsed = readTiff(view, tiffStart);
      if (parsed) parsed.hdr = false;
      return parsed;
    } catch (err) {
      return null;
    }
  }

  var XMP_HEAD = 'http://ns.adobe.com/xap/1.0/';

  function isXmp(view, off, len) {
    if (len < XMP_HEAD.length + 3) return false;
    for (var i = 0; i < XMP_HEAD.length; i++) {
      if (view.getUint8(off + 4 + i) !== XMP_HEAD.charCodeAt(i)) return false;
    }
    return true;
  }

  function readXmp(view, off, len) {
    var start = off + 4 + XMP_HEAD.length + 1;
    var end = Math.min(view.byteLength, off + 2 + len - 2);
    if (end <= start) return null;
    var bytes = new Uint8Array(end - start);
    for (var i = 0; i < bytes.length; i++) bytes[i] = view.getUint8(start + i);
    return bytes;
  }

  /**
  /**
   * 动态照片（Motion Photo / Live Photo）识别
   * JPEG[SOI..EOI] 之后追加若干数据段：
   *   - Google / OPPO / vivo：XMP 的 Container:Directory 按语义列出各段长度
   *   - 老版 Google：GCamera:MicroVideoOffset（以文件末尾为基准）
   *   - 少数机型：尾部直接是 MP4
   */
  function readMotion(view, sosOff, xmpBytes) {
    if (sosOff < 0 || sosOff + 4 >= view.byteLength) return null;

    var eoi = -1;
    for (var i = sosOff; i < view.byteLength - 1; i++) {
      if (view.getUint8(i) === 0xFF && view.getUint8(i + 1) === 0xD9) { eoi = i; break; }
    }
    if (eoi < 0) return null;

    var trailerStart = eoi + 2;
    var trailerLen = view.byteLength - trailerStart;
    if (trailerLen < 512) return null;

    var xmpText = '';
    if (xmpBytes) {
      for (var k = 0; k < xmpBytes.length; k++) xmpText += String.fromCharCode(xmpBytes[k]);
    }
    if (!/MotionPhoto|MicroVideo|MotionPhoto_Data/i.test(xmpText)) return null;

    // ① Container 目录
    var items = [];
    var blockRe = /<Container:Item[\s\S]*?\/?>/g;
    var bm;
    while ((bm = blockRe.exec(xmpText))) {
      var block = bm[0];
      var sm = /Item:Semantic="([^"]+)"/.exec(block);
      var lm = /Item:Length="(\d+)"/.exec(block);
      items.push({ semantic: sm ? sm[1] : '', length: lm ? parseInt(lm[1], 10) : 0 });
    }

    var declared = 0;
    for (var i2 = 0; i2 < items.length; i2++) {
      if (items[i2].semantic.toLowerCase() === 'primary') continue;
      declared += items[i2].length;
    }

    var mOffset = xmpText.match(/[A-Za-z]+:MicroVideoOffset="(\d+)"/);
    var offset = mOffset ? parseInt(mOffset[1], 10) : null;

    var valid = false;
    var style = 'unknown';

    if (declared > 0) {
      style = 'container';
      valid = declared === trailerLen;
    } else if (offset !== null) {
      style = 'microvideo';
      valid = (view.byteLength - offset) === trailerStart;
    } else if (trailerStart + 8 <= view.byteLength &&
               view.getUint8(trailerStart + 4) === 0x66 && view.getUint8(trailerStart + 5) === 0x74) {
      style = 'bare-mp4';
      valid = true;
    }

    var trailer = null;
    if (valid) {
      trailer = new Uint8Array(trailerLen);
      for (var v = 0; v < trailerLen; v++) trailer[v] = view.getUint8(trailerStart + v);
    }

    return {
      style: style,
      items: items,
      declared: declared,
      offset: offset,
      trailerStart: trailerStart,
      trailerLength: trailerLen,
      preserves: valid,
      xmp: xmpBytes,
      trailer: trailer
    };
  }


  function copySeg(view, off, len) {
    var out = new Uint8Array(2 + len);
    for (var i = 0; i < out.length; i++) out[i] = view.getUint8(off + i);
    return out;
  }

  function segHeadIs(view, off, len, tag) {
    if (len < tag.length + 3) return false;
    for (var i = 0; i < tag.length; i++) {
      if (view.getUint8(off + 4 + i) !== tag.charCodeAt(i)) return false;
    }
    return true;
  }

  function isMpf(view, off, len) { return segHeadIs(view, off, len, 'MPF\x00'); }
  function isIcc(view, off, len) { return segHeadIs(view, off, len, 'ICC_PROFILE\x00'); }

  /** 解析 MPF 段（段内相对位置），用于导出时修补副图偏移 */
  function parseMpf(seg) {
    var tiff = 8;                     // FF E2 + len + "MPF\0"
    if (seg.length < tiff + 8) return null;
    var le = seg[tiff] === 0x49 && seg[tiff + 1] === 0x49;
    function u16(o) { return le ? (seg[o] | (seg[o + 1] << 8)) : ((seg[o] << 8) | seg[o + 1]); }
    function u32(o) {
      return le
        ? (seg[o] | (seg[o + 1] << 8) | (seg[o + 2] << 16) | (seg[o + 3] << 24)) >>> 0
        : ((seg[o] << 24) | (seg[o + 1] << 16) | (seg[o + 2] << 8) | seg[o + 3]) >>> 0;
    }

    var ifd = tiff + u32(tiff + 4);
    if (ifd + 2 > seg.length) return null;
    var n = u16(ifd);
    var entries = [];

    for (var k = 0; k < n; k++) {
      var e = ifd + 2 + k * 12;
      if (e + 12 > seg.length) break;
      var tag = u16(e);
      if (tag !== 0xB002) continue;
      var count = u32(e + 4);
      var arr = tiff + u32(e + 8);
      var imgs = Math.floor(count / 16);
      for (var m = 0; m < imgs; m++) {
        entries.push({
          sizePos: arr + m * 16 + 4,
          offPos: arr + m * 16 + 8,
          size: u32(arr + m * 16 + 4)
        });
      }
    }
    return { little: le, tiff: tiff, entries: entries };
  }

  /** APP2 是否是以 urn:iso:std:iso 开头的增益图标记段 */
  function isGainMarker(view, off, len) {
    var tag = 'urn:iso:std:iso';
    if (len < tag.length + 3) return false;
    for (var i = 0; i < tag.length; i++) {
      if (view.getUint8(off + 4 + i) !== tag.charCodeAt(i)) return false;
    }
    return true;
  }

  /** APP2 中是否含 ISO 21496-1 增益图标识 */
  function hasGainMap(view, off, len) {
    var end = Math.min(view.byteLength, off + 2 + len - 2);
    var tag = '21496';
    for (var i = off + 4; i < end - tag.length; i++) {
      var hit = true;
      for (var j = 0; j < tag.length; j++) {
        if (view.getUint8(i + j) !== tag.charCodeAt(j)) { hit = false; break; }
      }
      if (hit) return true;
    }
    return false;
  }

  function detectHdr(view, off, len) {
    var end = Math.min(view.byteLength, off + 2 + len - 2);
    var tag = 'hdrgm';
    for (var i = off + 4; i < end - tag.length; i++) {
      var hit = true;
      for (var j = 0; j < tag.length; j++) {
        if (view.getUint8(i + j) !== tag.charCodeAt(j)) { hit = false; break; }
      }
      if (hit) return true;
    }
    return false;
  }

  async function readFile(file) {
    if (!file) return null;
    var isJpeg = /jpe?g/i.test(file.type) || /\.jpe?g$/i.test(file.name || '');
    var isHeif = /hei[cf]/i.test(file.type) || /\.(heic|heif|hif)$/i.test(file.name || '');
    if (!isJpeg && !isHeif) return null;
    try {
      var buf = await file.arrayBuffer();
      return isHeif && !isJpeg ? parseHeif(buf) : parse(buf);
    } catch (err) {
      return null;
    }
  }

  /* ── 格式化 ── */

  function formatAperture(f) {
    if (!f) return '';
    var v = Math.round(f * 100) / 100;
    return 'ƒ/' + (Number.isInteger(v) ? v.toFixed(1) : String(v));
  }

  function formatShutter(t) {
    if (!t) return '';
    if (t >= 1) {
      var v = Math.round(t * 10) / 10;
      return (Number.isInteger(v) ? v.toFixed(1) : String(v)) + 's';
    }
    return '1/' + Math.round(1 / t) + 's';
  }

  function formatISO(iso) {
    return iso ? 'ISO' + Math.round(iso) : '';
  }

  function formatFocal(mm) {
    return mm ? Math.round(mm) + 'mm' : '';
  }

  function formatDate(raw, style) {
    if (!raw) return '';
    var m = String(raw).match(/(\d{4})[:\-./](\d{2})[:\-./](\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return String(raw);
    var y = m[1], mo = m[2], d = m[3], hh = m[4], mm = m[5], ss = m[6] || '00';
    if (style === 'short') return y + '.' + mo + '.' + d + ' ' + hh + ':' + mm;
    if (style === 'space') return y + ' ' + mo + ' ' + d + ' ' + hh + ':' + mm + ':' + ss;
    return y + '.' + mo + '.' + d + ' ' + hh + ':' + mm + ':' + ss;
  }

  /** 拼接参数行：23mm ƒ/1.8 1/287s ISO50 */
  /** 手机 EXIF 的 FocalLength 是物理焦距（如 6.9mm），水印应显示等效焦距（23mm） */
  function pickFocal(x) {
    var f = x.focal || 0;
    var f35 = x.focal35 || 0;
    if (f35 && (!f || f35 / f >= 2.5)) return f35;
    return f || f35;
  }

  function formatParams(x) {
    if (!x) return '';
    return [
      formatFocal(pickFocal(x)),
      formatAperture(x.fNumber),
      formatShutter(x.exposure),
      formatISO(x.iso)
    ].filter(Boolean).join(' ');
  }

  function detectBrand(make, model) {
    var s = ((make || '') + ' ' + (model || '')).toLowerCase();
    if (!s.trim()) return '';
    if (/leica|xiaomi|redmi/.test(s)) return 'leica';
    if (/hasselblad|oppo|oneplus/.test(s)) return 'hasselblad';
    if (/vivo|zeiss/.test(s)) return 'zeiss';
    if (/ricoh|\bgr\b/.test(s)) return 'ricoh';
    if (/apple|iphone/.test(s)) return 'apple';
    if (/sony/.test(s)) return 'sony';
    if (/canon/.test(s)) return 'canon';
    if (/nikon/.test(s)) return 'nikon';
    if (/fuji/.test(s)) return 'fujifilm';
    return '';
  }

  function deviceName(make, model) {
    make = (make || '').trim();
    model = (model || '').trim();
    if (!model) return make;
    if (!make) return model;
    if (/apple/i.test(make)) return model;                       // iPhone 15 Pro
    if (model.toLowerCase().indexOf(make.toLowerCase()) === 0) return model;
    if (/^[A-Z0-9]{5,12}$/.test(model)) return make;             // 纯型号代码，例如 PJZ110
    return make + ' ' + model;                                   // 例如 SONY ILCE-7M4
  }

  global.LG = global.LG || {};
  global.LG.Exif = {
    parse: parse,
    parseHeif: parseHeif,
    readFile: readFile,
    formatAperture: formatAperture,
    formatShutter: formatShutter,
    formatISO: formatISO,
    formatFocal: formatFocal,
    pickFocal: pickFocal,
    formatDate: formatDate,
    formatParams: formatParams,
    detectBrand: detectBrand,
    deviceName: deviceName
  };
})(window);
