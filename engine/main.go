// vpncore — VPN-движок vpngramm: sing-box внутри, связь с приложением через локальный сокет.
//
// Приложение (Android VpnService) создаёт TUN-интерфейс и отдаёт его дескриптор движку по запросу «TUN»;
// каждый исходящий сокет движка приложение «защищает» (VpnService.protect) по запросу «PROTECT», чтобы
// трафик к серверу не заворачивался обратно в туннель. Сведения о сетях (Wi-Fi, мобильная) приложение
// присылает строкой «IFACES {json}» по постоянному соединению событий; «STOP» — остановить движок.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/unix"
)

// Сеть телефона, как её описывает приложение.
type ifaceInfo struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Addresses []string `json:"addresses"`
	Type      string   `json:"type"` // wifi | cellular | ethernet | other
	Metered   bool     `json:"metered"`
}

type ifacesMsg struct {
	Interfaces   []ifaceInfo `json:"interfaces"`
	DefaultIndex int         `json:"default"` // -1 — сети нет
}

var socketName string

// request — короткое соединение с приложением: команда (и, если нужно, дескриптор) → ответ (и дескриптор).
func request(cmd string, sendFd int) (string, int, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: "@" + socketName, Net: "unix"})
	if err != nil {
		return "", -1, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	var oob []byte
	if sendFd >= 0 {
		oob = unix.UnixRights(sendFd)
	}
	if _, _, err = conn.WriteMsgUnix([]byte(cmd+"\n"), oob, nil); err != nil {
		return "", -1, err
	}
	buf := make([]byte, 512)
	rOob := make([]byte, unix.CmsgSpace(4*4))
	n, oobn, _, _, err := conn.ReadMsgUnix(buf, rOob)
	if err != nil {
		return "", -1, err
	}
	reply := strings.TrimSpace(string(buf[:n]))
	fd := -1
	if oobn > 0 {
		if msgs, perr := unix.ParseSocketControlMessage(rOob[:oobn]); perr == nil {
			for _, m := range msgs {
				if fds, ferr := unix.ParseUnixRights(&m); ferr == nil && len(fds) > 0 {
					fd = fds[0]
				}
			}
		}
	}
	return reply, fd, nil
}

type platform struct {
	access         sync.Mutex
	networkManager adapter.NetworkManager
	ifaces         ifacesMsg
	myTunName      string
	myTunAddress   []netip.Addr
	monitor        *monitor
}

var _ adapter.PlatformInterface = (*platform)(nil)

func (p *platform) Initialize(nm adapter.NetworkManager) error { p.networkManager = nm; return nil }
func (p *platform) UsePlatformAutoDetectInterfaceControl() bool   { return true }

// Сокет к серверу — мимо туннеля: приложение вызывает VpnService.protect(fd).
func (p *platform) AutoDetectInterfaceControl(fd int) error {
	reply, _, err := request("PROTECT", fd)
	if err != nil {
		return E.Cause(err, "protect")
	}
	if reply != "OK" {
		return E.New("protect: ", reply)
	}
	return nil
}

func (p *platform) UsePlatformInterface() bool { return true }

func (p *platform) OpenInterface(options *tun.Options, _ option.TunPlatformOptions) (tun.Tun, error) {
	reply, fd, err := request("TUN", -1)
	if err != nil {
		return nil, E.Cause(err, "open tun")
	}
	if fd < 0 {
		return nil, E.New("open tun: ", reply)
	}
	var ifr [unix.IFNAMSIZ + 64]byte
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNGETIFF), uintptr(unsafe.Pointer(&ifr[0]))); errno == 0 {
		options.Name = unix.ByteSliceToString(ifr[:])
	} else if options.Name == "" {
		options.Name = "tun0"
	}
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(options.Name)
	}
	options.FileDescriptor = fd
	p.access.Lock()
	p.myTunName = options.Name
	p.myTunAddress = nil
	for _, a := range options.Inet4Address {
		p.myTunAddress = append(p.myTunAddress, a.Addr())
	}
	for _, a := range options.Inet6Address {
		p.myTunAddress = append(p.myTunAddress, a.Addr())
	}
	p.access.Unlock()
	return tun.New(*options)
}

func (p *platform) UsePlatformDefaultInterfaceMonitor() bool { return true }

func (p *platform) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	p.access.Lock()
	defer p.access.Unlock()
	p.monitor = &monitor{p: p, logger: l}
	return p.monitor
}

func (p *platform) UsePlatformNetworkInterfaces() bool { return true }

func (p *platform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	p.access.Lock()
	defer p.access.Unlock()
	var out []adapter.NetworkInterface
	for _, it := range p.ifaces.Interfaces {
		if it.Name == p.myTunName {
			continue
		}
		var prefixes []netip.Prefix
		for _, a := range it.Addresses {
			if pr, err := netip.ParsePrefix(a); err == nil {
				prefixes = append(prefixes, pr)
			}
		}
		t := C.InterfaceTypeOther
		switch it.Type {
		case "wifi":
			t = C.InterfaceTypeWIFI
		case "cellular":
			t = C.InterfaceTypeCellular
		case "ethernet":
			t = C.InterfaceTypeEthernet
		}
		mtu := it.MTU
		if mtu <= 0 {
			mtu = 1500
		}
		out = append(out, adapter.NetworkInterface{
			Interface: control.Interface{Index: it.Index, MTU: mtu, Name: it.Name, Addresses: prefixes, Flags: net.FlagUp | net.FlagRunning},
			Type:      t, Expensive: it.Metered,
		})
	}
	return out, nil
}

func (p *platform) UnderNetworkExtension() bool                 { return false }
func (p *platform) NetworkExtensionIncludeAllNetworks() bool     { return false }
func (p *platform) ClearDNSCache()                               {}
func (p *platform) RequestPermissionForWIFIState() error         { return nil }
func (p *platform) ReadWIFIState() adapter.WIFIState             { return adapter.WIFIState{} }
func (p *platform) SystemCertificates() []string                 { return nil }
func (p *platform) UsePlatformConnectionOwnerFinder() bool       { return false }
func (p *platform) UsePlatformWIFIMonitor() bool                 { return false }
func (p *platform) UsePlatformNotification() bool                { return false }
func (p *platform) SendNotification(*adapter.Notification) error { return nil }
func (p *platform) FindConnectionOwner(*adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (p *platform) MyInterfaceAddress() []netip.Addr {
	p.access.Lock()
	defer p.access.Unlock()
	return p.myTunAddress
}

// monitor — основная сеть телефона по данным приложения (Android не даёт приложениям читать маршруты через netlink).
type monitor struct {
	p            *platform
	logger       logger.Logger
	access       sync.Mutex
	callbacks    list.List[tun.DefaultInterfaceUpdateCallback]
	current      *control.Interface
	myInterfaces []string
}

func (m *monitor) Start() error {
	m.update()
	return nil
}
func (m *monitor) Close() error             { return nil }
func (m *monitor) OverrideAndroidVPN() bool { return false }
func (m *monitor) AndroidVPNEnabled() bool  { return false }

func (m *monitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.current
}

func (m *monitor) RegisterCallback(cb tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(cb)
}

func (m *monitor) UnregisterCallback(e *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(e)
}

func (m *monitor) RegisterMyInterface(name string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, name)
}

func (m *monitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

// update — основная сеть сменилась (или пришла впервые): сообщаем sing-box.
func (m *monitor) update() {
	nm := m.p.networkManager
	if nm == nil {
		return
	}
	if err := nm.UpdateInterfaces(); err != nil {
		m.logger.Error(E.Cause(err, "update interfaces"))
	}
	m.p.access.Lock()
	idx := m.p.ifaces.DefaultIndex
	m.p.access.Unlock()
	var next *control.Interface
	if idx >= 0 {
		if it, err := nm.InterfaceFinder().ByIndex(idx); err == nil {
			next = it
		}
	}
	m.access.Lock()
	old := m.current
	m.current = next
	cbs := m.callbacks.Array()
	m.access.Unlock()
	if old != nil && next != nil && old.Name == next.Name && old.Index == next.Index {
		return
	}
	for _, cb := range cbs {
		cb(next, 0)
	}
}

func main() {
	configPath := flag.String("config", "", "файл конфигурации sing-box")
	flag.StringVar(&socketName, "socket", "", "имя локального сокета приложения (abstract)")
	check := flag.Bool("check", false, "только проверить конфигурацию")
	flag.Parse()

	data, err := os.ReadFile(*configPath)
	if err != nil {
		fatal("чтение конфигурации", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	outbounds := include.OutboundRegistry()
	registerAWG(outbounds)
	registerXray(outbounds)
	ctx = box.Context(ctx, include.InboundRegistry(), outbounds, include.EndpointRegistry(),
		include.DNSTransportRegistry(), include.ServiceRegistry())
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		fatal("разбор конфигурации", err)
	}
	if *check {
		for _, o := range options.Outbounds {
			if ao, ok := o.Options.(*awgOptions); ok {
				if _, err = awgUAPI(*ao); err != nil {
					fatal("разбор конфигурации", err)
				}
			}
			if xo, ok := o.Options.(*xrayOptions); ok {
				if _, err = xrayConfig(*xo); err != nil {
					fatal("разбор конфигурации", err)
				}
			}
		}
		fmt.Println("OK")
		return
	}

	p := &platform{ifaces: ifacesMsg{DefaultIndex: -1}}
	service.MustRegister[adapter.PlatformInterface](ctx, p)

	// постоянное соединение событий: первым приложение присылает сведения о сетях
	events, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: "@" + socketName, Net: "unix"})
	if err != nil {
		fatal("связь с приложением", err)
	}
	_, _ = events.Write([]byte("EVENTS\n"))
	first := make(chan struct{})
	stop := make(chan struct{})
	go func() {
		r := bufio.NewReaderSize(events, 1<<20)
		once := sync.Once{}
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				close(stop) // приложение закрылось — выходим
				return
			}
			line = strings.TrimSpace(line)
			switch {
			case strings.HasPrefix(line, "IFACES "):
				var msg ifacesMsg
				if json.Unmarshal([]byte(strings.TrimPrefix(line, "IFACES ")), &msg) == nil {
					p.access.Lock()
					p.ifaces = msg
					mon := p.monitor
					p.access.Unlock()
					if mon != nil {
						mon.update()
					}
					once.Do(func() { close(first) })
				}
			case line == "STOP":
				close(stop)
				return
			}
		}
	}()
	select {
	case <-first:
	case <-time.After(10 * time.Second):
		fatal("связь с приложением", errors.New("нет сведений о сетях телефона"))
	}

	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		fatal("создание", err)
	}
	if err = instance.Start(); err != nil {
		fatal("запуск", err)
	}
	fmt.Println("STARTED")
	_, _ = events.Write([]byte("STARTED\n"))

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	select {
	case <-stop:
	case <-sig:
	}
	_ = instance.Close()
	fmt.Println("STOPPED")
}

func fatal(what string, err error) {
	fmt.Fprintf(os.Stderr, "ERROR %s: %v\n", what, err)
	os.Exit(1)
}
