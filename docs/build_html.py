#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把本项目的手写 Markdown 报告转成自包含的暗色 HTML。"""
import html
import re
import sys


def inline(text: str) -> str:
    codes = []

    def stash(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", stash, text)
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    for i, c in enumerate(codes):
        text = text.replace(f"\x00{i}\x00", "<code>" + html.escape(c, quote=False) + "</code>")
    return text


def convert(md: str) -> str:
    lines = md.split("\n")
    out = []
    i = 0
    n = len(lines)
    list_stack = []  # (indent, tag)
    para = []

    def flush_para():
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()

    def close_lists(to_indent=-1):
        while list_stack and list_stack[-1][0] > to_indent:
            out.append(f"</{list_stack.pop()[1]}>")

    while i < n:
        line = lines[i]
        stripped = line.strip()

        # code fence
        if stripped.startswith("```"):
            flush_para()
            close_lists()
            lang = stripped[3:].strip() or "text"
            i += 1
            buf = []
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            out.append(
                f'<pre class="code" data-lang="{html.escape(lang)}"><code>'
                + html.escape("\n".join(buf), quote=False)
                + "</code></pre>"
            )
            continue

        # blank
        if not stripped:
            flush_para()
            close_lists()
            i += 1
            continue

        # hr
        if re.fullmatch(r"-{3,}|\*{3,}", stripped):
            flush_para()
            close_lists()
            out.append("<hr/>")
            i += 1
            continue

        # heading
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            flush_para()
            close_lists()
            lvl = len(m.group(1))
            text = m.group(2)
            anchor = re.sub(r"[^\w\u4e00-\u9fff]+", "-", text).strip("-")
            out.append(f'<h{lvl} id="{anchor}">{inline(text)}</h{lvl}>')
            i += 1
            continue

        # table
        if stripped.startswith("|") and i + 1 < n and re.match(r"^\|[\s:\-|]+\|$", lines[i + 1].strip()):
            flush_para()
            close_lists()

            def cells(row):
                row = row.strip()
                if row.startswith("|"):
                    row = row[1:]
                if row.endswith("|"):
                    row = row[:-1]
                return [c.strip() for c in row.split("|")]

            head = cells(stripped)
            i += 2
            body = []
            while i < n and lines[i].strip().startswith("|"):
                body.append(cells(lines[i].strip()))
                i += 1
            t = ['<div class="tw"><table>', "<thead><tr>"]
            for c in head:
                t.append(f"<th>{inline(c)}</th>")
            t.append("</tr></thead><tbody>")
            for row in body:
                t.append("<tr>")
                for c in row:
                    t.append(f"<td>{inline(c)}</td>")
                t.append("</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t))
            continue

        # blockquote
        if stripped.startswith(">"):
            flush_para()
            close_lists()
            buf = []
            while i < n and lines[i].strip().startswith(">"):
                buf.append(lines[i].strip().lstrip(">").strip())
                i += 1
            out.append("<blockquote>" + inline(" ".join(buf)) + "</blockquote>")
            continue

        # lists
        m = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", line)
        if m:
            flush_para()
            indent = len(m.group(1))
            tag = "ol" if re.match(r"\d+\.", m.group(2)) else "ul"
            while list_stack and list_stack[-1][0] > indent:
                out.append(f"</{list_stack.pop()[1]}>")
            if not list_stack or list_stack[-1][0] < indent:
                out.append(f"<{tag}>")
                list_stack.append((indent, tag))
            out.append(f"<li>{inline(m.group(3))}</li>")
            i += 1
            continue

        para.append(stripped)
        i += 1

    flush_para()
    close_lists()
    return "\n".join(out)


CSS = """
:root{
  --bg:#0b0d10; --panel:#12151a; --panel2:#171b21; --line:#242a33;
  --fg:#e6eaf0; --fg2:#a8b3c2; --fg3:#6f7b8b;
  --accent:#5ac8fa; --accent2:#7ee787; --warn:#ffb454;
  --mono:"JetBrains Mono","Cascadia Code",Consolas,monospace;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
  font-family:"Inter","Segoe UI","Microsoft YaHei",system-ui,sans-serif;
  font-size:15px;line-height:1.75;-webkit-font-smoothing:antialiased}
.wrap{max-width:1080px;margin:0 auto;padding:56px 28px 120px}
.doc{background:var(--panel);border:1px solid var(--line);border-radius:14px;
  padding:52px 56px 64px;box-shadow:0 24px 60px rgba(0,0,0,.45)}
h1{font-size:31px;line-height:1.35;margin:0 0 6px;letter-spacing:.3px;font-weight:650}
h1+table{margin-top:26px}
h2{font-size:21px;margin:52px 0 16px;padding-bottom:10px;border-bottom:1px solid var(--line);
  font-weight:640;letter-spacing:.2px}
h2:first-of-type{margin-top:36px}
h3{font-size:16.5px;margin:32px 0 10px;color:#dfe7f2;font-weight:640}
h4{font-size:15px;margin:24px 0 8px;color:var(--fg2)}
p{margin:12px 0;color:#d3dae4}
strong{color:#fff;font-weight:640}
em{color:var(--fg2)}
a{color:var(--accent);text-decoration:none;border-bottom:1px solid rgba(90,200,250,.3)}
a:hover{border-bottom-color:var(--accent)}
code{font-family:var(--mono);font-size:12.5px;background:#1c222b;color:#ffd9a8;
  padding:2px 6px;border-radius:5px;border:1px solid #262e39;word-break:break-word}
pre.code{position:relative;background:#0e1116;border:1px solid var(--line);border-radius:10px;
  padding:18px 20px;overflow-x:auto;margin:16px 0}
pre.code code{background:none;border:none;padding:0;color:#c9d6e4;font-size:12.6px;line-height:1.72}
pre.code::before{content:attr(data-lang);position:absolute;top:0;right:0;
  font-family:var(--mono);font-size:10px;color:var(--fg3);padding:5px 10px;
  border-left:1px solid var(--line);border-bottom:1px solid var(--line);
  border-radius:0 10px 0 8px;background:#12161c;letter-spacing:.5px}
blockquote{margin:18px 0;padding:14px 20px;background:#131920;border-left:3px solid var(--accent);
  border-radius:0 8px 8px 0;color:#c3cedb}
blockquote p{margin:0}
hr{border:none;border-top:1px solid var(--line);margin:34px 0}
.tw{overflow-x:auto;margin:18px 0;border:1px solid var(--line);border-radius:10px}
table{border-collapse:collapse;width:100%;font-size:13.2px;background:var(--panel2)}
thead th{background:#1b222b;color:#cfd9e6;text-align:left;font-weight:640;
  padding:11px 14px;border-bottom:1px solid #2a323d;white-space:nowrap}
tbody td{padding:10px 14px;border-bottom:1px solid #1e242d;color:#c8d2de;vertical-align:top}
tbody tr:last-child td{border-bottom:none}
tbody tr:hover{background:#1a212a}
ul,ol{margin:12px 0;padding-left:24px;color:#d3dae4}
li{margin:6px 0}
li::marker{color:var(--accent)}
.meta{color:var(--fg3);font-size:12px;letter-spacing:.4px;margin-bottom:26px}
@media (max-width:720px){.doc{padding:28px 20px 40px}.wrap{padding:20px 12px 60px}h1{font-size:24px}}
@media print{body{background:#fff;color:#111}.doc{box-shadow:none;border:none;padding:0}
  pre.code code,code{color:#111}thead th{background:#eee;color:#111}tbody td{color:#111}}
"""


def main():
    src, dst = sys.argv[1], sys.argv[2]
    title = sys.argv[3] if len(sys.argv) > 3 else "VitreLens"
    subtitle = sys.argv[4] if len(sys.argv) > 4 else "CODE REVIEW · 2026-10-28"
    md = open(src, encoding="utf-8").read()
    body = convert(md)
    doc = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>{title}</title>
<style>{CSS}</style>
</head>
<body>
<div class="wrap"><div class="doc">
<div class="meta">{subtitle}</div>
{body}
</div></div>
</body>
</html>
"""
    open(dst, "w", encoding="utf-8").write(doc)
    print(f"written {dst} ({len(doc)} bytes)")


if __name__ == "__main__":
    main()
