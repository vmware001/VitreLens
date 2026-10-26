/* 同步源码到 nginx，并写入构建号（破坏缓存） */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = 'D:/data/code/liquidglass';
const DEST = 'D:/nginx/html/app/liquidglass';
const ASSETS = ['css/app.css', 'js/app.js', 'js/exif.js', 'js/glass.js', 'js/logos.js', 'js/watermark.js'];

const h = crypto.createHash('md5');
ASSETS.forEach((f) => h.update(fs.readFileSync(path.join(ROOT, f))));
const build = h.digest('hex').slice(0, 7);

let html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
html = html.replace(/href="css\/app\.css(\?v=[^"]*)?"/g, `href="css/app.css?v=${build}"`);
html = html.replace(/src="js\/(app|exif|glass|logos|watermark)\.js(\?v=[^"]*)?"/g, (m, name) => `src="js/${name}.js?v=${build}"`);
html = html.replace(/<span id="buildTag">[^<]*<\/span>/, `<span id="buildTag">${build}</span>`);
function writeRetry(file, data, tries) {
  for (var i = 0; i < (tries || 6); i++) {
    try { fs.writeFileSync(file, data); return; }
    catch (err) { if (i === (tries || 6) - 1) throw err; const end = Date.now() + 400; while (Date.now() < end) {} }
  }
}
writeRetry(path.join(ROOT, 'index.html'), html);

// 逐文件复制，避免 xcopy 对单文件的兼容问题
function copyTree(rel) {
  const src = path.join(ROOT, rel);
  const dst = path.join(DEST, rel);
  const st = fs.statSync(src);
  if (st.isDirectory()) {
    fs.mkdirSync(dst, { recursive: true });
    fs.readdirSync(src).forEach((name) => copyTree(path.join(rel, name)));
  } else {
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.copyFileSync(src, dst);
  }
}
['index.html', 'css', 'js'].forEach(copyTree);

// 兜底校验：确保部署的是应用页面而不是其它文件
const deployed = fs.readFileSync(path.join(DEST, 'index.html'), 'utf8');
if (!deployed.includes('VitreLens') || deployed.length < 10000 || deployed.length > 100000) {
  console.error('ERROR: 部署的 index.html 校验失败', deployed.length);
  process.exit(1);
}
console.log('build', build, '→ synced', deployed.length + 'B');
