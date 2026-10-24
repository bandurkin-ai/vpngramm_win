import json,sys
ip,port,uuid,sni,pbk,sid,bypass=sys.argv[1],int(sys.argv[2]),sys.argv[3],sys.argv[4],sys.argv[5],sys.argv[6],sys.argv[7]=="1"
rules=[{"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},{"ip_is_private":True,"outbound":"direct"}]
route={"rules":rules,"final":"proxy","auto_detect_interface":True}
import os
remote={"type":"udp","tag":"remote","server":"8.8.8.8","detour":"proxy"} if os.environ.get("DNS")=="udp" else {"type":"https","tag":"remote","server":"1.1.1.1","detour":"proxy"}
dns={"servers":[remote,{"type":"udp","tag":"direct-dns","server":"77.88.8.8"}],"final":"remote","strategy":"ipv4_only"}
if bypass:
    rules.append({"domain_suffix":["ru","xn--p1ai","su"],"outbound":"direct"})
    rules.append({"rule_set":["geoip-ru"],"outbound":"direct"})
    route["rule_set"]=[{"type":"remote","tag":"geoip-ru","format":"binary","url":"https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs","download_detour":"proxy"}]
    dns["rules"]=[{"domain_suffix":["ru","xn--p1ai","su"],"server":"direct-dns"}]
c={"log":{"level":"info","timestamp":False,"output":"stderr"},"dns":dns,
 "inbounds":[{"type":"tun","tag":"tun","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1500,"auto_route":True,"stack":"gvisor"}],
 "outbounds":[{"type":"vless","tag":"proxy","server":ip,"server_port":port,"uuid":uuid,"flow":"xtls-rprx-vision","packet_encoding":"xudp",
   "tls":{"enabled":True,"server_name":sni,"utls":{"enabled":True,"fingerprint":"chrome"},"reality":{"enabled":True,"public_key":pbk,"short_id":sid}}},
   {"type":"direct","tag":"direct"}],"route":route}
print(json.dumps(c,indent=2))
