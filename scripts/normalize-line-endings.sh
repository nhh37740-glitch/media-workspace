#!/usr/bin/env bash
#
# Rewrites every tracked text file with LF line endings.
#
# Needed because a single CR in a shebang turns a working script into "env: 'bash\r': No such file
# or directory", which reads as a missing interpreter. .gitattributes keeps new checkouts correct;
# this fixes a working tree that an editor already converted, and the sync script refuses to ship a
# script that still has CR.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

EXTENSIONS='sh|bash|java|gradle|json|sql|yml|yaml|properties|md|feature|xml|conf|opts|toml|gitignore|gitattributes|txt|css|js|ts|vue|html'

converted=0
while IFS= read -r file; do
  case "$file" in
    build/*|*/build/*|node_modules/*|*/.git/*|*/.gradle/*) continue ;;
  esac
  if printf '%s' "$file" | grep -qE "\.($EXTENSIONS)$" || [ "$file" = "gradlew" ] || [ "$file" = ".gitignore" ] || [ "$file" = ".gitattributes" ]; then
    # Detected by comparing the file with its own CR-stripped form, because grep's treatment of a
    # literal CR differs between the GNU grep shipped with Git Bash and the one on Linux.
    if ! tr -d '\r' < "$file" | cmp -s - "$file"; then
      # tr is used rather than sed -i so the file keeps its mode and no backup file is created.
      tr -d '\r' < "$file" > "$file.lf" && mv "$file.lf" "$file"
      printf 'normalized %s\n' "$file"
      converted=$((converted + 1))
    fi
  fi
done < <(git ls-files --cached --others --exclude-standard)

chmod +x gradlew scripts/*.sh 2>/dev/null || true
printf 'normalized %d file(s)\n' "$converted"
