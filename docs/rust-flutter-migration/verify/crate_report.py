import json
import sys
import urllib.request

NAMES = [
    "rustls", "rustls-pki-types", "rustls-webpki", "webpki-root-certs",
    "quinn", "quinn-proto", "s2n-quic", "h3", "h3-quinn",
    "reqwest", "hyper", "tokio", "tokio-rustls",
    "hickory-resolver", "trust-dns-resolver", "hickory-client",
    "jni", "flutter-rust-bridge", "rcgen", "rquest", "ureq", "doh-client",
    "rustls-platform-verifier", "quiche", "ring", "aws-lc-rs",
    "oauth2", "axum", "native-tls", "openssl", "base64", "serde",
    "moka", "quick_cache", "mini-moka", "object_store", "redb", "sled",
    "cacache", "bytes", "http", "url", "thiserror", "anyhow",
]

base = "https://crates.io/api/v1/crates/{}"
rows = []
for name in NAMES:
    req = urllib.request.Request(
        base.format(name),
        headers={"User-Agent": "pixiv-shaft-migration-eval"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.load(resp)
    except Exception as exc:
        rows.append((name, "ERROR", str(exc)[:60], "", "", ""))
        continue
    crate = data["crate"]
    versions = data.get("versions") or []
    recent = [v["num"] for v in versions[:5]]
    created = versions[0]["created_at"][:10] if versions else ""
    rows.append((
        crate["name"],
        crate["max_stable_version"] or crate["max_version"],
        created,
        crate["updated_at"][:10],
        f"{crate['downloads']}",
        ",".join(recent),
    ))

w = [12, 14, 12, 12, 14, 40]
header = ("crate", "max_stable", "published", "updated", "downloads", "recent_versions")
print("".join(h.ljust(x) for h, x in zip(header, w)))
for r in rows:
    print("".join(str(c).ljust(x) for c, x in zip(r, w)))
