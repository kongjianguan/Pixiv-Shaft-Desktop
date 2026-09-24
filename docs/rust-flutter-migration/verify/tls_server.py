import socket
import ssl
import sys


def serve(port: int):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain("tls_cert.pem", "tls_key.pem")
    try:
        ctx.set_alpn_protocols(["http/1.1"])
    except Exception:
        pass
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(5)
    print(f"本地 TLS 服务已启动 127.0.0.1:{port}", flush=True)
    while True:
        conn, _addr = server.accept()
        try:
            tls = ctx.wrap_socket(conn, server_side=True)
            data = tls.recv(4096)
            body = b"OK"
            resp = (
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: text/plain\r\n"
                b"Content-Length: " + str(len(body)).encode() + b"\r\n"
                b"Connection: close\r\n\r\n" + body
            )
            tls.sendall(resp)
            tls.close()
            print("服务端：完成一次握手并回复 200", flush=True)
        except Exception as exc:
            print(f"服务端握手失败: {type(exc).__name__}: {exc}", flush=True)
            try:
                conn.close()
            except Exception:
                pass


if __name__ == "__main__":
    serve(int(sys.argv[1]))
