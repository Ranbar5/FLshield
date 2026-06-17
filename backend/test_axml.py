import struct
import zipfile
import re
import os

def extract_package_name_from_axml(axml_data: bytes) -> str:
    magic, size = struct.unpack("<II", axml_data[0:8])
    if magic != 0x00080003:
        raise ValueError("Not a valid AXML file")
        
    offset = 8
    while offset < len(axml_data):
        chunk_type, chunk_size = struct.unpack("<II", axml_data[offset:offset+8])
        if chunk_type == 0x001C0001:  # String pool
            string_count, style_count, flags, string_offset, style_offset = struct.unpack(
                "<IIIII", axml_data[offset+8:offset+28]
            )
            is_utf8 = bool(flags & 256)
            
            offsets = []
            for i in range(string_count):
                off = struct.unpack("<I", axml_data[offset+28+i*4 : offset+32+i*4])[0]
                offsets.append(off)
                
            string_data_start = offset + string_offset
            strings = []
            for off in offsets:
                str_offset = string_data_start + off
                if is_utf8:
                    val1 = axml_data[str_offset]
                    str_offset += 1
                    if val1 & 0x80:
                        val1 = ((val1 & 0x7F) << 8) | axml_data[str_offset]
                        str_offset += 1
                    val2 = axml_data[str_offset]
                    str_offset += 1
                    if val2 & 0x80:
                        val2 = ((val2 & 0x7F) << 8) | axml_data[str_offset]
                        str_offset += 1
                    s = axml_data[str_offset : str_offset + val2].decode("utf-8", errors="replace")
                else:
                    val = struct.unpack("<H", axml_data[str_offset:str_offset+2])[0]
                    str_offset += 2
                    if val & 0x8000:
                        val = ((val & 0x7FFF) << 16) | struct.unpack("<H", axml_data[str_offset:str_offset+2])[0]
                        str_offset += 2
                    s = axml_data[str_offset : str_offset + val*2].decode("utf-16le", errors="replace")
                strings.append(s)
            
            pkg_pattern = re.compile(r'^[a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_.]+$')
            candidate_pkgs = [s for s in strings if pkg_pattern.match(s)]
            
            for candidate in candidate_pkgs:
                if any(x in candidate.lower() for x in ["permission", "action", "category", "intent", "scheme", "android:", "manifest"]):
                    continue
                return candidate
            if candidate_pkgs:
                return candidate_pkgs[0]
            break
        offset += chunk_size
    return ""

def get_apk_package_name(apk_path: str) -> str:
    try:
        with zipfile.ZipFile(apk_path) as z:
            manifest_data = z.read("AndroidManifest.xml")
            pkg = extract_package_name_from_axml(manifest_data)
            if pkg:
                return pkg
    except Exception as e:
        print(f"Error parsing AXML for {apk_path}: {e}")
    return ""

apks_dir = r"public/apks"
for f in os.listdir(apks_dir):
    if f.endswith(".apk"):
        path = os.path.join(apks_dir, f)
        pkg = get_apk_package_name(path)
        print(f"{f} -> {pkg}")
