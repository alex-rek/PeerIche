"""Loopback-only HTTP CONNECT/SOCKS5 fixture for the task Android emulator.

Only public HTTPS test hosts are allowed. Credentials are synthetic; logs never contain headers.
"""
import argparse
import base64
import json
import select
import socket
import socketserver
import threading
from pathlib import Path

ALLOWED = {"example.com", "tube.articfox.cc", "0ch.tv", "media.0ch.tv"}
USER, PASSWORD = b"peeriche-test", b"fixture-password"
stats = {}
lock = threading.Lock()
output = None


def record(name, field):
    with lock:
        values = stats.setdefault(name, {})
        values[field] = values.get(field, 0) + 1
        output.write_text(json.dumps(stats, indent=2), encoding="utf-8")


def read_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError()
        data += chunk
    return data


def tunnel(client, upstream):
    sockets = [client, upstream]
    while True:
        ready, _, _ = select.select(sockets, [], [], 30)
        if not ready:
            return
        for source in ready:
            data = source.recv(65536)
            if not data:
                return
            (upstream if source is client else client).sendall(data)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        client = self.request
        client.settimeout(20)
        name, authenticated, kind = self.server.fixture
        try:
            if kind == "http":
                header = b""
                while not header.endswith(b"\r\n\r\n"):
                    header += read_exact(client, 1)
                    if len(header) > 16384:
                        return
                lines = header.decode("iso-8859-1").split("\r\n")
                method, destination, _ = lines[0].split(" ")
                fields = {line.split(":", 1)[0].lower(): line.split(":", 1)[1].strip() for line in lines[1:] if ":" in line}
                expected = "Basic " + base64.b64encode(USER + b":" + PASSWORD).decode()
                if authenticated and fields.get("proxy-authorization") != expected:
                    record(name, "auth_rejected")
                    client.sendall(b'HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="fixture"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                    return
                if method != "CONNECT":
                    return
                host, port = destination.rsplit(":", 1)
                port = int(port)
            else:
                version, count = read_exact(client, 2)
                methods = read_exact(client, count)
                method = 2 if authenticated else 0
                if version != 5 or method not in methods:
                    client.sendall(bytes([5, 255]))
                    return
                client.sendall(bytes([5, method]))
                if authenticated:
                    version, length = read_exact(client, 2)
                    user = read_exact(client, length)
                    password = read_exact(client, read_exact(client, 1)[0])
                    valid = version == 1 and user == USER and password == PASSWORD
                    client.sendall(bytes([1, 0 if valid else 1]))
                    if not valid:
                        record(name, "auth_rejected")
                        return
                version, command, _, address_type = read_exact(client, 4)
                if version != 5 or command != 1 or address_type != 3:
                    return
                host = read_exact(client, read_exact(client, 1)[0]).decode("ascii")
                port = int.from_bytes(read_exact(client, 2), "big")
            if host not in ALLOWED or port != 443:
                record(name, "target_rejected")
                return
            with socket.create_connection((host, port), timeout=15) as upstream:
                record(name, "tunnels")
                if kind == "http":
                    client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                else:
                    client.sendall(bytes([5, 0, 0, 1, 127, 0, 0, 1, 0, 0]))
                tunnel(client, upstream)
        except (OSError, EOFError, ValueError):
            record(name, "closed")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--stats", required=True)
    args = parser.parse_args()
    output = Path(args.stats)
    output.write_text("{}", encoding="utf-8")
    for name, port, auth, kind in [("http", 18180, False, "http"), ("socks", 18181, False, "socks"),
                                   ("http_auth", 18182, True, "http"), ("socks_auth", 18183, True, "socks")]:
        server = Server(("127.0.0.1", port), Handler)
        server.fixture = name, auth, kind
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Ready: loopback HTTP/SOCKS5 fixtures on 18180..18183", flush=True)
    threading.Event().wait()
