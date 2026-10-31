#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 js/glass.js 里的 VERT / FRAG 着色器数组导出成 Android res/raw/*.glsl。
网页版是唯一真源：改玻璃效果只改 js/glass.js，这里重新导出即可，避免两处漂移。

注意：解析必须允许行尾 // 注释，否则 'uniform vec4  u_panel;', // x,y,w,h
这类行会被漏掉，导致 GLSL 用了未声明的 uniform（编译失败 → 玻璃整个消失）。
"""
import re
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'js', 'glass.js')
OUT_DIR = os.path.join(ROOT, 'android', 'app', 'res', 'raw')

LINE_RE = re.compile(r"^\s*'((?:[^'\\]|\\.)*)'\s*,?\s*(?://.*)?$")


def collect(lines, start_marker):
    """从 start_marker 行开始，收集连续的字符串数组元素，直到 ].join"""
    out = []
    started = False
    for ln in lines:
        if not started:
            if start_marker in ln:
                started = True
            continue
        if '].join' in ln:
            break
        m = LINE_RE.match(ln)
        if m:
            s = m.group(1).replace("\\'", "'").replace('\\\\', '\\')
            out.append(s)
    return out


def main():
    if not os.path.isfile(SRC):
        sys.exit('找不到 %s' % SRC)
    source = open(SRC, encoding='utf-8').read()
    lines = source.splitlines()
    # 新版网页使用 JS 模板字符串；旧版是单引号数组。两种格式都支持，
    # 这样网页与 Android 可以继续共享同一份 GLSL，而不必复制维护。
    vm = re.search(r"var\s+VERT\s*=\s*'([^']*)'\s*;", source)
    fm = re.search(r'var\s+FRAG\s*=\s*`([\s\S]*?)`\s*;', source)
    if vm and fm:
        vert = [vm.group(1)]
        frag = fm.group(1).splitlines()
    else:
        vert = collect(lines, 'var VERT = [')
        frag = collect(lines, 'var FRAG = [')
    if not vert or not frag:
        sys.exit('解析着色器数组失败')

    os.makedirs(OUT_DIR, exist_ok=True)
    for name, body in (('glass_vert.glsl', vert), ('glass_frag.glsl', frag)):
        path = os.path.join(OUT_DIR, name)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(''.join(s + '\n' for s in body))
        print('  %-18s %3d 行' % (name, len(body)))

    # 自检：uniform 使用必须都有声明
    frag_text = '\n'.join(frag)
    decl = set(re.findall(r'uniform\s+\w+\s+(\w+)\s*;', frag_text))
    used = set(re.findall(r'\bu_[a-zA-Z_]\w*', frag_text))
    missing = sorted(used - decl)
    if missing:
        sys.exit('!! 着色器缺少 uniform 声明: %s' % ', '.join(missing))
    print('  uniform 自检通过 (%d 个)' % len(decl))


if __name__ == '__main__':
    main()
