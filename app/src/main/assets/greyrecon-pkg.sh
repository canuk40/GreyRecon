#!/system/bin/sh
# greyrecon-pkg -- a minimal, original package installer for GreyRecon's terminal.
#
# Package format (".grpkg"): a gzipped tar containing a MANIFEST text file (NAME=/VERSION=/DEPENDS=
# key=value lines) plus payload files under bin/, lib/, share/, etc. This is an original, from-scratch
# design -- not dpkg's .deb format and not a port of dpkg/apt's own GPL-2 source.
# See GreyRecon.md for why that scoping (own format, own code, no bundled repo) is deliberate.
#
# Uses toybox's own tar/grep/cut/sha256sum (see libtoybox.so) -- runs as a normal shebang script, so
# GreyRecon's exec shim (greyrecon_exec.c) re-execs it via /system/bin/sh like any other script.
#
# Network fetch (install/fetch from a URL): toybox has no HTTP(S) client applet, so the actual GET
# happens in-process in the hosting Android app (real TLS via HttpURLConnection, validated against the
# system trust store -- see PkgFetchServer.kt) and this script just asks for it over a loopback socket
# via toybox's own `nc`. Fetching and then running arbitrary code is inherently sensitive -- this talks
# to a server bound to 127.0.0.1 that only this device's own processes can reach, but still: only fetch
# from sources you trust, same as you would with curl | sh anywhere else.
#
# Checksum verification: pass an expected sha256sum as the last argument to `install`/`fetch` and it's
# checked (via toybox's own sha256sum) before the file is used; a mismatch aborts and cleans up rather
# than installing anyway. A URL install with no explicit checksum tries a "<url>.sha256" sidecar first
# (best-effort, silent if it 404s) before falling back to a plain warning -- it's allowed, not blocked,
# since requiring every URL to publish a sidecar would make this unusable against plenty of real hosts.
# The sha256 actually installed is always recorded in the registry (see `info`), verified or not.

set -e

PKG_ROOT="${GREYRECON_PKG_ROOT:-$HOME/pkg}"
BIN_DIR="${GREYRECON_PKG_BIN:-$HOME/bin}"
REGISTRY="$PKG_ROOT/installed.txt"
FETCH_PORT="${GREYRECON_PKG_FETCH_PORT:-47823}"

mkdir -p "$PKG_ROOT" "$BIN_DIR"
touch "$REGISTRY"

usage() {
  echo "usage: greyrecon-pkg install <file.grpkg | url> [sha256sum]"
  echo "       greyrecon-pkg fetch <url> <dest> [sha256sum]"
  echo "       greyrecon-pkg list"
  echo "       greyrecon-pkg remove <name>"
  echo "       greyrecon-pkg info <name>"
  exit 1
}

# verify_checksum <file> <expected-hex> -- via toybox's own sha256sum. Prints and returns non-zero on
# mismatch; on match, prints the confirmed digest so it's visible in scrollback, not just silently ok.
verify_checksum() {
  file="$1"
  expected="$2"
  actual=$(sha256sum "$file" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "greyrecon-pkg: checksum mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    return 1
  fi
  echo "greyrecon-pkg: checksum verified ($actual)"
}

# do_fetch <url> <dest> -- talks to PkgFetchServer over a loopback socket. The server writes the
# downloaded bytes directly to $dest itself and replies with exactly one line, "OK <bytes>" or
# "ERR <message>" -- the body never crosses the socket, so there's no binary/text mixing to get wrong
# here.
do_fetch() {
  url="$1"
  dest="$2"
  reply=$(printf '%s\t%s\n' "$dest" "$url" | nc -w 30 127.0.0.1 "$FETCH_PORT" 2>/dev/null) || reply=""
  case "$reply" in
    "OK "*)
      return 0
      ;;
    "ERR "*)
      echo "greyrecon-pkg: fetch failed: ${reply#ERR }" >&2
      return 1
      ;;
    *)
      echo "greyrecon-pkg: fetch failed: no response from GreyRecon (is the terminal still open?)" >&2
      return 1
      ;;
  esac
}

cmd_fetch() {
  url="$1"
  dest="$2"
  expected_sha="$3"
  [ -z "$url" ] || [ -z "$dest" ] && usage
  do_fetch "$url" "$dest" || exit 1
  if [ -n "$expected_sha" ]; then
    verify_checksum "$dest" "$expected_sha" || { rm -f "$dest"; exit 1; }
  fi
  echo "greyrecon-pkg: fetched $url -> $dest"
}

cmd_install() {
  pkgfile="$1"
  expected_sha="$2"
  [ -z "$pkgfile" ] && usage

  fetched=""
  case "$pkgfile" in
    http://*|https://*)
      url="$pkgfile"
      fetched="$PKG_ROOT/.fetched.$$"
      trap 'rm -f "$fetched"' EXIT
      do_fetch "$url" "$fetched" || exit 1
      pkgfile="$fetched"

      if [ -z "$expected_sha" ]; then
        # Best-effort sidecar checksum -- the same "<url>.sha256" convention several real installers
        # use. Silent if it doesn't exist; a URL install with no checksum at all (neither an explicit
        # one nor a sidecar) is allowed but flagged below, not blocked outright.
        sidecar="$PKG_ROOT/.sidecar.$$"
        if do_fetch "$url.sha256" "$sidecar" 2>/dev/null; then
          expected_sha=$(cut -d' ' -f1 < "$sidecar" 2>/dev/null)
        fi
        rm -f "$sidecar"
      fi

      if [ -n "$expected_sha" ]; then
        verify_checksum "$pkgfile" "$expected_sha" || exit 1
      else
        echo "greyrecon-pkg: warning: installing from $url with no checksum to verify against" >&2
      fi
      ;;
    *)
      if [ -n "$expected_sha" ]; then
        verify_checksum "$pkgfile" "$expected_sha" || exit 1
      fi
      ;;
  esac

  [ -f "$pkgfile" ] || { echo "greyrecon-pkg: no such file: $pkgfile" >&2; exit 1; }

  work="$PKG_ROOT/.work.$$"
  rm -rf "$work"
  mkdir -p "$work"
  if ! tar -xzf "$pkgfile" -C "$work" 2>/dev/null; then
    echo "greyrecon-pkg: not a valid package archive: $pkgfile" >&2
    rm -rf "$work"
    exit 1
  fi

  if [ ! -f "$work/MANIFEST" ]; then
    echo "greyrecon-pkg: package is missing its MANIFEST" >&2
    rm -rf "$work"
    exit 1
  fi

  # Recorded in the registry regardless of whether it was actually verified against anything --
  # this is what actually got installed, useful as an audit trail even for an unverified local file.
  actual_sha=$(sha256sum "$pkgfile" | cut -d' ' -f1)

  name=$(grep '^NAME=' "$work/MANIFEST" | cut -d= -f2-)
  version=$(grep '^VERSION=' "$work/MANIFEST" | cut -d= -f2-)
  depends=$(grep '^DEPENDS=' "$work/MANIFEST" | cut -d= -f2-)

  if [ -z "$name" ]; then
    echo "greyrecon-pkg: MANIFEST is missing NAME" >&2
    rm -rf "$work"
    exit 1
  fi

  for dep in $depends; do
    if ! grep -q "^$dep:" "$REGISTRY" 2>/dev/null; then
      echo "greyrecon-pkg: warning: $name depends on '$dep', which isn't installed" >&2
    fi
  done

  dest="$PKG_ROOT/$name"
  rm -rf "$dest"
  mkdir -p "$dest"
  mv "$work"/* "$dest/" 2>/dev/null || true
  rm -rf "$work"

  if [ -d "$dest/bin" ]; then
    for f in "$dest/bin"/*; do
      [ -f "$f" ] || continue
      chmod 700 "$f"
      ln -sf "$f" "$BIN_DIR/$(basename "$f")"
    done
  fi

  grep -v "^$name:" "$REGISTRY" > "$REGISTRY.tmp" 2>/dev/null || true
  mv "$REGISTRY.tmp" "$REGISTRY"
  echo "$name:$version:$depends:$actual_sha" >> "$REGISTRY"

  echo "greyrecon-pkg: installed $name $version"
}

cmd_list() {
  if [ ! -s "$REGISTRY" ]; then
    echo "(no packages installed)"
    return
  fi
  while IFS=: read -r n v d; do
    echo "$n $v"
  done < "$REGISTRY"
}

cmd_remove() {
  name="$1"
  [ -z "$name" ] && usage
  if ! grep -q "^$name:" "$REGISTRY" 2>/dev/null; then
    echo "greyrecon-pkg: $name is not installed" >&2
    exit 1
  fi
  dest="$PKG_ROOT/$name"
  if [ -d "$dest/bin" ]; then
    for f in "$dest/bin"/*; do
      [ -f "$f" ] || continue
      link="$BIN_DIR/$(basename "$f")"
      [ -L "$link" ] && rm -f "$link"
    done
  fi
  rm -rf "$dest"
  grep -v "^$name:" "$REGISTRY" > "$REGISTRY.tmp" 2>/dev/null || true
  mv "$REGISTRY.tmp" "$REGISTRY"
  echo "greyrecon-pkg: removed $name"
}

cmd_info() {
  name="$1"
  [ -z "$name" ] && usage
  line=$(grep "^$name:" "$REGISTRY" 2>/dev/null) || { echo "greyrecon-pkg: $name is not installed" >&2; exit 1; }
  n=$(echo "$line" | cut -d: -f1)
  v=$(echo "$line" | cut -d: -f2)
  d=$(echo "$line" | cut -d: -f3)
  sha=$(echo "$line" | cut -d: -f4)
  echo "name: $n"
  echo "version: $v"
  echo "depends: ${d:-none}"
  echo "sha256: ${sha:-unknown}"
  echo "location: $PKG_ROOT/$n"
}

case "$1" in
  install) shift; cmd_install "$@" ;;
  fetch) shift; cmd_fetch "$@" ;;
  list) cmd_list ;;
  remove) shift; cmd_remove "$@" ;;
  info) shift; cmd_info "$@" ;;
  *) usage ;;
esac
