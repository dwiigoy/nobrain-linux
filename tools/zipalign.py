#!/usr/bin/env python3
"""
Minimal zipalign implementation.
Aligns all ZIP_STORED entries to 4-byte boundaries (required for APKs targeting API 30+).
Must be run BEFORE signing (apksigner will invalidate if run after v2 sign).
"""
import struct, sys, os, shutil

ALIGNMENT = 4

# ZIP local file header format
LFH_MAGIC  = b'PK\x03\x04'
LFH_SIZE   = 30  # fixed header bytes before filename/extra

# ZIP central directory entry
CDH_MAGIC  = b'PK\x01\x02'
CDH_SIZE   = 46

# ZIP end of central directory
EOCD_MAGIC = b'PK\x05\x06'

# ZIP64 end of central directory
EOCD64_MAGIC = b'PK\x06\x06'
EOCD64_LOC_MAGIC = b'PK\x06\x07'


def read_u16(data, off): return struct.unpack_from('<H', data, off)[0]
def read_u32(data, off): return struct.unpack_from('<I', data, off)[0]
def read_u64(data, off): return struct.unpack_from('<Q', data, off)[0]


def zipalign(input_path, output_path):
    with open(input_path, 'rb') as f:
        data = f.read()

    out   = bytearray()
    pos   = 0
    # map: old_offset → new_offset (for central directory fixup)
    offset_map = {}

    # --- Pass 1: rewrite local file entries ---
    while pos < len(data):
        magic = data[pos:pos+4]

        if magic == LFH_MAGIC:
            # Parse local file header
            gp_flag      = read_u16(data, pos + 6)
            compress     = read_u16(data, pos + 8)
            crc          = read_u32(data, pos + 14)
            comp_size    = read_u32(data, pos + 18)
            uncomp_size  = read_u32(data, pos + 22)
            fname_len    = read_u16(data, pos + 26)
            extra_len    = read_u16(data, pos + 28)
            fname        = data[pos + LFH_SIZE : pos + LFH_SIZE + fname_len]
            extra        = data[pos + LFH_SIZE + fname_len : pos + LFH_SIZE + fname_len + extra_len]

            # Handle zip64 extended info for comp_size
            if comp_size == 0xFFFFFFFF:
                # find zip64 extra field (id 0x0001)
                ep = 0
                while ep + 4 <= len(extra):
                    eid = struct.unpack_from('<H', extra, ep)[0]
                    esz = struct.unpack_from('<H', extra, ep+2)[0]
                    if eid == 0x0001:
                        comp_size   = read_u64(extra, ep + 4)
                        uncomp_size = read_u64(extra, ep + 12)
                        break
                    ep += 4 + esz

            old_data_offset = pos + LFH_SIZE + fname_len + extra_len
            new_header_start = len(out)

            if compress == 0:  # ZIP_STORED — needs alignment
                # Calculate how much padding extra needs
                tentative_data_offset = len(out) + LFH_SIZE + fname_len + len(extra)
                padding = (ALIGNMENT - (tentative_data_offset % ALIGNMENT)) % ALIGNMENT
                new_extra = extra + b'\x00' * padding
            else:
                new_extra = extra
                padding   = 0

            # Write updated local file header
            new_extra_len = len(new_extra)
            out += data[pos : pos + 28]                          # up to fname_len field
            out += struct.pack('<H', new_extra_len)              # updated extra_len
            out += fname
            out += new_extra
            # Copy compressed data
            out += data[old_data_offset : old_data_offset + comp_size]

            offset_map[pos] = new_header_start
            pos = old_data_offset + comp_size

        elif magic in (CDH_MAGIC, EOCD_MAGIC, EOCD64_MAGIC, EOCD64_LOC_MAGIC):
            break  # central directory starts here
        else:
            break

    central_dir_new_offset = len(out)

    # --- Pass 2: rewrite central directory with updated offsets ---
    while pos < len(data):
        magic = data[pos:pos+4]

        if magic == CDH_MAGIC:
            fname_len = read_u16(data, pos + 28)
            extra_len = read_u16(data, pos + 30)
            comm_len  = read_u16(data, pos + 32)
            old_lhdr_offset = read_u32(data, pos + 42)

            entry = bytearray(data[pos : pos + CDH_SIZE + fname_len + extra_len + comm_len])

            # Update local header offset
            if old_lhdr_offset == 0xFFFFFFFF:
                # zip64 — find in extra
                ep = CDH_SIZE + fname_len
                while ep + 4 <= CDH_SIZE + fname_len + extra_len:
                    eid = struct.unpack_from('<H', bytes(entry), ep)[0]
                    esz = struct.unpack_from('<H', bytes(entry), ep+2)[0]
                    if eid == 0x0001:
                        old64 = read_u64(bytes(entry), ep + 4)
                        new64 = offset_map.get(old64, old64)
                        struct.pack_into('<Q', entry, ep + 4, new64)
                        break
                    ep += 4 + esz
            else:
                new_offset = offset_map.get(old_lhdr_offset, old_lhdr_offset)
                struct.pack_into('<I', entry, 42, new_offset & 0xFFFFFFFF)

            out += bytes(entry)
            pos += CDH_SIZE + fname_len + extra_len + comm_len

        elif magic == EOCD64_MAGIC:
            rec_size = read_u64(data, pos + 4)
            entry = bytearray(data[pos : pos + 12 + rec_size])
            # Update central dir offset (at pos+48)
            struct.pack_into('<Q', entry, 48, central_dir_new_offset)
            out += bytes(entry)
            pos += 12 + rec_size

        elif magic == EOCD64_LOC_MAGIC:
            entry = bytearray(data[pos : pos + 20])
            # Update zip64 EOCD offset (at pos+8)
            struct.pack_into('<Q', entry, 8, central_dir_new_offset)
            out += bytes(entry)
            pos += 20

        elif magic == EOCD_MAGIC:
            entry = bytearray(data[pos : pos + 22])
            cd_offset = read_u32(data, pos + 16)
            if cd_offset != 0xFFFFFFFF:
                struct.pack_into('<I', entry, 16, central_dir_new_offset & 0xFFFFFFFF)
            out += bytes(entry)
            comment_len = read_u16(data, pos + 20)
            out += data[pos + 22 : pos + 22 + comment_len]
            break
        else:
            break

    with open(output_path, 'wb') as f:
        f.write(out)

    print(f"zipalign done: {output_path} ({len(out) // 1024 // 1024}MB)")


if __name__ == '__main__':
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    inp = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.join(project_root, 'android/app/build/outputs/apk/release/app-release-unsigned.apk')
    out = sys.argv[2] if len(sys.argv) > 2 else \
        os.path.join(project_root, 'nobrain-linux-aligned.apk')
    zipalign(inp, out)
