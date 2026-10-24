"""Конфигурация сервера AmneziaWG (.conf, как пишет приложение) → команды UAPI для amneziawg-go (стенд)."""
import base64, sys
KEYS = {"privatekey": "private_key", "publickey": "public_key", "presharedkey": "preshared_key", "headerprotectionkey": "header_protection_key"}
SIMPLE = {"listenport": "listen_port", "jc": "jc", "jmin": "jmin", "jmax": "jmax", "s1": "s1", "s2": "s2", "s3": "s3", "s4": "s4",
          "h1": "h1", "h2": "h2", "h3": "h3", "h4": "h4", "i1": "i1", "contentpaddingaddition": "content_padding_addition",
          "allowedips": "allowed_ip"}
out = ["set=1"]
for line in open(sys.argv[1]):
    line = line.strip()
    if line == "[Peer]" or "=" not in line:
        continue
    k, v = (x.strip() for x in line.split("=", 1))
    k = k.lower()
    if k in KEYS:
        out.append(f"{KEYS[k]}={base64.b64decode(v).hex()}")
    elif k in SIMPLE:
        out.append(f"{SIMPLE[k]}={v}")
    elif k == "randomtrailers" and v != "off":
        sys.exit("RandomTrailers на стенде не поддерживается")
print("\n".join(out) + "\n")
