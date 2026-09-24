import struct
import sys


def parse_client_hello(data: bytes):
    if len(data) < 5:
        return None
    if data[0] != 0x16:
        return None
    body = data[5:]
    if len(body) < 4:
        return None
    hs_type = body[0]
    hs_len = int.from_bytes(body[1:4], "big")
    hs = body[4 : 4 + hs_len]
    if hs_type != 0x01:
        return None
    offset = 2 + 32  # version + random
    sid_len = hs[offset]
    offset += 1 + sid_len
    cipher_len = int.from_bytes(hs[offset : offset + 2], "big")
    offset += 2 + cipher_len
    comp_len = hs[offset]
    offset += 1 + comp_len
    if offset + 2 > len(hs):
        return None
    ext_total = int.from_bytes(hs[offset : offset + 2], "big")
    offset += 2
    end = offset + ext_total
    extensions = []
    while offset + 4 <= end:
        etype = int.from_bytes(hs[offset : offset + 2], "big")
        elen = int.from_bytes(hs[offset + 2 : offset + 4], "big")
        ebody = hs[offset + 4 : offset + 4 + elen]
        extensions.append((etype, elen, ebody))
        offset += 4 + elen
    return {"legacy_version": hs[0:2].hex(), "extensions": extensions}


SNI_TYPE = 0
ALPN_TYPE = 16


def describe(path):
    with open(path, "rb") as handle:
        data = handle.read()
    parsed = parse_client_hello(data)
    print(f"文件: {path}  字节数: {len(data)}")
    if parsed is None:
        print("  无法解析为 TLS ClientHello")
        return
    print(f"  legacy_version: {parsed['legacy_version']}")
    types = [e[0] for e in parsed["extensions"]]
    print(f"  扩展数量: {len(types)}")
    print(f"  扩展类型列表: {types}")
    sni_hits = [e for e in parsed["extensions"] if e[0] == SNI_TYPE]
    if not sni_hits:
        print("  SNI(server_name, type=0): 未出现 —— 未发送 SNI")
    else:
        for _t, _l, ebody in sni_hits:
            list_len = int.from_bytes(ebody[0:2], "big")
            pos = 2
            names = []
            while pos < 2 + list_len:
                ntype = ebody[pos]
                nlen = int.from_bytes(ebody[pos + 1 : pos + 3], "big")
                names.append((ntype, ebody[pos + 3 : pos + 3 + nlen].decode("utf-8", "replace")))
                pos += 3 + nlen
            print(f"  SNI(server_name, type=0): 出现 {len(sni_hits)} 次 -> {names}")
    for _t, _l, ebody in parsed["extensions"]:
        if _t == ALPN_TYPE:
            alpn_len = int.from_bytes(ebody[0:2], "big")
            pos = 2
            protos = []
            while pos < 2 + alpn_len:
                plen = ebody[pos]
                protos.append(ebody[pos + 1 : pos + 1 + plen].decode("utf-8", "replace"))
                pos += 1 + plen
            print(f"  ALPN(type=16): {protos}")
    print()


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        describe(arg)
