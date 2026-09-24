import socket
import ssl
import sys

DEFAULT_PATHS = [
    "/img-master/img/2024/01/01/00/00/00/12345_p0_master1200.jpg",
    "/c/240x240/img-master/img/2024/01/01/00/00/00/12345_p0_square1200.jpg",
    "/common/images/no_profile.png",
    "/common/images/no_profile_s.png",
    "/www/images/limit.png",
    "/favicon.ico",
]


def probe(ip, port, host, path):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        raw = socket.create_connection((ip, port), timeout=12)
    except OSError as exc:
        return f"TCP 失败: {type(exc).__name__}"
    try:
        sock = ctx.wrap_socket(raw, server_hostname=None)
    except Exception as exc:
        return f"握手失败: {type(exc).__name__}: {exc}"
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Referer: https://app-api.pixiv.net/\r\n"
        f"User-Agent: PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)\r\n"
        f"Accept: image/*\r\n"
        f"Connection: close\r\n\r\n"
    )
    sock.sendall(request.encode())
    data = b""
    while True:
        try:
            part = sock.recv(65536)
        except Exception:
            break
        if not part:
            break
        data += part
        if len(data) > 300000:
            break
    sock.close()
    head = data.split(b"\r\n\r\n", 1)[0]
    lines = head.split(b"\r\n")
    status = lines[0].decode("utf-8", "replace") if lines else ""
    ctype = ""
    for line in lines:
        if line.lower().startswith(b"content-type"):
            ctype = line.decode("utf-8", "replace")
    return f"{status} | {ctype} | {len(data)} 字节"


if __name__ == "__main__":
    ip = sys.argv[1]
    host = sys.argv[2]
    paths = sys.argv[3:] or DEFAULT_PATHS
    for path in paths:
        print(f"{path}  ->  {probe(ip, 443, host, path)}")
