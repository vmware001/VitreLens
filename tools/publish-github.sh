#!/bin/bash
# 把本仓库发布到 GitHub。用法：./tools/publish-github.sh [仓库名] [--private]
set -e
cd "$(dirname "$0")/.."

# Git Bash 里 gh 默认不在 PATH 上
[ -d "/c/Program Files/GitHub CLI" ] && export PATH="/c/Program Files/GitHub CLI:$PATH"

REPO="${1:-VitreLens}"
VIS="${2:---public}"

if ! command -v gh >/dev/null 2>&1; then
  echo "!! 没找到 gh（GitHub CLI）。请先安装：winget install --id GitHub.cli -e"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "== 需要先登录 GitHub =="
  gh auth login
fi

echo "== 当前账号 =="
gh auth status 2>&1 | head -4

if [ -n "$(git status --porcelain)" ]; then
  echo "!! 工作区还有未提交的改动，先提交再发布"
  git status --short | head
  exit 1
fi

echo "== 将要发布 =="
echo "  仓库名：$REPO（$VIS）"
echo "  文件：$(git ls-files | wc -l) 个"
echo "  提交：$(git rev-list --count HEAD) 个"
echo "  分支：$(git branch --show-current)"

gh repo create "$REPO" "$VIS" --source=. --remote=origin --push

echo
echo "== 完成 =="
gh repo view --web --json url -q .url 2>/dev/null || gh repo view "$REPO" --json url -q .url
