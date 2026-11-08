"""Loopback-only RTMP receiver for synthetic Android integration tests. No credentials, no external publish.

Run with adb reverse tcp:19350 tcp:19350 on the task emulator, then instrumentation
with -e broadcast-rtmp true. Writes a small FLV plus JSON counters under docs/previews.
This deliberately minimal receiver is a test fixture, not a production streaming server.
"""
import argparse
import json
import os
import socket
import struct
import time
from pathlib import Path


def exact(conn, count):
    result = bytearray()
    while len(result) < count:
        data = conn.recv(count - len(result))
        if not data:
            raise EOFError()
        result.extend(data)
    return bytes(result)


def amf(value):
    if value is None:
        return b"\x05"
    if isinstance(value, str):
        text = value.encode(); return b"\x02" + struct.pack(">H", len(text)) + text
    if isinstance(value, (int, float)):
        return b"\x00" + struct.pack(">d", value)
    if isinstance(value, dict):
        return b"\x03" + b"".join(struct.pack(">H", len(k.encode())) + k.encode() + amf(v) for k, v in value.items()) + b"\x00\x00\x09"
    raise ValueError("unsupported fixture AMF")


def command_header(data):
    if data[:1] != b"\x02":
        return "", 0
    length = int.from_bytes(data[1:3], "big")
    name = data[3:3 + length].decode(errors="replace")
    offset = 3 + length
    transaction = struct.unpack(">d", data[offset + 1:offset + 9])[0] if data[offset:offset + 1] == b"\x00" else 0
    return name, transaction


class Receiver:
    def __init__(self, conn, output):
        self.conn, self.output = conn, output
        self.in_chunk = 128
        self.out_chunk = 128
        self.channels = {}
        self.counts = {"video": 0, "audio": 0, "video_config": 0, "audio_config": 0, "published": False}

    def send(self, kind, payload, stream=0):
        header = b"\x03\x00\x00\x00" + len(payload).to_bytes(3, "big") + bytes([kind]) + stream.to_bytes(4, "little")
        body = b"\xc3".join(payload[n:n + self.out_chunk] for n in range(0, len(payload), self.out_chunk))
        self.conn.sendall(header + body)

    def reply(self, *values, stream=0):
        self.send(20, b"".join(amf(value) for value in values), stream)

    def message(self):
        while True:
            basic = exact(self.conn, 1)[0]
            fmt, channel = basic >> 6, basic & 63
            if channel == 0:
                channel = 64 + exact(self.conn, 1)[0]
            elif channel == 1:
                extension = exact(self.conn, 2); channel = 64 + extension[0] + 256 * extension[1]
            current = self.channels.setdefault(channel, {"timestamp": 0, "length": 0, "type": 0, "stream": 0, "data": bytearray(), "extended": False})
            if fmt == 0:
                header = exact(self.conn, 11)
                stamp = int.from_bytes(header[:3], "big")
                current.update(timestamp=stamp, length=int.from_bytes(header[3:6], "big"), type=header[6], stream=int.from_bytes(header[7:], "little"), data=bytearray(), extended=stamp == 0xFFFFFF, delta=0)
            elif fmt in (1, 2):
                header = exact(self.conn, 7 if fmt == 1 else 3)
                delta = int.from_bytes(header[:3], "big")
                current.update(timestamp=current["timestamp"] + delta, delta=delta, data=bytearray(), extended=delta == 0xFFFFFF)
                if fmt == 1:
                    current.update(length=int.from_bytes(header[3:6], "big"), type=header[6])
            elif len(current["data"]) == current["length"]:
                current["data"] = bytearray(); current["timestamp"] += current.get("delta", 0)
            if current["extended"]:
                stamp = int.from_bytes(exact(self.conn, 4), "big")
                if fmt == 0:
                    current["timestamp"] = stamp
            if current["length"] > 8 * 1024 * 1024:
                raise ValueError("fixture message limit")
            remaining = current["length"] - len(current["data"])
            current["data"].extend(exact(self.conn, min(remaining, self.in_chunk)))
            if len(current["data"]) == current["length"]:
                return current["type"], bytes(current["data"]), current["timestamp"]

    def tag(self, kind, data, stamp):
        self.output.write(bytes([kind]) + len(data).to_bytes(3, "big") + (stamp & 0xFFFFFF).to_bytes(3, "big") + bytes([(stamp >> 24) & 255]) + b"\0\0\0" + data + (11 + len(data)).to_bytes(4, "big"))

    def run(self):
        hello = exact(self.conn, 1537)
        if hello[0] != 3:
            raise ValueError("RTMP version")
        first = struct.pack(">II", int(time.time()) & 0xFFFFFFFF, 0) + os.urandom(1528)
        self.conn.sendall(b"\x03" + first + hello[1:])
        exact(self.conn, 1536)
        self.output.write(b"FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00")
        try:
            while True:
                kind, payload, stamp = self.message()
                if kind == 1:
                    self.in_chunk = int.from_bytes(payload[:4], "big") & 0x7FFFFFFF
                    if not 1 <= self.in_chunk <= 8 * 1024 * 1024:
                        raise ValueError("chunk limit")
                elif kind == 20:
                    name, tx = command_header(payload)
                    if name == "connect":
                        self.send(5, (5_000_000).to_bytes(4, "big"))
                        self.send(6, (5_000_000).to_bytes(4, "big") + b"\x02")
                        self.reply("_result", tx, {"fmsVer": "FMS/3,5,7,7009", "capabilities": 31}, {"level": "status", "code": "NetConnection.Connect.Success", "description": "Fixture connected", "objectEncoding": 0})
                    elif name == "createStream":
                        self.reply("_result", tx, None, 1)
                    elif name == "publish":
                        self.counts["published"] = True
                        self.send(4, b"\x00\x00\x00\x00\x00\x01")
                        self.reply("onStatus", 0, None, {"level": "status", "code": "NetStream.Publish.Start", "description": "Fixture publishing"}, stream=1)
                    elif name in ("releaseStream", "FCPublish"):
                        self.reply("_result", tx, None, None)
                    elif name in ("deleteStream", "closeStream"):
                        break
                elif kind in (8, 9):
                    self.tag(kind, payload, stamp)
                    key = "video" if kind == 9 else "audio"
                    self.counts[key + "_config" if len(payload) > 1 and payload[1] == 0 else key] += 1
                elif kind == 18:
                    self.tag(kind, payload, stamp)
        except EOFError:
            pass
        return self.counts


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--port", type=int, default=19350); parser.add_argument("--output", default="docs/previews/broadcast-loopback.flv")
    args = parser.parse_args()
    path = Path(args.output); path.parent.mkdir(parents=True, exist_ok=True)
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); server.bind(("127.0.0.1", args.port)); server.listen(1); server.settimeout(90)
        print("Loopback RTMP fixture listening", flush=True)
        conn, _ = server.accept()
        with conn, path.open("wb") as output:
            conn.settimeout(30)
            result = Receiver(conn, output).run()
    path.with_suffix(".json").write_text(json.dumps(result), encoding="utf-8")
    print(json.dumps(result), flush=True)
    if not all(result[key] > 0 for key in ("video", "audio", "video_config", "audio_config")):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
