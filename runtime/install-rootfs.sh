#!/usr/bin/env bash
# Runs INSIDE the Debian rootfs (chroot on a privileged host, or proot in CI).
# Expects DSH_VERSION in the environment and Node installed at /usr/local.
set -euo pipefail

: "${DSH_VERSION:?DSH_VERSION is required}"
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
export HOME=/root
export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -yqq --no-install-recommends \
    ca-certificates curl xz-utils git ripgrep bash python3 make g++ build-essential \
    procps openssh-client unzip ncurses-base ncurses-bin tzdata jq >/dev/null
dpkg --configure -a >/dev/null || true

npm install -g "@deepseek-ai/dsh@${DSH_VERSION}" --foreground-scripts --no-audit --no-fund >/dev/null
npm install -g pnpm@10 --no-audit --no-fund >/dev/null

python3 - <<'PY'
import collections, json, os, shutil

root = "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules"
topdir = os.path.join(root, "zod")
top = json.load(open(os.path.join(topdir, "package.json")))["version"]
groups = collections.defaultdict(list)
for dp, dn, fn in os.walk(root):
    if os.path.basename(dp) == "zod" and os.path.basename(os.path.dirname(dp)) == "node_modules" and dp != topdir:
        pj = os.path.join(dp, "package.json")
        if os.path.isfile(pj) and not os.path.islink(dp):
            groups[json.load(open(pj))["version"]].append(dp)
for ver, dirs in groups.items():
    if ver == top or not dirs:
        continue
    shared = os.path.join(root, ".shared", "zod-" + ver)
    os.makedirs(os.path.dirname(shared), exist_ok=True)
    if not os.path.isdir(shared):
        shutil.copytree(dirs[0], shared, symlinks=True)
    for d in dirs:
        shutil.rmtree(d, ignore_errors=True)
        os.symlink(shared, d)
    print(f"deduped {len(dirs)} zod@{ver} copies")
PY

node -v
dsh --version
