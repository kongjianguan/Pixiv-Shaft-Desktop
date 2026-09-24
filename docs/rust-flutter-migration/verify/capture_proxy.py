import socket
import sys
import threading


def pipe(src, dst, sink=None):
    try:
        while True:
            part = src.recv(16384)
            if not part:
                break
            if sink is not None:
                sink.write(part)
                sink.flush()
            dst.sendall(part)
    except OSError:
        pass
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def capture(listen_port: int, target_port: int, outfile: str):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(1)
    print(f"捕获代理已启动 127.0.0.1:{listen_port} -> 127.0.0.1:{target_port}", flush=True)
    conn, _addr = server.accept()
    upstream = socket.create_connection(("127.0.0.1", target_port), timeout=15)
    with open(outfile, "wb") as sink:
        c2s = threading.Thread(target=pipe, args=(conn, upstream, sink))
        s2c = threading.Thread(target=pipe, args=(upstream, conn, None))
        c2s.start()
        s2c.start()
        c2s.join(timeout=15)
        s2c.join(timeout=15)
    try:
        conn.close()
    except OSError:
        pass
    try:
        upstream.close()
    except OSError:
        pass
    server.close()
    print("捕获结束", flush=True)


if __name__ == "__main__":
    capture(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3])
