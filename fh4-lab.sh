#!/bin/bash
# Builds the fh4-test changes as a DroidDeck Lab build, on the phone, from the DroidDeck terminal.
set -euo pipefail
dir=$HOME/DroidDeck-fh4
[ -d "$dir/.git" ] || git clone --filter=blob:none https://github.com/Tapuuk/DroidDeck.git "$dir"
cd "$dir"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/Droid-Deck/DroidDeck.git
git fetch -q origin fh4-test
git fetch -q upstream
git config user.name >/dev/null || git config user.name fh4-test
git config user.email >/dev/null || git config user.email fh4-test@localhost
[ -x "$HOME/.local/bin/dd-lab-build" ] || tools/agent/setup
export PATH="$HOME/.local/bin:$PATH" ADB_SERIAL="${ADB_SERIAL:-127.0.0.1:5555}"
state=$(python3 tools/droiddeckctl state 2>/dev/null | sed -n '/^{/,$p') ||
  { echo "Can't reach DroidDeck. Turn on Wireless debugging in Developer options and run this again."; exit 1; }
base=$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s.get("apkCommit") or s["build"].rsplit(" at ", 1)[-1])' <<<"$state")
git cat-file -e "$base^{commit}" 2>/dev/null && git merge-base --is-ancestor 61031e3c "$base" ||
  { echo "This DroidDeck is too old for Lab builds. Set Updates > Update channel to Preview, update, then run this again."; exit 1; }
git switch -q -f -C fh4-lab "$base"
git cherry-pick origin/fh4-test
dd-lab-build
