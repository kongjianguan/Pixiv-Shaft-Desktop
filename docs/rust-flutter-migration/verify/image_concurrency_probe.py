import socket
import ssl
import threading
import time

TARGET_IP = "210.140.139.134"
PORT = 443
HOST = "i.pximg.net"
PATH = "/robots.txt"
CONCURRENCY = 6

results = []
lock = threading.Lock()


def build_ctx():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def worker(idx):
    started = time.time()
    try:
        raw = socket.create_connection((TARGET_IP, PORT), timeout=20)
    except OSError as exc:
        with lock:
            results.append((idx, "TCP 失败", type(exc).__name__, 0))
        return
    try:
        sock = build_ctx().wrap_socket(raw, server_hostname=None)
    except Exception as exc:
        with lock:
            results.append((idx, "握手失败", type(exc).__name__, 0))
        raw.close()
        return
    request = (
        f"GET {PATH} HTTP/1.1\r\n"
        f"Host: {HOST}\r\n"
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
        if len(data) > 200000:
            break
    sock.close()
    status = data.split(b"\r\n\r\n", 1)[0].split(b"\r\n")[0].decode("utf-8", "replace")
    with lock:
        results.append((idx, status, f"{len(data)} 字节", int((time.time() - started) * 1000)))


def main():
    print(f"并发 {CONCURRENCY} 个相同请求 -> {TARGET_IP}{PATH}")
    threads = [threading.Thread(target=worker, args=(i,)) for i in range(CONCURRENCY)]
    begin = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = int((time.time() - begin) * 1000)
    for idx, status, size, ms in sorted(results):
        print(f"  #{idx}: {status}  {size}  {ms} ms")
    print(f"总耗时 {wall} ms —— 若为串行会被拉长到单请求耗时 x {CONCURRENCY}")


if __name__ == "__main__":
    main()
