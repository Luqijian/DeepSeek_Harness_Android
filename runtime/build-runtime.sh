#!/usr/bin/env bash
# Build the bundled Linux runtime (Debian 13 arm64 rootfs + Node 24 + official dsh)
# and the proot host binaries for jniLibs. Run on a Linux host (CI: ubuntu-24.04-arm).
#
# Outputs:
#   out/rootfs.tar.gz            -> app/src/main/assets/runtime/rootfs.bin
#   out/manifest.json            -> app/src/main/assets/runtime/manifest.json
#   out/jniLibs/arm64-v8a/*.so   -> app/src/main/jniLibs/arm64-v8a/
#
# Environment:
#   DSH_VERSION   official npm package to bundle (default: value in gradle.properties)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/runtime/out"
DSH_VERSION="${DSH_VERSION:-$(grep -oP '^dshVersion=\K.*' "$ROOT/gradle.properties")}"
NODE_VERSION=24.22.0
DEBIAN_TAG=trixie
PROOT_VER=5.1.107.97
TALLOC_VER=2.5.0
SHMEM_VER=0.7
TERMUX_REPO=https://packages.termux.dev/apt/termux-main

rm -rf "$OUT"; mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/stage"

echo "==> 1/7 Fetch Debian $DEBIAN_TAG arm64 rootfs from the Docker registry"
python3 - "$OUT/stage/debian.tar" "$DEBIAN_TAG" <<'PY'
import gzip, io, json, sys, tarfile, urllib.request

out_path, tag = sys.argv[1], sys.argv[2]
def get(url, token=None, accept=None):
    req = urllib.request.Request(url)
    if token: req.add_header('Authorization', 'Bearer ' + token)
    if accept: req.add_header('Accept', accept)
    return urllib.request.urlopen(req, timeout=300)

scope = 'repository:library/debian:pull'
tok = json.load(get('https://auth.docker.io/token?service=registry.docker.io&scope=' + scope))['token']
idx = json.load(get(f'https://registry-1.docker.io/v2/library/debian/manifests/{tag}', tok,
    'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json,'
    ' application/vnd.docker.distribution.manifest.v2+json'))
digest = None
for m in idx.get('manifests', []):
    p = m.get('platform', {})
    if p.get('os') == 'linux' and p.get('architecture') == 'arm64':
        digest = m['digest']; break
assert digest, 'no arm64 manifest'
man = json.load(get('https://registry-1.docker.io/v2/library/debian/manifests/' + digest, tok,
    'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'))
with open(out_path, 'wb') as out:
    for layer in man['layers']:
        data = get('https://registry-1.docker.io/v2/library/debian/blobs/' + layer['digest'], tok).read()
        if layer['mediaType'].endswith('gzip'):
            data = gzip.decompress(data)
        out.write(data)
PY

echo "==> 2/7 Rewrite hardlinks to file copies (Android SELinux denies link(2) in app data)"
python3 - "$OUT/stage/debian.tar" "$OUT/stage/rootfs.tar" <<'PY'
import copy, io, sys, tarfile
src = tarfile.open(sys.argv[1], 'r:')
dst = tarfile.open(sys.argv[2], 'w')
n = 0
for m in src:
    if m.islnk():
        f = src.extractfile(m)
        data = f.read() if f else b''
        m2 = copy.copy(m); m2.type = tarfile.REGTYPE; m2.linkname = ''; m2.size = len(data)
        dst.addfile(m2, io.BytesIO(data)); n += 1
    else:
        dst.addfile(m, src.extractfile(m) if m.isreg() else None)
dst.close(); src.close()
print(f'    converted {n} hardlinks')
PY

echo "==> 3/7 Extract rootfs"
mkdir -p "$OUT/stage/rootfs"
tar -xf "$OUT/stage/rootfs.tar" -C "$OUT/stage/rootfs"

echo "==> 4/7 Install Node $NODE_VERSION (official linux-arm64 glibc build)"
curl -fsSL -o "$OUT/stage/node.tar.xz" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz"
tar -xJf "$OUT/stage/node.tar.xz" -C "$OUT/stage/rootfs/usr/local" --strip-components=1

echo "==> 5/7 Install @deepseek-ai/dsh@$DSH_VERSION and dedupe nested zod copies"
R="$OUT/stage/rootfs"
cp "$ROOT/runtime/install-rootfs.sh" "$R/root/install-rootfs.sh"
chmod +x "$R/root/install-rootfs.sh"

install_env=(
  /usr/bin/env -i
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  TMPDIR=/tmp HOME=/root DSH_VERSION="$DSH_VERSION"
  /bin/bash /root/install-rootfs.sh
)

if chroot "$R" /bin/true >/dev/null 2>&1; then
    echo "    using chroot"
    chroot "$R" "${install_env[@]}"
else
    echo "    chroot unavailable (GitHub runner); using host proot"
    if ! command -v proot >/dev/null 2>&1; then
        if [ "$(id -u)" -eq 0 ]; then
            apt-get update -qq && apt-get install -yqq proot
        else
            sudo apt-get update -qq && sudo apt-get install -yqq proot
        fi
    fi
    PROOT_NO_SECCOMP=1 proot -0 -r "$R" -b /dev -b /proc -b /sys -b /etc/resolv.conf -w /root "${install_env[@]}"
fi

echo "==> 6/7 Clean caches and package rootfs"
R="$OUT/stage/rootfs"
rm -f "$R/root/install-rootfs.sh"
rm -rf "$R/root/.npm" "$R/root/.cache" "$R/var/lib/apt/lists"/* "$R/var/cache/apt"/* "$R/var/log"/*
rm -rf "$R/usr/share/doc"/* "$R/usr/share/man"/*
find "$R/usr/share/locale" -mindepth 1 -maxdepth 1 ! -name 'en*' ! -name 'C*' -exec rm -rf {} + 2>/dev/null || true
tar -czf "$OUT/rootfs.tar.gz" -C "$R" .

echo "==> 7/7 proot host binaries from Termux (16 KB aligned)"
for spec in "pool/main/p/proot/proot_${PROOT_VER}_aarch64.deb" \
            "pool/main/libt/libtalloc/libtalloc_${TALLOC_VER}_aarch64.deb" \
            "pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_aarch64.deb"; do
    curl -fsSL -o "$OUT/stage/$(basename "$spec")" "$TERMUX_REPO/$spec"
done
python3 - "$OUT/stage" "$OUT/jniLibs/arm64-v8a" <<'PY'
import io, os, sys, tarfile

def members(path):
    data = open(path, 'rb').read()
    off = 8
    while off + 60 <= len(data):
        hdr = data[off:off + 60]; off += 60
        name = hdr[:16].decode('ascii', 'replace').strip().rstrip('/')
        size = int(hdr[48:58].decode().strip())
        body = data[off:off + size]; off += size + (size & 1)
        if name.startswith('data.tar'):
            return tarfile.open(fileobj=io.BytesIO(body), mode='r:*')
    raise SystemExit('no data.tar in ' + path)

stage, out = sys.argv[1], sys.argv[2]
want = {
    'proot_': ('./data/data/com.termux/files/usr/bin/proot', 'libproot.so'),
    'proot_': ('./data/data/com.termux/files/usr/libexec/proot/loader', 'libproot_loader.so'),
}
bins = {}
for deb in os.listdir(stage):
    if not deb.endswith('.deb'):
        continue
    tf = members(os.path.join(stage, deb))
    for m in tf.getmembers():
        if m.isreg():
            bins.setdefault(m.name, []).append((deb, tf.extractfile(m).read()))

def pick(suffix):
    for name, payloads in bins.items():
        if name.endswith(suffix):
            return payloads[0][1]
    raise SystemExit('missing ' + suffix)

proot = pick('/bin/proot').replace(b'libtalloc.so.2\x00', b'libtalloc.so\x00\x00\x00')
loader = pick('libexec/proot/loader')
talloc = pick('libtalloc.so.2.5.0').replace(b'libtalloc.so.2\x00', b'libtalloc.so\x00\x00\x00')
shmem = pick('libandroid-shmem.so')
for name, blob in [('libproot.so', proot), ('libproot_loader.so', loader),
                   ('libtalloc.so', talloc), ('libandroid-shmem.so', shmem)]:
    open(os.path.join(out, name), 'wb').write(blob)
    print('    wrote', name, len(blob))
PY

SHA=$(sha256sum "$OUT/rootfs.tar.gz" | cut -d' ' -f1)
SIZE=$(stat -c%s "$OUT/rootfs.tar.gz")
python3 - "$OUT/manifest.json" "$SHA" "$SIZE" "$DSH_VERSION" <<'PY'
import json, sys
json.dump({
    'version': sys.argv[4] + '-r1',
    'dshVersion': sys.argv[4],
    'sha256': sys.argv[2],
    'size': int(sys.argv[3]),
    'installedSize': int(sys.argv[3]),
}, open(sys.argv[1], 'w'), indent=2)
PY

echo "==> Done"
ls -lh "$OUT/rootfs.tar.gz" "$OUT/manifest.json" "$OUT/jniLibs/arm64-v8a/"
echo "Copy into the app before building:"
echo "  cp $OUT/rootfs.tar.gz $ROOT/app/src/main/assets/runtime/rootfs.bin"
echo "  cp $OUT/manifest.json $ROOT/app/src/main/assets/runtime/manifest.json"
echo "  cp $OUT/jniLibs/arm64-v8a/*.so $ROOT/app/src/main/jniLibs/arm64-v8a/"
