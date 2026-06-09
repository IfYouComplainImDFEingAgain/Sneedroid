#!/usr/bin/env python3
"""
whisper_test.py — connect to SneedChat, authenticate, and DO NOT join a room.

Purpose: test whether whisper (DM) frames are delivered on a bare authenticated
connection, i.e. whether whispers are connection-level rather than room-scoped. If
they are, a background service could keep DMs flowing without drinking from a busy
room's message firehose.

It connects with your session cookies, never sends `/join`, keeps the socket alive
with WebSocket PING control frames (there is no valid `/ping` chat command), and prints
every inbound frame — loudly flagging any `whisper`. Have someone whisper you (or use
--send-whisper) and watch.

The WS endpoint is behind the Tartarus (ttrs) proof-of-work gate. ttrs_clearance lives
only ~5 minutes AND is bound to the connection's TLS fingerprint, so this script solves
the PoW and upgrades the WebSocket over the *same* TLS socket (SHA-256, like the app's
single OkHttp stack). A clearance minted elsewhere — browser, curl, requests — is rejected.

--- Getting cookies -------------------------------------------------------------
The WS endpoint sits behind the KiwiFlare PoW gate, so you need real cookies:
  xf_user, xf_session  (XenForo login)  +  sssg_clearance / ttrs_clearance (PoW)

Two ways to supply them:
  1. Netscape cookies.txt (browser "Get cookies.txt" extension export, or a curl/yt-dlp
     jar) via --cookies-file. HttpOnly lines (#HttpOnly_…) and domain filtering are handled.
  2. Raw header string (DevTools → Network → the `chat.ws` request → Request Headers →
     copy the `Cookie:` value) via --cookie / SNEED_COOKIE / a ./cookies.txt of just that.

--- Usage ----------------------------------------------------------------------
  pip install websocket-client
  python3 whisper_test.py --cookies-file cookies.txt              # listen only, no join
  python3 whisper_test.py --cookies-file cookies.txt --join 15    # control: DO join room 15
  python3 whisper_test.py --cookie "xf_user=...; xf_session=..."  # raw header instead
  SNEED_COOKIE="xf_user=...; ..." python3 whisper_test.py
  python3 whisper_test.py --cookies-file cookies.txt --send-whisper 1234:"hello"
"""

import argparse
import hashlib
import http.client
import json
import os
import re
import socket
import ssl
import sys
import threading
import time
import urllib.parse
from urllib.parse import urlparse

try:
    from websocket import create_connection
    from websocket import WebSocketConnectionClosedException
except ImportError:
    sys.exit("Missing dependency. Run:  pip install websocket-client")

DEFAULT_URL = "wss://kiwifarms.st:9443/chat.ws"
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
PING_INTERVAL = 20  # seconds; matches the app's heartbeat


def ts() -> str:
    return time.strftime("%H:%M:%S")


def header_to_dict(header: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in header.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def parse_netscape(path: str, host: str | None) -> dict[str, str]:
    """Parse a Netscape/Mozilla cookies.txt into a {name: value} dict.

    Handles the `#HttpOnly_` line prefix (which the stdlib MozillaCookieJar drops as a
    comment, losing HttpOnly cookies like xf_session) and filters to cookies that apply
    to `host`. Falls back to including everything if the host filter matches nothing.
    """
    def collect(filter_host: str | None) -> dict[str, str]:
        jar: dict[str, str] = {}
        with open(path) as f:
            for raw in f:
                line = raw.rstrip("\n")
                if line.startswith("#HttpOnly_"):
                    line = line[len("#HttpOnly_"):]
                elif not line.strip() or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 7:
                    parts = line.split()
                if len(parts) < 7:
                    continue
                domain, _flag, _path, _secure, _expiry, name = parts[:6]
                value = "\t".join(parts[6:]) if "\t" in line else parts[6]
                dom = domain.lstrip(".")
                if filter_host and not (filter_host == dom or filter_host.endswith("." + dom)):
                    continue
                jar[name] = value
        return jar

    cookies = collect(host)
    if not cookies and host:
        print(f"[{ts()}] note: no cookies matched host {host}; including all entries from {path}.")
        cookies = collect(None)
    if not cookies:
        sys.exit(f"No cookies parsed from {path} (is it Netscape format?).")
    return cookies


def load_cookies(arg_cookie: str | None, cookies_file: str | None, host: str | None) -> dict[str, str]:
    if arg_cookie:
        return header_to_dict(arg_cookie)
    if cookies_file:
        return parse_netscape(cookies_file, host)
    env = os.environ.get("SNEED_COOKIE")
    if env:
        return header_to_dict(env)
    if os.path.exists("cookies.txt"):
        with open("cookies.txt") as f:
            return header_to_dict(f.read())
    sys.exit(
        "No cookies. Provide --cookie, --cookies-file, SNEED_COOKIE env, or a cookies.txt file.\n"
        "See the header of this script for how to grab them from your browser."
    )


def solve_pow(salt: str, difficulty: int) -> int:
    """Find a nonce whose SHA-256(salt+nonce) has `difficulty` leading zero bits (MSB-first).

    Matches the app's KiwiFlare/Tartarus solver. difficulty 16 → ~65k tries, sub-second.
    """
    full = difficulty // 8
    rem = difficulty % 8
    mask = (0xFF << (8 - rem)) & 0xFF if rem else 0
    nonce = 0
    while True:
        nonce += 1
        h = hashlib.sha256(f"{salt}{nonce}".encode()).digest()
        if h[:full] == b"\x00" * full and (rem == 0 or (h[full] & mask) == 0):
            return nonce


def header_from(cookies: dict[str, str]) -> str:
    return "; ".join(f"{n}={v}" for n, v in cookies.items())


def _set_cookie_token(resp: http.client.HTTPResponse) -> str | None:
    for sc in resp.headers.get_all("Set-Cookie") or []:
        m = re.search(r"ttrs_clearance=([^;]+)", sc)
        if m:
            return m.group(1)
    return None


def gate_connect(url: str, cookies: dict[str, str], user_agent: str, do_pow: bool):
    """Open a TLS socket, solve the Tartarus PoW on it, and upgrade the SAME socket to a WS.

    The clearance the gate issues is bound to the connection's TLS fingerprint, so it is only
    honoured by the exact client that solved it. Minting with one library and upgrading with
    another (e.g. requests → websocket-client) is rejected. Doing both over one socket — the
    way the app's single OkHttp stack does — is what makes it work.
    """
    parsed = urlparse(url)
    host = parsed.hostname or ""
    port = parsed.port or 443
    resource = parsed.path or "/chat.ws"

    # Identity cookies only; the clearance is minted fresh below (a stale/foreign one is useless).
    login = {n: v for n, v in cookies.items() if not n.endswith("_clearance")}

    sock = ssl.create_default_context().wrap_socket(
        socket.create_connection((host, port), timeout=20), server_hostname=host)

    if do_pow:
        conn = http.client.HTTPConnection(host, port, timeout=20)
        conn.sock = sock
        base_hdr = {"User-Agent": user_agent, "Connection": "keep-alive"}

        conn.request("GET", resource, headers={**base_hdr, "Cookie": header_from(login)})
        r = conn.getresponse()
        html = r.read().decode("utf-8", "replace")
        stage = _set_cookie_token(r)
        salt_m = re.search(r'data-ttrs-challenge="([^"]+)"', html)
        if not salt_m:
            if "data-sssg-challenge" in html:
                sys.exit("Gate is using the SSSG variant, not TTRS — this script only solves TTRS.")
            if r.status in (101, 200, 400, 426):
                print(f"[{ts()}] no challenge (HTTP {r.status}) — already cleared on this socket.")
            else:
                sys.exit(f"Unexpected gate response (HTTP {r.status}): {html[:200]}")
        else:
            salt = salt_m.group(1)
            diff_m = re.search(r'data-ttrs-difficulty="([^"]+)"', html)
            difficulty = int(diff_m.group(1)) if diff_m else 16
            print(f"[{ts()}] TTRS challenge: difficulty={difficulty} salt={salt[:16]}… — solving…")
            t0 = time.time()
            nonce = solve_pow(salt, difficulty)
            print(f"[{ts()}] solved: nonce={nonce} in {time.time() - t0:.2f}s — submitting…")

            body = urllib.parse.urlencode({"salt": salt, "nonce": str(nonce)})
            post_cookie = header_from({**login, "ttrs_clearance": stage} if stage else login)
            conn.request("POST", "/.ttrs/challenge", body=body, headers={
                **base_hdr, "Content-Type": "application/x-www-form-urlencoded", "Cookie": post_cookie})
            r2 = conn.getresponse()
            payload = r2.read().decode("utf-8", "replace")
            try:
                ok = bool(json.loads(payload).get("success"))
            except ValueError:
                ok = False
            if not ok:
                sys.exit(f"ttrs rejected solution (HTTP {r2.status}): {payload[:200]}")
            fresh = _set_cookie_token(r2)
            if not fresh:
                sys.exit("ttrs accepted but no ttrs_clearance cookie was set.")
            cookies["ttrs_clearance"] = fresh
            print(f"[{ts()}] fresh ttrs_clearance minted (valid ~5 min).")

    headers = [f"User-Agent: {user_agent}", "Origin: https://kiwifarms.st",
               f"Cookie: {header_from(cookies)}"]
    # Reuse the same TLS socket so the gate sees the connection it just cleared.
    return create_connection(url, socket=sock, header=headers)


def main() -> None:
    p = argparse.ArgumentParser(description="SneedChat whisper-without-join test")
    p.add_argument("--cookie", help="Cookie header value (name=val; name2=val2; ...)")
    p.add_argument("--cookies-file", metavar="PATH",
                   help="Netscape-format cookies.txt (e.g. a browser export / curl jar)")
    p.add_argument("--url", default=DEFAULT_URL, help=f"WS URL (default {DEFAULT_URL})")
    p.add_argument("--join", type=int, metavar="ROOM_ID",
                   help="CONTROL: actually join this room (to compare against not joining)")
    p.add_argument("--send-whisper", metavar="ID:TEXT",
                   help="After connect, send a whisper '/w <id> <text>' (echoes back as a whisper)")
    p.add_argument("--raw", action="store_true", help="Print raw frames instead of pretty JSON")
    p.add_argument("--no-pow", action="store_true",
                   help="Skip the PoW (debug). Rarely works: the gate binds clearance to the TLS "
                        "fingerprint, so a browser/curl-minted ttrs_clearance is usually rejected here.")
    args = p.parse_args()

    host = urlparse(args.url).hostname
    cookies = load_cookies(args.cookie, args.cookies_file, host)
    for required in ("xf_user", "xf_session"):
        if required not in cookies:
            print(f"[{ts()}] WARNING: cookie '{required}' not present — auth will likely fail.")
    print(f"[{ts()}] cookies: {', '.join(cookies) or '(none)'}")

    def handle(message: str) -> None:
        if args.raw:
            print(f"[{ts()}] <<< {message}")
            return
        try:
            obj = json.loads(message)
        except json.JSONDecodeError:
            print(f"[{ts()}] <<< (non-JSON) {message}")
            return
        if "whisper" in obj:
            wm = obj["whisper"]
            author = (wm.get("author") or {}).get("username", "?")
            recipient = (wm.get("recipient") or {}).get("username", "?")
            body = wm.get("message_raw") or wm.get("message") or ""
            print(f"[{ts()}] ★★★ WHISPER  {author} → {recipient}: {body}")
            print(f"            full: {json.dumps(wm, ensure_ascii=False)}")
        elif "messages" in obj:
            print(f"[{ts()}] <<< messages ({len(obj['messages'])})  [room traffic / backlog]")
        elif "system" in obj:
            print(f"[{ts()}] <<< system: {obj['system']}")
        elif "permissions" in obj:
            print(f"[{ts()}] <<< permissions: {json.dumps(obj['permissions'], ensure_ascii=False)}")
        elif "users" in obj:
            print(f"[{ts()}] <<< users ({len(obj['users'])})")
        else:
            print(f"[{ts()}] <<< {{{', '.join(obj.keys())}}}: {message[:300]}")

    print(f"[{ts()}] connecting… (Ctrl+C to quit)")
    ws = gate_connect(args.url, cookies, USER_AGENT, do_pow=not args.no_pow)
    print(f"[{ts()}] OPEN — connected to {args.url}")

    if args.join is not None:
        print(f"[{ts()}] >>> /join {args.join}  (CONTROL mode: joining a room)")
        ws.send(f"/join {args.join}")
    else:
        print(f"[{ts()}] NOT joining any room — listening for whispers on the bare connection.")
    if args.send_whisper:
        ident, _, text = args.send_whisper.partition(":")
        print(f"[{ts()}] >>> /w {ident.strip()} {text}")
        ws.send(f"/w {ident.strip()} {text}")

    stop = threading.Event()

    # Keep-alive via WebSocket PING control frames (there is no valid `/ping` chat command).
    def heartbeat():
        while not stop.wait(PING_INTERVAL):
            try:
                ws.ping()
            except Exception as e:
                print(f"[{ts()}] ws ping failed: {e}")
                return
    threading.Thread(target=heartbeat, daemon=True).start()

    try:
        while True:
            msg = ws.recv()
            if msg is None or msg == "":
                continue
            if isinstance(msg, bytes):
                msg = msg.decode("utf-8", "replace")
            handle(msg)
    except WebSocketConnectionClosedException:
        print(f"[{ts()}] CLOSED by server")
    except KeyboardInterrupt:
        print(f"\n[{ts()}] bye")
    finally:
        stop.set()
        try:
            ws.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
