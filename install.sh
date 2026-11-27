#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEFAULT_INSTALL_DIR="${HOME}/.local/share/procomp"
DEFAULT_BIN_DIR="${HOME}/.local/bin"

INSTALL_DIR=""
BIN_DIR=""
ASSUME_YES=0
UNINSTALL=0

usage() {
    cat <<'EOF'
Usage: ./install.sh [options]

Install or upgrade procomp (pure JEP 458 source copy) for Linux, macOS, and Windows Git Bash.
Never edits shell profiles or Windows Registry PATH, and never runs destructive rm commands.

Options:
  --dir <path>      Install directory (default: ~/.local/share/procomp)
  --bin-dir <path>  Directory for procomp PATH shims (default: ~/.local/bin)
  -y, --yes         Non-interactive mode (accept defaults / provided flags)
  --uninstall       Verify an existing install and print exact rm commands (does not delete)
  -h, --help        Show this help message
EOF
}

is_windows_bash() {
    case "$(uname -s 2>/dev/null || echo "")" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

expand_tilde() {
    local p="$1"
    case "$p" in
        "~") printf '%s\n' "$HOME" ;;
        "~/"*) printf '%s/%s\n' "$HOME" "${p#\~/}" ;;
        *) printf '%s\n' "$p" ;;
    esac
}

normalize_path() {
    local p
    p="$(expand_tilde "$1")"
    case "$p" in
        /*) ;;
        *) p="$PWD/$p" ;;
    esac
    # Strip trailing slashes (except root "/")
    while [ "${#p}" -gt 1 ] && [ "${p%/}" != "$p" ]; do
        p="${p%/}"
    done
    # Canonicalize existing directory or existing parent so relative/dot segments resolve cleanly
    if [ -d "$p" ]; then
        p="$(CDPATH= cd -- "$p" && pwd)"
    else
        local parent base
        parent="$(dirname "$p")"
        base="$(basename "$p")"
        if [ -d "$parent" ]; then
            parent="$(CDPATH= cd -- "$parent" && pwd)"
            if [ "$parent" = "/" ]; then
                p="/$base"
            else
                p="$parent/$base"
            fi
        fi
    fi
    printf '%s\n' "$p"
}

to_windows_path() {
    local p="$1"
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$p"
    else
        printf '%s\n' "$p"
    fi
}

resolve_java_bin() {
    if [ -n "${JAVA_HOME:-}" ]; then
        local jh="$JAVA_HOME"
        if is_windows_bash && command -v cygpath >/dev/null 2>&1; then
            jh="$(cygpath -u "$jh")"
        fi
        if [ -x "$jh/bin/java" ]; then
            printf '%s\n' "$jh/bin/java"
            return 0
        fi
        if [ -x "$jh/bin/java.exe" ]; then
            printf '%s\n' "$jh/bin/java.exe"
            return 0
        fi
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    return 1
}

check_jdk_25() {
    local java_bin
    if ! java_bin="$(resolve_java_bin)"; then
        echo "Error: Java runtime not found. procomp requires JDK 25 or newer." >&2
        echo "Install JDK 25+ and ensure JAVA_HOME or PATH points to it." >&2
        exit 1
    fi

    local version_line raw_ver major
    version_line="$("$java_bin" -version 2>&1 | head -n 1 || true)"
    raw_ver="$(printf '%s\n' "$version_line" | sed -n 's/.*"\([^"]*\)".*/\1/p')"
    if [ -z "$raw_ver" ]; then
        echo "Error: Could not parse Java version from '$java_bin -version' output: $version_line" >&2
        echo "procomp requires JDK 25 or newer." >&2
        exit 1
    fi

    major="${raw_ver%%[^0-9]*}"
    if [ "$major" = "1" ]; then
        local rest="${raw_ver#1.}"
        major="${rest%%[^0-9]*}"
    fi

    if [ -z "$major" ] || ! [ "$major" -ge 25 ] 2>/dev/null; then
        echo "Error: Found Java $raw_ver ($java_bin), but procomp requires JDK 25 or newer." >&2
        exit 1
    fi
}

suggest_path_bin_dirs() {
    local IFS=':'
    local seen=":"
    local entry norm
    for entry in ${PATH:-}; do
        [ -z "$entry" ] && continue
        norm="$(normalize_path "$entry")"
        case "$norm" in
            "$HOME"/*)
                if [ -d "$norm" ] && [ -w "$norm" ]; then
                    case "$seen" in
                        *":$norm:"*) ;;
                        *)
                            seen="${seen}${norm}:"
                            printf '%s\n' "$norm"
                            ;;
                    esac
                fi
                ;;
        esac
    done
}

is_dir_on_path() {
    local target
    target="$(normalize_path "$1")"
    local IFS=':'
    local entry norm
    for entry in ${PATH:-}; do
        [ -z "$entry" ] && continue
        norm="$(normalize_path "$entry")"
        if [ "$norm" = "$target" ]; then
            return 0
        fi
    done
    return 1
}

compute_version_string() {
    local stamp
    stamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    if command -v git >/dev/null 2>&1 && git -C "$SCRIPT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        local tag sha dirty
        tag="$(git -C "$SCRIPT_DIR" describe --tags --exact-match 2>/dev/null || echo "dev")"
        sha="$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
        dirty=""
        if [ -n "$(git -C "$SCRIPT_DIR" status --porcelain 2>/dev/null || true)" ]; then
            dirty="+dirty"
        fi
        printf '%s (%s%s, installed %s)\n' "$tag" "$sha" "$dirty" "$stamp"
    else
        printf 'dev (installed %s)\n' "$stamp"
    fi
}

write_uninstall_script() {
    local target_file="$1"
    cat > "$target_file" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MARKER="$INSTALL_DIR/.procomp-install"
BIN_DIR_OVERRIDE=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --bin-dir)
            if [ "$#" -lt 2 ] || [ -z "$2" ]; then
                echo "Error: --bin-dir requires a path argument." >&2
                exit 2
            fi
            BIN_DIR_OVERRIDE="$2"
            shift 2
            ;;
        --bin-dir=*)
            BIN_DIR_OVERRIDE="${1#--bin-dir=}"
            shift
            ;;
        *)
            echo "Error: Unknown option '$1'." >&2
            exit 2
            ;;
    esac
done

if [ ! -f "$MARKER" ]; then
    echo "Error: '$INSTALL_DIR' does not contain a .procomp-install marker. Refusing to generate uninstall commands." >&2
    exit 1
fi

BIN_DIR=""
while IFS='=' read -r key value; do
    case "$key" in
        BIN_DIR) BIN_DIR="$value" ;;
    esac
done < "$MARKER"

if [ -n "$BIN_DIR_OVERRIDE" ]; then
    BIN_DIR="$BIN_DIR_OVERRIDE"
fi

echo "procomp uninstall (dry-run — no files were deleted)"
echo ""
echo "Run the following commands to remove procomp:"

if [ -n "$BIN_DIR" ]; then
    if [ -f "$BIN_DIR/procomp" ] && grep -q "# procomp-shim" "$BIN_DIR/procomp" 2>/dev/null && grep -Fq "$INSTALL_DIR" "$BIN_DIR/procomp" 2>/dev/null; then
        printf '  rm -f "%s"\n' "$BIN_DIR/procomp"
    fi
    if [ -f "$BIN_DIR/procomp.cmd" ] && grep -q "# procomp-shim" "$BIN_DIR/procomp.cmd" 2>/dev/null && grep -Fq "$INSTALL_DIR" "$BIN_DIR/procomp.cmd" 2>/dev/null; then
        printf '  rm -f "%s"\n' "$BIN_DIR/procomp.cmd"
    fi
fi

printf '  rm -rf "%s"\n' "$INSTALL_DIR"
echo ""
echo "Note: Per-Project state (~/.local/state/procomp or %LOCALAPPDATA%\\procomp) is untouched."
EOF
    chmod +x "$target_file"
}

run_uninstall() {
    local target_dir="${INSTALL_DIR:-$DEFAULT_INSTALL_DIR}"
    target_dir="$(normalize_path "$target_dir")"
    local marker="$target_dir/.procomp-install"

    if [ ! -f "$marker" ] || [ ! -x "$target_dir/uninstall.sh" ]; then
        echo "Error: No procomp installation found at '$target_dir' (missing .procomp-install or uninstall.sh)." >&2
        exit 1
    fi

    if [ -n "$BIN_DIR" ]; then
        "$target_dir/uninstall.sh" --bin-dir "$(normalize_path "$BIN_DIR")"
    else
        "$target_dir/uninstall.sh"
    fi
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --dir)
            if [ "$#" -lt 2 ] || [ -z "$2" ]; then
                echo "Error: --dir requires a path argument." >&2
                exit 2
            fi
            INSTALL_DIR="$2"
            shift 2
            ;;
        --dir=*)
            INSTALL_DIR="${1#--dir=}"
            if [ -z "$INSTALL_DIR" ]; then
                echo "Error: --dir= requires a non-empty path." >&2
                exit 2
            fi
            shift
            ;;
        --bin-dir)
            if [ "$#" -lt 2 ] || [ -z "$2" ]; then
                echo "Error: --bin-dir requires a path argument." >&2
                exit 2
            fi
            BIN_DIR="$2"
            shift 2
            ;;
        --bin-dir=*)
            BIN_DIR="${1#--bin-dir=}"
            if [ -z "$BIN_DIR" ]; then
                echo "Error: --bin-dir= requires a non-empty path." >&2
                exit 2
            fi
            shift
            ;;
        -y|--yes)
            ASSUME_YES=1
            shift
            ;;
        --uninstall)
            UNINSTALL=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Error: Unknown option '$1'. Run './install.sh --help' for usage." >&2
            exit 2
            ;;
    esac
done

if [ "$UNINSTALL" -eq 1 ]; then
    run_uninstall
    exit 0
fi

# Verify source payload exists in repo checkout
if [ ! -d "$SCRIPT_DIR/src" ] || [ ! -d "$SCRIPT_DIR/web" ] || [ ! -f "$SCRIPT_DIR/procomp" ] || [ ! -f "$SCRIPT_DIR/procomp.cmd" ]; then
    echo "Error: Expected src/, web/, procomp, and procomp.cmd in '$SCRIPT_DIR'." >&2
    exit 1
fi

# Pre-flight: strictly require JDK 25+
check_jdk_25

# Resolve install and bin directories (interactive prompt when TTY and not --yes)
if [ "$ASSUME_YES" -eq 0 ] && [ -t 0 ]; then
    default_dir="${INSTALL_DIR:-$DEFAULT_INSTALL_DIR}"
    printf 'Install directory [%s]: ' "$default_dir"
    IFS= read -r user_dir || true
    INSTALL_DIR="${user_dir:-$default_dir}"

    suggestions="$(suggest_path_bin_dirs || true)"
    if [ -n "$suggestions" ]; then
        echo "Detected writable user directories already on PATH:"
        while IFS= read -r dir_opt; do
            [ -n "$dir_opt" ] && printf '  - %s\n' "$dir_opt"
        done <<< "$suggestions"
    fi
    default_bin="${BIN_DIR:-$DEFAULT_BIN_DIR}"
    printf 'Bin directory for shims [%s]: ' "$default_bin"
    IFS= read -r user_bin || true
    BIN_DIR="${user_bin:-$default_bin}"
else
    INSTALL_DIR="${INSTALL_DIR:-$DEFAULT_INSTALL_DIR}"
    BIN_DIR="${BIN_DIR:-$DEFAULT_BIN_DIR}"
fi

INSTALL_DIR="$(normalize_path "$INSTALL_DIR")"
BIN_DIR="$(normalize_path "$BIN_DIR")"

# Safety guard: refuse non-empty directory that is not a procomp install
if [ -e "$INSTALL_DIR" ]; then
    if [ ! -d "$INSTALL_DIR" ]; then
        echo "Error: '$INSTALL_DIR' exists and is not a directory." >&2
        exit 1
    fi
    if [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null || true)" ] && [ ! -f "$INSTALL_DIR/.procomp-install" ]; then
        echo "Error: '$INSTALL_DIR' is non-empty and does not contain a .procomp-install marker." >&2
        echo "Refusing to modify an unrelated directory. Choose an empty directory or an existing procomp install." >&2
        exit 1
    fi
fi

# Stage new installation
mkdir -p "$(dirname "$INSTALL_DIR")"
STAGE_DIR="${INSTALL_DIR}.new.$$"
mkdir -p "$STAGE_DIR"

cp -R "$SCRIPT_DIR/src" "$STAGE_DIR/src"
cp -R "$SCRIPT_DIR/web" "$STAGE_DIR/web"
cp "$SCRIPT_DIR/procomp" "$STAGE_DIR/procomp"
cp "$SCRIPT_DIR/procomp.cmd" "$STAGE_DIR/procomp.cmd"
chmod +x "$STAGE_DIR/procomp"

VERSION_STR="$(compute_version_string)"
printf '%s\n' "$VERSION_STR" > "$STAGE_DIR/VERSION"

cat > "$STAGE_DIR/.procomp-install" <<EOF
INSTALL_DIR=$INSTALL_DIR
BIN_DIR=$BIN_DIR
VERSION=$VERSION_STR
EOF

write_uninstall_script "$STAGE_DIR/uninstall.sh"

# Swap staged directory into place (never running rm on user directories)
BACKUP_DIR=""
if [ -d "$INSTALL_DIR" ]; then
    if [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null || true)" ]; then
        BACKUP_DIR="${INSTALL_DIR}.bak-$(date -u +"%Y%m%dT%H%M%SZ")"
        if [ -e "$BACKUP_DIR" ]; then
            BACKUP_DIR="${BACKUP_DIR}-$$"
        fi
        mv "$INSTALL_DIR" "$BACKUP_DIR"
        if ! mv "$STAGE_DIR" "$INSTALL_DIR"; then
            mv "$BACKUP_DIR" "$INSTALL_DIR" || true
            echo "Error: Failed to move staged installation into '$INSTALL_DIR'." >&2
            exit 1
        fi
    else
        rmdir "$INSTALL_DIR"
        mv "$STAGE_DIR" "$INSTALL_DIR"
    fi
else
    mv "$STAGE_DIR" "$INSTALL_DIR"
fi

# Write thin owned shims in BIN_DIR
mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/procomp" <<EOF
#!/usr/bin/env sh
# procomp-shim
# PROCOMP_INSTALL_DIR=$INSTALL_DIR
set -eu
exec "$INSTALL_DIR/procomp" "\$@"
EOF
chmod +x "$BIN_DIR/procomp"

if is_windows_bash; then
    WIN_INSTALL_DIR="$(to_windows_path "$INSTALL_DIR")"
    printf '@echo off\r\nrem # procomp-shim\r\nrem PROCOMP_INSTALL_DIR=%s\r\ncall "%s\\procomp.cmd" %%*\r\nexit /b %%ERRORLEVEL%%\r\n' \
        "$INSTALL_DIR" "$WIN_INSTALL_DIR" > "$BIN_DIR/procomp.cmd"
fi

# Post-install smoke check
SMOKE_OUT="$("$BIN_DIR/procomp" --version)"

echo "Installed: $SMOKE_OUT"
echo "  Install dir: $INSTALL_DIR"
echo "  Shim:        $BIN_DIR/procomp"
if is_windows_bash; then
    echo "  Windows shim: $BIN_DIR/procomp.cmd"
fi
echo "  Uninstall:   $INSTALL_DIR/uninstall.sh"

if [ -n "$BACKUP_DIR" ]; then
    echo ""
    echo "Previous installation was moved aside to:"
    echo "  $BACKUP_DIR"
    echo "To remove the backup, run:"
    printf '  rm -rf "%s"\n' "$BACKUP_DIR"
fi

if ! is_dir_on_path "$BIN_DIR"; then
    echo ""
    echo "Note: '$BIN_DIR' is not currently on your PATH."
    echo "Add it to your shell profile (~/.bashrc or ~/.zshrc):"
    printf '  export PATH="%s:$PATH"\n' "$BIN_DIR"
    if is_windows_bash; then
        WIN_BIN_DIR="$(to_windows_path "$BIN_DIR")"
        echo "To also use procomp from PowerShell/CMD, run in PowerShell:"
        printf '  [Environment]::SetEnvironmentVariable("Path", [Environment]::GetEnvironmentVariable("Path", "User") + ";%s", "User")\n' "$WIN_BIN_DIR"
    fi
fi
