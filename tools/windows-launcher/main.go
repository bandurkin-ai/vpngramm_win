//go:build windows

// vpngramm.exe — запуск vpngramm для Windows: Java из папки runtime рядом с программой, без окна консоли.
// Если Java не нашлась или приложение сразу упало — окно с объяснением и путём к журналу.
package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

const mainClass = "app.hy2setup.desktop.MainKt"

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail("Не удалось определить папку программы: " + err.Error())
	}
	dir := filepath.Dir(exe)
	javaw := filepath.Join(dir, "runtime", "bin", "javaw.exe")
	if _, err := os.Stat(javaw); err != nil {
		fail("Рядом с vpngramm.exe нет папки runtime (в ней Java, без неё программа не запустится).\n\n" +
			"Распакуйте архив целиком («Извлечь всё…») и запускайте vpngramm.exe из распакованной папки, а не прямо из архива.")
	}

	logPath := filepath.Join(dataDir(), "launcher.log")
	_ = os.MkdirAll(filepath.Dir(logPath), 0o700)
	log, _ := os.Create(logPath)

	cmd := exec.Command(javaw,
		"-Xmx1g",
		// прокси Windows (например, Happ в режиме системного прокси) — и для запросов приложения
		"-Djava.net.useSystemProxies=true",
		"-Dfile.encoding=UTF-8",
		"-cp", filepath.Join(dir, "app", "lib", "*"),
		mainClass,
	)
	cmd.Dir = dir
	if log != nil {
		cmd.Stdout, cmd.Stderr = log, log
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	start := time.Now()
	err = cmd.Run()
	if log != nil {
		_ = log.Close()
	}
	var exit *exec.ExitError
	switch {
	case err == nil:
	case errors.As(err, &exit) && time.Since(start) > 20*time.Second:
		// упало во время работы — отчёт покажет само приложение при следующем запуске
		os.Exit(exit.ExitCode())
	case errors.As(err, &exit):
		fail(fmt.Sprintf("vpngramm не запустился (код %d).\n\nПодробности — в файле:\n%s", exit.ExitCode(), logPath))
	default:
		fail("Не удалось запустить Java: " + err.Error())
	}
}

// dataDir — %APPDATA%\vpngramm (там же настройки и зашифрованные ключи приложения).
func dataDir() string {
	if a := os.Getenv("APPDATA"); a != "" {
		return filepath.Join(a, "vpngramm")
	}
	return filepath.Join(os.TempDir(), "vpngramm")
}

func fail(text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString("vpngramm")
	const mbIconError = 0x10
	_, _, _ = box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), mbIconError)
	os.Exit(1)
}
