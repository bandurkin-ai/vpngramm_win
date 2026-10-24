//go:build android

package main

import (
	"github.com/amnezia-vpn/amneziawg-go/conn"
	E "github.com/sagernet/sing/common/exceptions"
)

// protectedBind — UDP-сокеты AmneziaWG сразу после открытия выводятся мимо туннеля (VpnService.protect),
// до первого отправленного пакета; иначе рукопожатие ушло бы обратно в TUN.
type protectedBind struct{ *conn.StdNetBind }

func newProtectedBind() conn.Bind { return &protectedBind{conn.NewStdNetBind().(*conn.StdNetBind)} }

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actual, err := b.StdNetBind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	for _, peek := range []func() (int, error){b.PeekLookAtSocketFd4, b.PeekLookAtSocketFd6} {
		fd, perr := peek()
		if perr != nil || fd < 0 {
			continue // сокета этого семейства нет (например, без IPv6)
		}
		if reply, _, rerr := request("PROTECT", fd); rerr != nil || reply != "OK" {
			_ = b.StdNetBind.Close()
			return nil, 0, E.New("awg: protect: ", reply, rerr)
		}
	}
	return fns, actual, nil
}

func bindUAPI() string { return "" }

// protectFd — вывести сокет мимо туннеля через приложение (VpnService.protect).
func protectFd(fd int) error {
	reply, _, err := request("PROTECT", fd)
	if err != nil {
		return err
	}
	if reply != "OK" {
		return E.New("protect: ", reply)
	}
	return nil
}
