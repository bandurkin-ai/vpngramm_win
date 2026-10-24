package main

// Исходящее соединение «awg» для sing-box: AmneziaWG (amneziawg-go) в пользовательском режиме.
//
// В sing-box нет маскировки AmneziaWG, поэтому туннель поднимает amneziawg-go со своим сетевым стеком
// (netstack), а sing-box отдаёт в него TCP-соединения и UDP-пакеты так же, как в VLESS или Hysteria2.
// Маршрутизация, DNS и «российские сайты напрямую» остаются за sing-box.

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// awgOptions — параметры клиента; ключи в base64, как в .conf AmneziaWG.
type awgOptions struct {
	Server              string   `json:"server"` // IPv4 сервера (имя заранее разрешает приложение)
	ServerPort          uint16   `json:"server_port"`
	PrivateKey          string   `json:"private_key"`
	PeerPublicKey       string   `json:"peer_public_key"`
	PreSharedKey        string   `json:"pre_shared_key,omitempty"`
	Address             []string `json:"address"`
	DNS                 []string `json:"dns,omitempty"`
	MTU                 int      `json:"mtu,omitempty"`
	Keepalive           int      `json:"persistent_keepalive,omitempty"`
	Jc                  int      `json:"jc"`
	Jmin                int      `json:"jmin"`
	Jmax                int      `json:"jmax"`
	S1                  int      `json:"s1"`
	S2                  int      `json:"s2"`
	S3                  int      `json:"s3,omitempty"`
	S4                  int      `json:"s4,omitempty"`
	H1                  string   `json:"h1,omitempty"`
	H2                  string   `json:"h2,omitempty"`
	H3                  string   `json:"h3,omitempty"`
	H4                  string   `json:"h4,omitempty"`
	I1                  string   `json:"i1,omitempty"`
	HeaderProtectionKey string   `json:"header_protection_key,omitempty"`
	ContentPadding      string   `json:"content_padding_addition,omitempty"`
}

func registerAWG(registry *outbound.Registry) {
	outbound.Register[awgOptions](registry, "awg", newAWG)
}

type awgOutbound struct {
	outbound.Adapter
	logger log.ContextLogger
	opts   awgOptions
	local4 netip.Addr
	uapi   string
	dev    *device.Device
	tnet   *netstack.Net
}

func newAWG(_ context.Context, _ adapter.Router, logger log.ContextLogger, tag string, o awgOptions) (adapter.Outbound, error) {
	uapi, err := awgUAPI(o)
	if err != nil {
		return nil, err
	}
	a := &awgOutbound{
		Adapter: outbound.NewAdapter("awg", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		logger:  logger, opts: o, uapi: uapi,
	}
	for _, s := range o.Address {
		if p, err := netip.ParsePrefix(s); err == nil && p.Addr().Is4() {
			a.local4 = p.Addr()
		}
	}
	if !a.local4.IsValid() {
		return nil, E.New("awg: нужен IPv4-адрес в туннеле")
	}
	return a, nil
}

// awgUAPI — настройки устройства в формате UAPI WireGuard (ключи — hex).
func awgUAPI(o awgOptions) (string, error) {
	key := func(name, b64 string) (string, error) {
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
		if err != nil || len(raw) != 32 {
			return "", E.New("awg: неверный ключ ", name)
		}
		return hex.EncodeToString(raw), nil
	}
	if o.Server == "" || o.ServerPort == 0 {
		return "", E.New("awg: не указан сервер")
	}
	priv, err := key("private_key", o.PrivateKey)
	if err != nil {
		return "", err
	}
	pub, err := key("peer_public_key", o.PeerPublicKey)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	w := func(k string, v any) { fmt.Fprintf(&b, "%s=%v\n", k, v) }
	w("private_key", priv)
	w("jc", o.Jc)
	w("jmin", o.Jmin)
	w("jmax", o.Jmax)
	w("s1", o.S1)
	w("s2", o.S2)
	w("s3", o.S3)
	w("s4", o.S4)
	for i, h := range []string{o.H1, o.H2, o.H3, o.H4} {
		if h == "" {
			h = fmt.Sprint(i + 1)
		}
		w(fmt.Sprintf("h%d", i+1), h)
	}
	if o.I1 != "" {
		w("i1", o.I1)
	}
	if o.HeaderProtectionKey != "" {
		hk, err := key("header_protection_key", o.HeaderProtectionKey)
		if err != nil {
			return "", err
		}
		w("header_protection_key", hk)
	}
	if o.ContentPadding != "" {
		w("content_padding_addition", o.ContentPadding)
	}
	b.WriteString(bindUAPI())
	w("public_key", pub)
	if o.PreSharedKey != "" {
		psk, err := key("pre_shared_key", o.PreSharedKey)
		if err != nil {
			return "", err
		}
		w("preshared_key", psk)
	}
	w("endpoint", net.JoinHostPort(o.Server, fmt.Sprint(o.ServerPort)))
	if o.Keepalive > 0 {
		w("persistent_keepalive_interval", o.Keepalive)
	}
	w("allowed_ip", "0.0.0.0/0")
	w("allowed_ip", "::/0")
	return b.String(), nil
}

func (a *awgOutbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	mtu := a.opts.MTU
	if mtu <= 0 {
		mtu = 1280
	}
	var dns []netip.Addr
	for _, s := range a.opts.DNS {
		if ip, err := netip.ParseAddr(s); err == nil {
			dns = append(dns, ip)
		}
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{a.local4}, dns, mtu)
	if err != nil {
		return E.Cause(err, "awg: сетевой стек")
	}
	logf := func(prefix string) func(string, ...any) {
		return func(format string, args ...any) { a.logger.Debug(prefix + fmt.Sprintf(format, args...)) }
	}
	dev := device.NewDevice(tunDev, newProtectedBind(), &device.Logger{Verbosef: logf("awg: "), Errorf: logf("awg error: ")})
	if err = dev.IpcSet(a.uapi); err != nil {
		dev.Close()
		return E.Cause(err, "awg: настройки")
	}
	if err = dev.Up(); err != nil {
		dev.Close()
		return E.Cause(err, "awg: запуск")
	}
	a.dev, a.tnet = dev, tnet
	a.logger.Info("AmneziaWG: туннель к ", a.opts.Server, ":", a.opts.ServerPort, " поднят")
	return nil
}

func (a *awgOutbound) Close() error {
	if a.dev != nil {
		a.dev.Close()
	}
	return nil
}

func (a *awgOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if a.tnet == nil {
		return nil, E.New("awg: туннель не запущен")
	}
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		if destination.IsIP() {
			return a.tnet.DialContextTCPAddrPort(ctx, destination.AddrPort())
		}
		return a.tnet.DialContext(ctx, "tcp", destination.String())
	case N.NetworkUDP:
		if destination.IsIP() {
			return a.tnet.DialUDPAddrPort(netip.AddrPort{}, destination.AddrPort())
		}
		return a.tnet.DialContext(ctx, "udp", destination.String())
	}
	return nil, E.New("awg: сеть ", network, " не поддерживается")
}

func (a *awgOutbound) ListenPacket(_ context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	if a.tnet == nil {
		return nil, E.New("awg: туннель не запущен")
	}
	return a.tnet.ListenUDPAddrPort(netip.AddrPortFrom(a.local4, 0))
}
