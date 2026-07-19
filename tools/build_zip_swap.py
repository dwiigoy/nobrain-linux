#!/usr/bin/env python3
"""
Build APK using ZIP swap pipeline:
1. Binary patch DEX: com/nobrain/linux -> com/nobrain/panel
2. Recalculate DEX header SHA-1 + Adler32
3. Clone base APK, replace classes.dex
4. Patch AndroidManifest.xml: add screenLayout flag to configChanges + patch icon ref
5. Rebuild resources.arsc to add mipmap type + ic_launcher entries
6. Add mipmap PNG files (resized from ICON_SRC)
7. Output: nobrain-unsigned.apk (ready for zipalign+sign)
"""
import struct, zipfile, hashlib, shutil, os, sys, io, tarfile

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.environ.get(
    "NOBRAIN_BUILD_DIR", os.path.join(PROJECT_ROOT, "android/app/build")
)
BASE_APK = os.environ.get("NOBRAIN_BASE_APK", os.path.join(BUILD, "base.apk"))
DEX_IN   = f"{BUILD}/dex/classes.dex"
OUT_APK  = f"{BUILD}/nobrain-unsigned.apk"
NEW_ROOTFS = os.environ.get(
    "NOBRAIN_ROOTFS",
    os.path.join(PROJECT_ROOT, "android/app/src/main/assets/rootfs.tar.gz"),
)
STAGING_ROOTFS = os.environ.get(
    "NOBRAIN_STAGING_ROOTFS", os.path.join(PROJECT_ROOT, "rootfs/staging")
)
NEW_XLORIE_SO = os.environ.get(
    "NOBRAIN_XLORIE_SO",
    os.path.join(PROJECT_ROOT, "android/app/src/main/jniLibs/arm64-v8a/libXlorie.so"),
)
RUNTIME_ASSETS = {
    f"assets/{name}": os.path.join(PROJECT_ROOT, "runtime", name)
    for name in (
        "chromium",
        "install-wps",
        "nobrain-chromium-shutdown",
        "nobrain-dialog-raise.c",
        "nobrain-ssh-access",
        "nobrain-menu-core",
    )
}
RUNTIME_ASSETS.update({
    f"assets/dwm-src/{name}": os.path.join(PROJECT_ROOT, "dwm", name)
    for name in ("Makefile", "config.mk", "config.h", "drw.c", "drw.h", "dwm.c", "util.c", "util.h")
})
NATIVE_LIB_DIR = os.path.dirname(NEW_XLORIE_SO)
ICON_SRC = os.environ.get(
    "NOBRAIN_ICON", os.path.join(PROJECT_ROOT, "assets/nobrain-icon.png")
)

# mipmap densities: (dir_suffix, density_dpi, icon_size_px)
ICON_DENSITIES = [
    ('mdpi',    160,  48),
    ('hdpi',    240,  72),
    ('xhdpi',   320,  96),
    ('xxhdpi',  480, 144),
    ('xxxhdpi', 640, 192),
]

# resource.arsc string pools after adding mipmap
_GLOBAL_STRINGS = (
    ['res/layout/activity_main.xml'] +
    [f'res/mipmap-{d}/ic_launcher.png' for d, _, _ in ICON_DENSITIES]
)
_TYPE_STRINGS = ['id', 'layout', 'style', 'mipmap']
_KEY_STRINGS  = ['progress_bar', 'start_button', 'status_text', 'webview',
                  'activity_main', 'AppTheme', 'ic_launcher']

IC_LAUNCHER_KEY_IDX = 6   # index of 'ic_launcher' in _KEY_STRINGS
MIPMAP_TYPE_IDX     = 4   # 1-based type index for 'mipmap' (= 4th type string + 1)
MIPMAP_RES_ID       = 0x7F040000  # package 0x7F, type 4, entry 0

# manifest Res_value to search+replace for android:icon attribute
_ICON_OLD_VAL = struct.pack('<HBBI', 8, 0, 0x01, 0x01080148)  # TYPE_REFERENCE → framework icon
_ICON_NEW_VAL = struct.pack('<HBBI', 8, 0, 0x01, MIPMAP_RES_ID)

OLD_PKG = b"com/nobrain/linux"   # 17 bytes
NEW_PKG = b"com/nobrain/panel"   # 17 bytes — same length, no reoffset needed
TARGET_VERSION_CODE = 3
TARGET_VERSION_NAME = "0.1.1"
assert len(OLD_PKG) == len(NEW_PKG)

# libXlorie.so .data section hardcoded screen resolution offsets
# Found via binary analysis: int32 LE at these file offsets
XLORIE_W_OFF = 3304732   # currently 540
XLORIE_H_OFF = 3304736   # currently 1144
XLORIE_W_NEW = 1100
XLORIE_H_NEW = 800

REQUIRED_ROOTFS_EXECUTABLES = [
    "usr/bin/xbps-install",
    "usr/bin/gcc",
    "usr/bin/g++",
    "usr/bin/ld.bfd",
    "usr/bin/getconf",
    "usr/bin/ldd",
    "usr/bin/cmake",
]

REQUIRED_ROOTFS_PATHS = REQUIRED_ROOTFS_EXECUTABLES + [
    "usr/bin/ld",
    "usr/lib/gcc/aarch64-linux-gnu/14.2/crtbeginS.o",
    "usr/lib/gcc/aarch64-linux-gnu/14.2/libgcc.a",
]

REQUIRED_ROOTFS_ARCHIVE_PRESENT_PATHS = REQUIRED_ROOTFS_PATHS + [
    "usr/lib/getconf/POSIX_V6_LP64_OFF64",
    "usr/lib/getconf/POSIX_V7_LP64_OFF64",
    "usr/lib/getconf/XBS5_LP64_OFF64",
    "usr/lib/gconv/gconv-modules",
    "usr/lib/gconv/gconv-modules.d/gconv-modules-extra.conf",
    "usr/lib/gconv/UTF-16.so",
    "usr/lib/gconv/UTF-32.so",
    "usr/lib/gconv/ISO8859-1.so",
]

REQUIRED_ROOTFS_ARCHIVE_REGULAR_FILES = REQUIRED_ROOTFS_EXECUTABLES + [
    "usr/lib/gcc/aarch64-linux-gnu/14.2/crtbeginS.o",
    "usr/lib/gcc/aarch64-linux-gnu/14.2/libgcc.a",
    "usr/lib/getconf/POSIX_V6_LP64_OFF64",
    "usr/lib/getconf/POSIX_V7_LP64_OFF64",
    "usr/lib/getconf/XBS5_LP64_OFF64",
    "usr/lib/gconv/gconv-modules",
    "usr/lib/gconv/gconv-modules.d/gconv-modules-extra.conf",
    "usr/lib/gconv/UTF-16.so",
    "usr/lib/gconv/UTF-32.so",
    "usr/lib/gconv/ISO8859-1.so",
]

FORBIDDEN_PUBLIC_ROOTFS_PATHS = [
    "usr/local/bin/install-codex",
    "usr/local/bin/install-steamcmd",
    "usr/local/bin/nobrain-stardew",
    "usr/local/bin/nobrain-game-ime-bridge",
]


def is_forbidden_public_rootfs_path(rel):
    normalized = rel.strip("/")
    if normalized in FORBIDDEN_PUBLIC_ROOTFS_PATHS:
        return True
    return any("sxmo" in part.lower() for part in normalized.split("/"))


def validate_rootfs_gate():
    """Fail the build if the staging rootfs is missing core repair/toolchain files."""
    print("\n[0] Validating staging rootfs gate")
    missing = []
    not_exec = []
    for rel in REQUIRED_ROOTFS_PATHS:
        path = os.path.join(STAGING_ROOTFS, rel)
        if not os.path.exists(path):
            missing.append(rel)
            continue
        if rel in REQUIRED_ROOTFS_EXECUTABLES and not os.access(path, os.X_OK):
            not_exec.append(rel)
    if missing or not_exec:
        if missing:
            print("  Missing required rootfs paths:")
            for rel in missing:
                print(f"    - {rel}")
        if not_exec:
            print("  Required rootfs files are not executable:")
            for rel in not_exec:
                print(f"    - {rel}")
        raise SystemExit("rootfs gate failed")
    forbidden_staging = []
    for current, dirs, files in os.walk(STAGING_ROOTFS):
        for name in dirs + files:
            path = os.path.join(current, name)
            rel = os.path.relpath(path, STAGING_ROOTFS)
            if is_forbidden_public_rootfs_path(rel):
                forbidden_staging.append(rel)
    if forbidden_staging:
        print("  Forbidden public rootfs paths in staging:")
        for rel in forbidden_staging:
            print(f"    - {rel}")
        raise SystemExit("rootfs gate failed; staging contains private or obsolete files")
    print("  OK: xbps, gcc/g++, linker, glibc helpers, and cmake are present")

    print("[0b] Validating rootfs archive gate")
    archive_members = {}
    forbidden_archive = []
    try:
        with tarfile.open(NEW_ROOTFS, "r:gz") as tf:
            for member in tf:
                normalized = member.name.lstrip("./")
                if normalized in REQUIRED_ROOTFS_ARCHIVE_PRESENT_PATHS:
                    archive_members[normalized] = member
                if is_forbidden_public_rootfs_path(normalized):
                    forbidden_archive.append(normalized)
    except (tarfile.TarError, OSError) as exc:
        raise SystemExit(f"rootfs archive gate failed: cannot read {NEW_ROOTFS}: {exc}")

    archive_missing = []
    archive_nonregular = []
    for rel in REQUIRED_ROOTFS_ARCHIVE_PRESENT_PATHS:
        member = archive_members.get(rel)
        if member is None:
            archive_missing.append(rel)
        elif rel in REQUIRED_ROOTFS_ARCHIVE_REGULAR_FILES and not member.isfile():
            archive_nonregular.append(f"{rel} ({member.type!r})")

    if archive_missing:
        print("  Missing from rootfs.tar.gz:")
        for rel in archive_missing:
            print(f"    - {rel}")
    if archive_nonregular:
        print("  Not regular files in rootfs.tar.gz:")
        for rel in archive_nonregular:
            print(f"    - {rel}")
    if forbidden_archive:
        print("  Forbidden public rootfs paths in rootfs.tar.gz:")
        for rel in forbidden_archive:
            print(f"    - {rel}")
    if archive_missing or archive_nonregular or forbidden_archive:
        raise SystemExit("rootfs archive gate failed; fix forbidden or hardlink entries")
    print("  OK: rootfs.tar.gz contains required files as regular entries")


# ── resources.arsc helpers ────────────────────────────────────────────────────

def _build_utf16_pool(strings):
    """ResStringPool with UTF-16LE strings (used for type strings)."""
    encoded = [struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\x00\x00'
               for s in strings]
    str_data, offsets = b'', []
    for e in encoded:
        offsets.append(len(str_data))
        str_data += e
    while len(str_data) % 4:
        str_data += b'\x00'
    off_bytes = struct.pack(f'<{len(offsets)}I', *offsets)
    strs_start = 28 + len(off_bytes)
    total = strs_start + len(str_data)
    hdr = struct.pack('<HHIIIIII', 0x0001, 28, total, len(strings), 0, 0, strs_start, 0)
    return hdr + off_bytes + str_data


def _build_utf8_pool(strings):
    """ResStringPool with UTF-8 strings (used for key and global strings)."""
    def _enc_len(n):
        return bytes([(n >> 8) | 0x80, n & 0xFF]) if n > 0x7F else bytes([n])
    encoded = [_enc_len(len(s)) + _enc_len(len(s.encode('utf-8'))) +
               s.encode('utf-8') + b'\x00' for s in strings]
    str_data, offsets = b'', []
    for e in encoded:
        offsets.append(len(str_data))
        str_data += e
    while len(str_data) % 4:
        str_data += b'\x00'
    off_bytes = struct.pack(f'<{len(offsets)}I', *offsets)
    strs_start = 28 + len(off_bytes)
    total = strs_start + len(str_data)
    hdr = struct.pack('<HHIIIIII', 0x0001, 28, total, len(strings), 0, 1 << 8, strs_start, 0)
    return hdr + off_bytes + str_data


def _build_typespec(type_id, count):
    """ResTable_typeSpec chunk with all spec-flags set to 0."""
    total = 16 + count * 4
    chunk = struct.pack('<HHI', 0x0202, 16, total)
    chunk += struct.pack('<BBHI', type_id, 0, 0, count)
    chunk += b'\x00' * (count * 4)
    return chunk


def _build_mipmap_type(density, global_str_idx):
    """ResTable_type chunk for one mipmap density with a single ic_launcher entry."""
    type_id    = MIPMAP_TYPE_IDX
    config_sz  = 64
    hdr_sz     = 20 + config_sz   # 84
    entries_st = hdr_sz + 4       # 88 (1 offset × 4 bytes after header)

    config = bytearray(config_sz)
    struct.pack_into('<I', config, 0, config_sz)   # config.size
    struct.pack_into('<H', config, 14, density)    # config.density

    # ResTable_entry (simple): size=8, flags=0, key=IC_LAUNCHER_KEY_IDX
    entry = struct.pack('<HHI', 8, 0, IC_LAUNCHER_KEY_IDX)
    # Res_value: size=8, res0=0, dataType=TYPE_STRING(0x03), data=global_str_idx
    value = struct.pack('<HBBI', 8, 0, 0x03, global_str_idx)
    entry_data = entry + value   # 16 bytes

    total = entries_st + len(entry_data)  # 104

    chunk = struct.pack('<HHI', 0x0201, hdr_sz, total)
    chunk += struct.pack('<BBHI', type_id, 0, 0, 1)       # id, flags, reserved, entryCount
    chunk += struct.pack('<I', entries_st)                  # entriesStart
    chunk += bytes(config)
    chunk += struct.pack('<I', 0)                           # offset[0] = 0
    chunk += entry_data
    return chunk


# Splash screen attribute IDs (Android API 31+, from R$attr):
# windowSplashScreenBackground          = 0x0101062C
# windowSplashScreenIconBackgroundColor = 0x01010630
# We replace statusBarColor (0x01010451) and navigationBarColor (0x01010452) entries
# with these — both set to #1a1a2e (same as windowBackground) so splash is invisible.
# Since the app runs IMMERSIVE_STICKY, status/nav bar colors are never visible anyway.
_SPLASH_BG_REPLACE = (
    bytes([0x51, 0x04, 0x01, 0x01, 0x08, 0x00, 0x00, 0x1d, 0x3e, 0x21, 0x16, 0xff]),  # old: statusBarColor=#16213e
    bytes([0x2C, 0x06, 0x01, 0x01, 0x08, 0x00, 0x00, 0x1d, 0x2e, 0x1a, 0x1a, 0xff]),  # new: windowSplashScreenBackground=#1a1a2e
)
_SPLASH_ICON_REPLACE = (
    bytes([0x52, 0x04, 0x01, 0x01, 0x08, 0x00, 0x00, 0x1d, 0x3e, 0x21, 0x16, 0xff]),  # old: navigationBarColor=#16213e
    bytes([0x30, 0x06, 0x01, 0x01, 0x08, 0x00, 0x00, 0x1d, 0x2e, 0x1a, 0x1a, 0xff]),  # new: windowSplashScreenIconBackgroundColor=#1a1a2e
)

def patch_splash_screen_arsc(chunk: bytes) -> bytes:
    """Replace statusBarColor/navigationBarColor with splash screen attrs in style chunk."""
    chunk = chunk.replace(_SPLASH_BG_REPLACE[0], _SPLASH_BG_REPLACE[1])
    chunk = chunk.replace(_SPLASH_ICON_REPLACE[0], _SPLASH_ICON_REPLACE[1])
    return chunk


def build_arsc(orig_arsc: bytes) -> bytes:
    """Rebuild resources.arsc adding a 'mipmap' type with ic_launcher entries."""
    # Verbatim copy of existing TYPESPEC+TYPE chunks for id/layout/style (480 bytes)
    existing_chunks = patch_splash_screen_arsc(orig_arsc[580:1060])

    new_global_pool = _build_utf8_pool(_GLOBAL_STRINGS)
    new_type_pool   = _build_utf16_pool(_TYPE_STRINGS)
    new_key_pool    = _build_utf8_pool(_KEY_STRINGS)

    # TYPESPEC + TYPE per density for mipmap
    mipmap_chunks = _build_typespec(MIPMAP_TYPE_IDX, 1)
    for i, (_, density, _) in enumerate(ICON_DENSITIES):
        global_idx = 1 + i   # global string indices 1-5 for mipmap paths
        mipmap_chunks += _build_mipmap_type(density, global_idx)

    # Package payload (everything after the 288-byte pkg header)
    type_strings_off = 288
    key_strings_off  = 288 + len(new_type_pool)
    pkg_payload = new_type_pool + new_key_pool + existing_chunks + mipmap_chunks
    pkg_total   = 288 + len(pkg_payload)

    pkg_name_bytes = 'com.nobrain.panel'.encode('utf-16-le').ljust(256, b'\x00')
    pkg_header = struct.pack('<HHII', 0x0200, 288, pkg_total, 0x7F)
    pkg_header += pkg_name_bytes
    pkg_header += struct.pack('<IIIII', type_strings_off, 0, key_strings_off, 0, 0)

    pkg_chunk = pkg_header + pkg_payload

    res_total = 12 + len(new_global_pool) + len(pkg_chunk)
    res_header = struct.pack('<HHII', 0x0002, 12, res_total, 1)
    return res_header + new_global_pool + pkg_chunk


def generate_icons() -> dict:
    """Resize ICON_SRC to all mipmap densities. Returns {density_name: png_bytes}."""
    try:
        from PIL import Image
    except ImportError:
        print('  WARNING: Pillow not available — skipping icon generation')
        return {}
    if not os.path.exists(ICON_SRC):
        print(f'  WARNING: {ICON_SRC} not found — skipping icon generation')
        return {}
    img = Image.open(ICON_SRC).convert('RGBA')
    result = {}
    for dname, _, size in ICON_DENSITIES:
        resized = img.resize((size, size), Image.LANCZOS)
        buf = io.BytesIO()
        resized.save(buf, 'PNG')
        result[dname] = buf.getvalue()
    return result


def patch_dex(data: bytes) -> bytes:
    data = bytearray(data)
    count = 0
    i = 0
    while True:
        idx = data.find(OLD_PKG, i)
        if idx == -1:
            break
        data[idx:idx+len(OLD_PKG)] = NEW_PKG
        count += 1
        i = idx + len(NEW_PKG)
    print(f"  DEX: replaced {count} occurrences of package name")

    # Recalculate SHA-1 (bytes 32..end)
    sha1 = hashlib.sha1(bytes(data[32:])).digest()
    data[12:32] = sha1
    print(f"  DEX: SHA-1 = {sha1.hex()}")

    # Recalculate Adler32 (bytes 12..end)
    adler = _adler32(bytes(data[12:]))
    struct.pack_into('<I', data, 8, adler)
    print(f"  DEX: Adler32 = {adler:#010x}")

    return bytes(data)


def _adler32(data: bytes) -> int:
    MOD = 65521
    a, b = 1, 0
    for byte in data:
        a = (a + byte) % MOD
        b = (b + a) % MOD
    return (b << 16) | a


def patch_xlorie_so(data: bytes) -> bytes:
    """Patch hardcoded screen resolution in libXlorie.so .data section."""
    data = bytearray(data)
    old_w = struct.unpack_from('<I', data, XLORIE_W_OFF)[0]
    old_h = struct.unpack_from('<I', data, XLORIE_H_OFF)[0]
    struct.pack_into('<I', data, XLORIE_W_OFF, XLORIE_W_NEW)
    struct.pack_into('<I', data, XLORIE_H_OFF, XLORIE_H_NEW)
    print(f"  libXlorie.so: resolution {old_w}x{old_h} -> {XLORIE_W_NEW}x{XLORIE_H_NEW}")
    return bytes(data)


def patch_manifest(data: bytes) -> bytes:
    """Patch binary AXML manifest:
    1. OR additional configChanges flags (screenLayout etc.)
    2. Replace android:icon reference to point to our app-defined mipmap resource.
    3. Set the release version code/name for the ZIP-swap build.
    4. Fix resource ID map: remap 'icon' string from android:versionCode (0x0101021B)
       to android:icon (0x01010002) so PackageParser recognises the attribute.
    """
    data = bytearray(data)

    # configChanges attributes: name_idx=4, ns=37, verified offsets:
    # data field at 3564 = 0x2ca0
    # data field at 3584 = 0x2cb0
    CFG_OFFSETS = [3564, 3584]
    # Union of all ActivityInfo.CONFIG_* flags except CONFIG_ASSETS_PATHS (0x80000000):
    # mcc|mnc|locale|touchscreen|keyboard|keyboardHidden|navigation|orientation|
    # screenLayout|uiMode|screenSize|smallestScreenSize|density|layoutDirection|fontScale
    CONFIG_MASK = 0x40003FFF

    for off in CFG_OFFSETS:
        old = struct.unpack_from('<I', data, off)[0]
        new = old | CONFIG_MASK
        struct.pack_into('<I', data, off, new)
        print(f"  Manifest: configChanges @ {off}: {old:#010x} -> {new:#010x}")

    # Patch android:icon Res_value to reference our mipmap resource
    count = bytes(data).count(_ICON_OLD_VAL)
    if count == 0:
        print("  WARNING: icon attribute pattern not found in manifest — skipping icon patch")
    else:
        idx = bytes(data).find(_ICON_OLD_VAL)
        data[idx:idx + len(_ICON_OLD_VAL)] = _ICON_NEW_VAL
        print(f"  Manifest: android:icon value @ {idx}: 0x01080148 -> 0x{MIPMAP_RES_ID:08X}")

    # Fix resource ID map: the AXML string pool has 'icon' mapped to resource ID
    # 0x0101021B (android:versionCode) due to a build-tool quirk. Android's
    # PackageParser looks for attribute 0x01010002 (android:icon), so without this
    # fix the icon attribute is never recognised and no launcher icon appears.
    ATTR_ICON_CORRECT = 0x01010002
    ATTR_VERSIONCODE  = 0x0101021B

    sp_off        = 8   # string pool starts right after 8-byte AXML header
    # ResStringPool_header: type(H), hdrSz(H), total(I), strCount(I),
    #   styleCount(I), flags(I), stringsStart(I), stylesStart(I) = 28 bytes
    sp_hdr_sz_val = struct.unpack_from('<H', data, sp_off + 2)[0]
    sp_total_val  = struct.unpack_from('<I', data, sp_off + 4)[0]
    sp_cnt_val    = struct.unpack_from('<I', data, sp_off + 8)[0]
    sp_flags_val  = struct.unpack_from('<I', data, sp_off + 16)[0]  # flags at +16
    sp_str_start  = struct.unpack_from('<I', data, sp_off + 20)[0]  # stringsStart at +20
    abs_str_val   = sp_off + sp_str_start
    is_utf8_val   = bool(sp_flags_val & 0x100)

    if struct.unpack_from('<I', data, sp_off + 12)[0] != 0:
        raise SystemExit("Manifest string styles are unsupported by the ZIP-swap version patch")

    def _read_len8(buf, pos):
        first = buf[pos]
        return (((first & 0x7f) << 8) | buf[pos + 1], pos + 2) if first & 0x80 else (first, pos + 1)

    def _write_len8(value):
        return bytes((0x80 | (value >> 8), value & 0xff)) if value > 0x7f else bytes((value,))

    def _read_len16(buf, pos):
        first = struct.unpack_from('<H', buf, pos)[0]
        if first & 0x8000:
            second = struct.unpack_from('<H', buf, pos + 2)[0]
            return (((first & 0x7fff) << 16) | second, pos + 4)
        return first, pos + 2

    def _write_len16(value):
        if value > 0x7fff:
            return struct.pack('<HH', 0x8000 | (value >> 16), value & 0xffff)
        return struct.pack('<H', value)

    strings = []
    for i in range(sp_cnt_val):
        rel = struct.unpack_from('<I', data, sp_off + sp_hdr_sz_val + i * 4)[0]
        pos = abs_str_val + rel
        if is_utf8_val:
            char_len, pos = _read_len8(data, pos)
            byte_len, pos = _read_len8(data, pos)
            strings.append(bytes(data[pos:pos + byte_len]).decode('utf-8'))
        else:
            char_len, pos = _read_len16(data, pos)
            strings.append(bytes(data[pos:pos + char_len * 2]).decode('utf-16-le'))

    version_indexes = [i for i, value in enumerate(strings) if value in ("0.1", "0.1.0", TARGET_VERSION_NAME)]
    if len(version_indexes) != 1:
        raise SystemExit(f"Manifest versionName string is ambiguous: {version_indexes}")
    strings[version_indexes[0]] = TARGET_VERSION_NAME

    rebuilt_strings = bytearray()
    rebuilt_offsets = []
    for value in strings:
        rebuilt_offsets.append(len(rebuilt_strings))
        encoded = value.encode('utf-8')
        if is_utf8_val:
            rebuilt_strings += _write_len8(len(value)) + _write_len8(len(encoded)) + encoded + b'\0'
        else:
            encoded16 = value.encode('utf-16-le')
            rebuilt_strings += _write_len16(len(value)) + encoded16 + b'\0\0'
    while len(rebuilt_strings) % 4:
        rebuilt_strings.append(0)

    old_sp_total = sp_total_val
    new_sp_total = sp_str_start + len(rebuilt_strings)
    pool_prefix = bytearray(data[sp_off:abs_str_val])
    struct.pack_into('<I', pool_prefix, 4, new_sp_total)
    for i, rel in enumerate(rebuilt_offsets):
        struct.pack_into('<I', pool_prefix, sp_hdr_sz_val + i * 4, rel)
    data[sp_off:sp_off + old_sp_total] = pool_prefix + rebuilt_strings
    struct.pack_into('<I', data, 4, len(data))
    sp_total_val = new_sp_total
    abs_str_val = sp_off + sp_str_start
    print(f"  Manifest: versionName -> {TARGET_VERSION_NAME}")

    rmap_off_val  = sp_off + sp_total_val
    rmap_ct       = struct.unpack_from('<H', data, rmap_off_val)[0]
    if rmap_ct == 0x0180:
        rmap_hdr_sz_val = struct.unpack_from('<H', data, rmap_off_val + 2)[0]
        rmap_total_val  = struct.unpack_from('<I', data, rmap_off_val + 4)[0]
        num_rmap_ids    = (rmap_total_val - rmap_hdr_sz_val) // 4
        for i in range(min(sp_cnt_val, num_rmap_ids)):
            off_i = struct.unpack_from('<I', data, sp_off + sp_hdr_sz_val + i * 4)[0]
            p = abs_str_val + off_i
            if is_utf8_val:
                bl = data[p + 1]
                s = bytes(data[p + 2:p + 2 + bl]).decode('utf-8', 'replace')
            else:
                cl = struct.unpack_from('<H', data, p)[0]
                s = bytes(data[p + 2:p + 2 + cl * 2]).decode('utf-16-le', 'replace')
            if s == 'icon':
                entry_pos = rmap_off_val + rmap_hdr_sz_val + i * 4
                old_rid   = struct.unpack_from('<I', data, entry_pos)[0]
                if old_rid == ATTR_VERSIONCODE:
                    struct.pack_into('<I', data, entry_pos, ATTR_ICON_CORRECT)
                    print(f"  Manifest: rmap[{i}]('icon') 0x{old_rid:08X} -> "
                          f"0x{ATTR_ICON_CORRECT:08X} (android:icon)")
                else:
                    print(f"  Manifest: rmap[{i}]('icon') = 0x{old_rid:08X} (already correct)")
    else:
        print(f"  WARNING: expected rmap chunk (0x0180) at {rmap_off_val}, got 0x{rmap_ct:04X}")

    def _pool_string(index):
        rel = struct.unpack_from('<I', data, sp_off + sp_hdr_sz_val + index * 4)[0]
        pos = abs_str_val + rel
        if is_utf8_val:
            _, pos = _read_len8(data, pos)
            byte_len, pos = _read_len8(data, pos)
            return bytes(data[pos:pos + byte_len]).decode('utf-8', 'replace')
        char_len, pos = _read_len16(data, pos)
        return bytes(data[pos:pos + char_len * 2]).decode('utf-16-le', 'replace')

    version_code_patched = False
    version_scan = rmap_off_val + rmap_total_val
    while version_scan < len(data) - 8:
        chunk_type, _, chunk_size = struct.unpack_from('<HHI', data, version_scan)
        if chunk_type == 0x0102:
            name_index = struct.unpack_from('<I', data, version_scan + 20)[0]
            if _pool_string(name_index) == 'manifest':
                attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, version_scan + 24)
                attr_base = version_scan + 16 + attr_start
                for i in range(attr_count):
                    attr_pos = attr_base + i * attr_size
                    attr_name_index = struct.unpack_from('<I', data, attr_pos + 4)[0]
                    if _pool_string(attr_name_index) == 'versionCode':
                        old_version = struct.unpack_from('<I', data, attr_pos + 16)[0]
                        struct.pack_into('<I', data, attr_pos + 16, TARGET_VERSION_CODE)
                        print(f"  Manifest: versionCode {old_version} -> {TARGET_VERSION_CODE}")
                        version_code_patched = True
                        break
                break
        if chunk_size == 0:
            break
        version_scan += chunk_size
    if not version_code_patched:
        raise SystemExit("Manifest versionCode attribute not found")

    # Sort <application> element attributes by resource ID.
    # Android's ApplyStyle() assumes XML attrs are in ascending resId order (like AAPT2
    # always generates). If android:icon (0x01010002) is after allowBackup (0x01010280),
    # the sorted-merge scan breaks early and never finds it.
    def _attr_rid(data_arr, a_pos):
        ni = struct.unpack_from('<I', data_arr, a_pos + 4)[0]
        if 0 <= ni < num_rmap_ids:
            return struct.unpack_from('<I', data_arr, rmap_off_val + rmap_hdr_sz_val + ni * 4)[0]
        return 0xFFFFFFFF

    elem_scan = rmap_off_val + rmap_total_val
    while elem_scan < len(data) - 8:
        ct3, hs3, csz3 = struct.unpack_from('<HHI', data, elem_scan)
        if ct3 == 0x0102:  # START_ELEMENT
            _, _, _, name_idx3 = struct.unpack_from('<IIII', data, elem_scan + 8)
            if 0 <= name_idx3 < sp_cnt_val:
                off_i3 = struct.unpack_from('<I', data, sp_off + sp_hdr_sz_val + name_idx3 * 4)[0]
                p3 = abs_str_val + off_i3
                if is_utf8_val:
                    elem_name = bytes(data[p3+2:p3+2+data[p3+1]]).decode('utf-8', 'replace')
                else:
                    cl3 = struct.unpack_from('<H', data, p3)[0]
                    elem_name = bytes(data[p3+2:p3+2+cl3*2]).decode('utf-16-le', 'replace')
                if elem_name == 'application':
                    astart3, asz3, acnt3 = struct.unpack_from('<HHH', data, elem_scan + 24)
                    ab3 = elem_scan + 16 + astart3
                    rids3 = [(_attr_rid(data, ab3 + i * asz3), i) for i in range(acnt3)]
                    needs_sort = any(rids3[i][0] > rids3[i+1][0] for i in range(len(rids3)-1))
                    if needs_sort:
                        order3 = [idx for _, idx in sorted(rids3, key=lambda x: x[0])]
                        blks3 = [bytes(data[ab3 + i*asz3 : ab3 + (i+1)*asz3]) for i in range(acnt3)]
                        data[ab3 : ab3 + acnt3 * asz3] = b''.join(blks3[j] for j in order3)
                        before = [f'0x{r:08x}' for r, _ in rids3]
                        after  = [f'0x{r:08x}' for r, _ in sorted(rids3, key=lambda x: x[0])]
                        print(f"  Manifest: sorted <application> attrs {before} -> {after}")
                    else:
                        print(f"  Manifest: <application> attrs already sorted")
                    break
        if csz3 == 0:
            break
        elem_scan += csz3

    return bytes(data)


def main():
    print("=== ZIP Swap Build ===")
    print()
    validate_rootfs_gate()

    # 1. Load and patch DEX
    print(f"[1] Patching DEX: {DEX_IN}")
    with open(DEX_IN, 'rb') as f:
        dex = f.read()
    print(f"  DEX size: {len(dex)} bytes")
    dex_patched = patch_dex(dex)

    # 2. Generate icon PNGs
    print(f"\n[2] Generating launcher icons from: {ICON_SRC}")
    icon_pngs = generate_icons()
    if icon_pngs:
        for dname, _, size in ICON_DENSITIES:
            print(f"  {dname}: {size}x{size}px ({len(icon_pngs[dname])} bytes)")
    else:
        print("  (icon generation skipped)")

    # 3. Build new resources.arsc
    print(f"\n[3] Rebuilding resources.arsc")
    with zipfile.ZipFile(BASE_APK, 'r') as z:
        orig_arsc = z.read('resources.arsc')
    new_arsc = build_arsc(orig_arsc)
    print(f"  resources.arsc: {len(orig_arsc)} -> {len(new_arsc)} bytes")
    print(f"  Splash screen: statusBarColor->windowSplashScreenBackground=#1a1a2e")
    print(f"  Splash screen: navBarColor->windowSplashScreenIconBackgroundColor=#1a1a2e")

    # 4. Build new APK
    print(f"\n[4] Assembling APK from base: {BASE_APK}")
    with zipfile.ZipFile(BASE_APK, 'r') as zin:
        names = zin.namelist()
        print(f"  Base APK has {len(names)} entries")

        with zipfile.ZipFile(OUT_APK, 'w', allowZip64=True) as zout:
            for name in names:
                if name in RUNTIME_ASSETS:
                    continue
                if name == 'classes.dex':
                    zout.writestr('classes.dex', dex_patched,
                                  compress_type=zipfile.ZIP_STORED)
                    print(f"  REPLACED classes.dex ({len(dex_patched)} bytes)")
                elif name == 'AndroidManifest.xml':
                    orig = zin.read(name)
                    patched = patch_manifest(orig)
                    zout.writestr(name, patched,
                                  compress_type=zipfile.ZIP_STORED)
                    print(f"  PATCHED  AndroidManifest.xml")
                elif name == 'resources.arsc':
                    zout.writestr(name, new_arsc,
                                  compress_type=zipfile.ZIP_STORED)
                    print(f"  REPLACED resources.arsc")
                elif name == 'assets/rootfs.tar.gz' and os.path.exists(NEW_ROOTFS):
                    with open(NEW_ROOTFS, 'rb') as f:
                        new_rootfs = f.read()
                    info = zin.getinfo(name)
                    info.compress_type = zipfile.ZIP_STORED
                    zout.writestr(info, new_rootfs)
                    print(f"  REPLACED assets/rootfs.tar.gz ({len(new_rootfs)} bytes)")
                elif name == 'lib/arm64-v8a/libXlorie.so' and os.path.exists(NEW_XLORIE_SO):
                    with open(NEW_XLORIE_SO, 'rb') as f:
                        new_so = f.read()
                    info = zin.getinfo(name)
                    zout.writestr(info, new_so)
                    print(f"  REPLACED lib/arm64-v8a/libXlorie.so ({len(new_so)} bytes)")
                else:
                    info = zin.getinfo(name)
                    data = zin.read(name)
                    zout.writestr(info, data)

            for asset_name, source_path in RUNTIME_ASSETS.items():
                with open(source_path, 'rb') as runtime_file:
                    runtime_data = runtime_file.read()
                zout.writestr(asset_name, runtime_data,
                              compress_type=zipfile.ZIP_DEFLATED)
                print(f"  ADDED    {asset_name} ({len(runtime_data)} bytes)")

            # Add virgl native libs (new entries, not in base APK)
            for virgl_lib in [
                "libexec_virgl_test_server.so",
                "libvirglrenderer.so",
                "libepoxy.so",
            ]:
                virgl_so = os.path.join(NATIVE_LIB_DIR, virgl_lib)
                if os.path.exists(virgl_so):
                    with open(virgl_so, 'rb') as f:
                        virgl_data = f.read()
                    zout.writestr(f"lib/arm64-v8a/{virgl_lib}", virgl_data,
                                  compress_type=zipfile.ZIP_STORED)
                    print(f"  ADDED    lib/arm64-v8a/{virgl_lib} ({len(virgl_data)} bytes)")

            # Add mipmap + drawable PNG files (new entries, not in base APK)
            if icon_pngs:
                for dname, _, _ in ICON_DENSITIES:
                    for folder in ('mipmap', 'drawable'):
                        path = f'res/{folder}-{dname}/ic_launcher.png'
                        zout.writestr(path, icon_pngs[dname],
                                      compress_type=zipfile.ZIP_STORED)
                print(f"  ADDED    {len(ICON_DENSITIES)*2} mipmap+drawable PNG entries")

    size_mb = os.path.getsize(OUT_APK) / 1024 / 1024
    print(f"\n[OK] Unsigned APK: {OUT_APK} ({size_mb:.1f} MB)")


if __name__ == '__main__':
    main()
