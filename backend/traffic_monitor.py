"""
Traffic Monitor —丈量 WebSocket + HTTP bytes (solo para tests)
"""

import time
import json
import logging
from collections import defaultdict
from dataclasses import dataclass, field

logger = logging.getLogger("flshield.traffic")

@dataclass
class TrafficStats:
    device_id: str
    ws_messages_sent: int = 0
    ws_bytes_sent: int = 0
    ws_messages_recv: int = 0
    ws_bytes_recv: int = 0
    http_requests: int = 0
    http_bytes_sent: int = 0
    first_seen: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)

_traffic: dict[str, TrafficStats] = defaultdict(lambda: None)

def get_stats(device_id: str) -> TrafficStats:
    if _traffic.get(device_id) is None:
        _traffic[device_id] = TrafficStats(device_id=device_id)
    return _traffic[device_id]

def track_ws_recv(device_id: str, num_bytes: int):
    s = get_stats(device_id)
    s.ws_messages_recv += 1
    s.ws_bytes_recv += num_bytes
    s.last_activity = time.time()
    logger.debug(f"[{device_id}] WS recv {num_bytes}B ({s.ws_messages_recv} msgs)")

def track_ws_sent(device_id: str, num_bytes: int):
    s = get_stats(device_id)
    s.ws_messages_sent += 1
    s.ws_bytes_sent += num_bytes
    s.last_activity = time.time()
    logger.debug(f"[{device_id}] WS sent {num_bytes}B ({s.ws_messages_sent} msgs)")

def track_http(device_id: str, bytes_sent: int):
    s = get_stats(device_id)
    s.http_requests += 1
    s.http_bytes_sent += bytes_sent
    s.last_activity = time.time()
    logger.debug(f"[{device_id}] HTTP {bytes_sent}B ({s.http_requests} reqs)")

def get_report(device_id: str) -> dict:
    s = _traffic.get(device_id)
    if s is None:
        return {"device_id": device_id, "error": "no data"}
    total = s.ws_bytes_sent + s.ws_bytes_recv + s.http_bytes_sent
    return {
        "device_id": device_id,
        "ws_sent_kb": round(s.ws_bytes_sent / 1024, 1),
        "ws_recv_kb": round(s.ws_bytes_recv / 1024, 1),
        "http_kb": round(s.http_bytes_sent / 1024, 1),
        "total_kb": round(total / 1024, 1),
        "ws_messages_sent": s.ws_messages_sent,
        "ws_messages_recv": s.ws_messages_recv,
        "http_requests": s.http_requests,
        "uptime_s": round(s.last_activity - s.first_seen, 0),
        "last_activity": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(s.last_activity)),
    }

def get_all_reports() -> dict:
    return {did: get_report(did) for did in _traffic}

def reset(device_id: str = None):
    if device_id:
        _traffic.pop(device_id, None)
    else:
        _traffic.clear()