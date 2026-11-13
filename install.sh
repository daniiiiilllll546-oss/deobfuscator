#!/usr/bin/env bash
# Install or update deflow: clone into ~/.claude/skills/deflow (also makes it a
# Claude Code skill) and link the `deflow` command into a writable folder on PATH.
set -euo pipefail
DIR="${DEFLOW_DIR:-$HOME/.claude/skills/deflow}"
REPO=https://github.com/daniiiiilllll546-oss/deobfuscator.git

if [ -d "$DIR/.git" ]; then git -C "$DIR" pull -q --ff-only; echo "deflow: updated $DIR"
else mkdir -p "$(dirname "$DIR")"; git clone -q "$REPO" "$DIR"; echo "deflow: installed to $DIR"; fi
chmod +x "$DIR/deflow"

# First folder that is on PATH and writable without sudo.
for d in /opt/homebrew/bin /usr/local/bin "$HOME/.local/bin" "$HOME/bin"; do
  case ":$PATH:" in *":$d:"*) case "$d" in "$HOME"/*) mkdir -p "$d" ;; esac; [ -d "$d" ] && [ -w "$d" ] && { ln -sf "$DIR/deflow" "$d/deflow"; echo "deflow: linked $d/deflow"; LINKED=1; break; } ;; esac
done
if [ -z "${LINKED:-}" ]; then
  mkdir -p "$HOME/.local/bin"; ln -sf "$DIR/deflow" "$HOME/.local/bin/deflow"
  echo "deflow: linked ~/.local/bin/deflow. Add it to PATH:  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.zshrc"
fi
command -v javac >/dev/null || echo "deflow: note: needs a JDK 17+ (java + javac) on PATH"
echo "deflow: try  deflow some.jar"
