#!/usr/bin/env python3
"""Замена Android-приложения для проверки движка vpncore в Linux-контейнере.

Как VpnService: создаёт TUN (172.19.0.1/30), заворачивает в него весь трафик, кроме «защищённых» сокетов
(protect = метка SO_MARK 0x1 → основная таблица маршрутов), отдаёт дескриптор TUN движку по запросу и
присылает сведения о сетях. Запуск: fakeapp.py <имя сокета> <движок> <конфиг>.
"""
import array, fcntl, json, os, socket, struct, subprocess, sys, threading

NAME, ENGINE, CONFIG = sys.argv[1], sys.argv[2], sys.argv[3]
TUNSETIFF, IFF_TUN, IFF_NO_PI = 0x400454CA, 0x0001, 0x1000

def sh(c): subprocess.run(c, shell=True, check=False)

tun = os.open("/dev/net/tun", os.O_RDWR)
fcntl.ioctl(tun, TUNSETIFF, struct.pack("16sH", b"tun0", IFF_TUN | IFF_NO_PI))
sh("ip addr add 172.19.0.1/30 dev tun0; ip link set tun0 up mtu 1500")
# как Android: всё — в туннель (таблица 100), защищённые сокеты (метка 1) — по основной таблице
sh("ip route add default dev tun0 table 100; ip rule add fwmark 1 lookup main pref 100; ip rule add from all lookup 100 pref 200")
sh("ip rule add to 172.17.0.0/16 lookup main pref 150")  # docker-сеть (сам сокет управления не IP)
# как VpnService.Builder.addDnsServer: DNS телефона — адрес внутри туннеля, его перехватывает sing-box
open("/etc/resolv.conf", "w").write("nameserver 172.19.0.2\n")

def ifaces():
    out = json.loads(subprocess.check_output("ip -j addr show eth0", shell=True))[0]
    addrs = [f"{a['local']}/{a['prefixlen']}" for a in out["addr_info"]]
    return {"interfaces": [{"name": "eth0", "index": out["ifindex"], "mtu": out["mtu"], "addresses": addrs, "type": "ethernet"}],
            "default": out["ifindex"]}

srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
srv.bind("\0" + NAME)
srv.listen(16)
protected = 0

def handle(c):
    global protected
    msg, fds, _, _ = socket.recv_fds(c, 512, 4)
    cmd = msg.decode().strip()
    if cmd == "EVENTS":
        c.sendall(("IFACES " + json.dumps(ifaces()) + "\n").encode())
        while True:
            line = c.recv(4096)
            if not line:
                return
            print("[app] событие:", line.decode().strip(), flush=True)
    elif cmd == "TUN":
        socket.send_fds(c, [b"FD\n"], [tun])
        print("[app] отдал TUN", flush=True)
    elif cmd == "PROTECT" and fds:
        s = socket.socket(fileno=os.dup(fds[0]))
        s.setsockopt(socket.SOL_SOCKET, 36, 1)  # SO_MARK
        s.detach(); os.close(fds[0])
        protected += 1
        c.sendall(b"OK\n")
    else:
        c.sendall(b"ERR\n")
    c.close()

def loop():
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()

threading.Thread(target=loop, daemon=True).start()
p = subprocess.Popen([ENGINE, "-socket", NAME, "-config", CONFIG])
p.wait()
