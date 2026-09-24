import socket
import ssl
import sys


def run(dst_ip: str, dst_port: int, expected_host: str, send_sni: bool):
    print(f"--- Python ssl: {dst_ip}:{dst_port} server_hostname={expected_host if send_sni else None}")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        raw = socket.create_connection((dst_ip, dst_port), timeout=15)
    except OSError as exc:
        print(f"  TCP 连接失败: {exc}")
        return
    try:
        sock = ctx.wrap_socket(raw, server_hostname=expected_host if send_sni else None)
    except Exception as exc:
        print(f"  握手失败: {type(exc).__name__}: {exc}")
        raw.close()
        return
    print("  握手成功")
    print(f"  协商协议: {sock.version()}")

    request = (
        f"GET /img-original/img/2024/01/01/00/00/00/12345_p0.jpg HTTP/1.1\r\n"
        f"Host: i.pximg.net\r\n"
        f"Referer: https://app-api.pixiv.net/\r\n"
        f"User-Agent: PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)\r\n"
        f"Accept: image/*\r\n"
        f"Connection: close\r\n\r\n"
    )
    try:
        sock.sendall(request.encode())
        chunks = b""
        while True:
            part = sock.recv(65536)
            if not part:
                break
            chunks += part
            if len(chunks) > 60000:
                break
    except Exception as exc:
        print(f"  请求失败: {type(exc).__name__}: {exc}")
        sock.close()
        return
    sock.close()
    head = chunks.split(b"\r\n\r\n", 1)[0].decode("utf-8", "replace")
    print(f"  响应状态行: {head.splitlines()[0] if head else '<空>'}")
    for line in head.splitlines():
        low = line.lower()
        if low.startswith("content-type") or low.startswith("content-length") or low.startswith("server"):
            print(f"  响应头: {line}")


if __name__ == "__main__":
    dst_ip = sys.argv[1]
    dst_port = int(sys.argv[2])
    expected_host = sys.argv[3]
    send_sni = sys.argv[4] == "true"
    run(dst_ip, dst_port, expected_host, send_sni)
