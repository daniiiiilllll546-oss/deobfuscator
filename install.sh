#!/usr/bin/env bash
# Install or update deflow: clone it, register it as the Claude Code skill
# /deflow, and link the `deflow` command into a writable folder on PATH.
set -euo pipefail
DIR="${DEFLOW_DIR:-$HOME/.local/share/deflow}"
SKILLS="${CLAUDE_SKILLS_DIR:-$HOME/.claude/skills}"
REPO=https://github.com/daniiiiilllll546-oss/deobfuscator.git

if [ -d "$DIR/.git" ]; then git -C "$DIR" pull -q --ff-only; echo "deflow: updated $DIR"
else mkdir -p "$(dirname "$DIR")"; git clone -q "$REPO" "$DIR"; echo "deflow: installed to $DIR"; fi
chmod +x "$DIR/deflow"

# Claude Code skill: ~/.claude/skills/deflow -> repo skill folder (gives /deflow).
mkdir -p "$SKILLS"
if [ -e "$SKILLS/deflow" ] && [ ! -L "$SKILLS/deflow" ]; then
  echo "deflow: $SKILLS/deflow exists and is not a link; leaving it alone" >&2
else
  ln -sfn "$DIR/skills/deflow" "$SKILLS/deflow"; echo "deflow: Claude Code skill ready: /deflow (new sessions)"
fi

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
