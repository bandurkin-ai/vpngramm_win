package main

// Исходящее соединение «xray» для sing-box: VLESS + Reality + XHTTP через Xray-core.
//
// В sing-box нет транспорта XHTTP, поэтому соединение к серверу строит встроенный Xray (без своих входов):
// sing-box отдаёт ему TCP-соединения и UDP-пакеты, как любому outbound. Маршрутизация, DNS и «российские
// сайты напрямую» остаются за sing-box.

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"os"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	core "github.com/xtls/xray-core/core"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

	// только то, что нужно клиенту VLESS + Reality + XHTTP
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
)

// xrayOptions — исходящее соединение в формате Xray (vless, streamSettings с xhttp и reality).
type xrayOptions struct {
	Outbound json.RawMessage `json:"xray"`
}

func registerXray(registry *outbound.Registry) {
	outbound.Register[xrayOptions](registry, "xray", newXray)
	// сокеты Xray к серверу — мимо туннеля (на Android — VpnService.protect)
	_ = internet.RegisterDialerController(func(_, _ string, c syscall.RawConn) error {
		var perr error
		if err := c.Control(func(fd uintptr) { perr = protectFd(int(fd)) }); err != nil {
			return err
		}
		return perr
	})
}

// xrayConfig — полная конфигурация Xray из одного исходящего соединения.
func xrayConfig(o xrayOptions) (*core.Config, error) {
	if len(o.Outbound) == 0 {
		return nil, E.New("xray: нет исходящего соединения")
	}
	full, _ := json.Marshal(map[string]any{
		"log":       map[string]any{"loglevel": xrayLogLevel()},
		"outbounds": []json.RawMessage{o.Outbound},
	})
	cfg, err := serial.LoadJSONConfig(bytes.NewReader(full))
	if err != nil {
		return nil, E.Cause(err, "xray: конфигурация")
	}
	return cfg, nil
}

type xrayOutbound struct {
	outbound.Adapter
	logger   log.ContextLogger
	cfg      *core.Config
	instance *core.Instance
}

func newXray(_ context.Context, _ adapter.Router, logger log.ContextLogger, tag string, o xrayOptions) (adapter.Outbound, error) {
	cfg, err := xrayConfig(o)
	if err != nil {
		return nil, err
	}
	return &xrayOutbound{
		Adapter: outbound.NewAdapter("xray", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		logger:  logger, cfg: cfg,
	}, nil
}

func (x *xrayOutbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	inst, err := core.New(x.cfg)
	if err != nil {
		return E.Cause(err, "xray: создание")
	}
	if err = inst.Start(); err != nil {
		return E.Cause(err, "xray: запуск")
	}
	x.instance = inst
	x.logger.Info("Xray (VLESS XHTTP) запущен")
	return nil
}

func (x *xrayOutbound) Close() error {
	if x.instance != nil {
		return x.instance.Close()
	}
	return nil
}

func xrayDest(network string, d M.Socksaddr) xnet.Destination {
	var addr xnet.Address
	if d.IsIP() {
		addr = xnet.IPAddress(d.Addr.AsSlice())
	} else {
		addr = xnet.DomainAddress(d.Fqdn)
	}
	if N.NetworkName(network) == N.NetworkUDP {
		return xnet.UDPDestination(addr, xnet.Port(d.Port))
	}
	return xnet.TCPDestination(addr, xnet.Port(d.Port))
}

func (x *xrayOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if x.instance == nil {
		return nil, E.New("xray: не запущен")
	}
	// sing-box отменяет контекст установки соединения сразу после неё, а Xray по отмене закрыл бы сессию:
	// соединение живёт до Close()
	return core.Dial(context.WithoutCancel(ctx), x.instance, xrayDest(network, destination))
}

func (x *xrayOutbound) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	if x.instance == nil {
		return nil, E.New("xray: не запущен")
	}
	return core.DialUDP(context.WithoutCancel(ctx), x.instance)
}

// xrayLogLevel — подробный журнал Xray по VPNCORE_XRAY_LOG=debug (для стенда).
func xrayLogLevel() string {
	if l := os.Getenv("VPNCORE_XRAY_LOG"); l != "" {
		return l
	}
	return "warning"
}
