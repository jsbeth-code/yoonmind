#!/usr/bin/env bash
# 한글 HTML 보고서를 PDF로 변환 (Claude Code 원격 환경용)
# 사용법: build_pdf.sh <input.html> <output.pdf>
set -euo pipefail

IN="${1:?usage: build_pdf.sh <input.html> <output.pdf>}"
OUT="${2:?usage: build_pdf.sh <input.html> <output.pdf>}"

# 한글 폰트 없으면 설치 (이 환경에서 apt는 동작, 외부 폰트 CDN은 프록시 차단됨)
if ! fc-list 2>/dev/null | grep -qiE "Noto Sans CJK"; then
  apt-get install -y fonts-noto-cjk
fi

# pre-installed Playwright chromium headless shell 탐색
SHELL_BIN=$(ls -d /opt/pw-browsers/chromium_headless_shell-*/chrome-linux/headless_shell 2>/dev/null | head -1)
if [ -z "$SHELL_BIN" ]; then
  SHELL_BIN=$(ls -d /opt/pw-browsers/chromium-*/chrome-linux/chrome 2>/dev/null | head -1)
fi
[ -n "$SHELL_BIN" ] || { echo "chromium not found under /opt/pw-browsers" >&2; exit 1; }

"$SHELL_BIN" --headless --disable-gpu --no-sandbox \
  --print-to-pdf="$OUT" --no-pdf-header-footer \
  "file://$(realpath "$IN")"

echo "OK: $OUT ($(stat -c%s "$OUT") bytes)"
