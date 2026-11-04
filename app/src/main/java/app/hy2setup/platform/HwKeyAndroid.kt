package app.hy2setup.platform

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import app.hy2setup.hwkey.CtapHid
import app.hy2setup.hwkey.HwKeyException
import app.hy2setup.hwkey.HwTransport
import app.hy2setup.hwkey.NfcU2f
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Активное окно приложения — нужно, чтобы включить чтение NFC (MainActivity сообщает о себе в onResume/onPause). */
object NfcHost {
    @Volatile var activity: Activity? = null
}

/**
 * Аппаратный ключ (Рутокен MFA) на Android: по USB-C — HID-интерфейс FIDO (CTAPHID), по NFC — IsoDep (ISO 7816).
 * Берётся тот способ, которым токен появится первым.
 */
class HwKeyAndroid(private val app: Application) {
    @Volatile private var cancelled = false
    private val tags = LinkedBlockingQueue<IsoDep>()
    private var usb: UsbDeviceConnection? = null
    private var usbIntf: UsbInterface? = null
    private var iso: IsoDep? = null
    private var readerOn: Activity? = null

    fun open(timeoutMs: Long): HwTransport {
        cancelled = false
        tags.clear()
        startNfc()
        val um = app.getSystemService(UsbManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        var asked: String? = null
        while (true) {
            if (cancelled) throw HwKeyException("Вход по Рутокену отменён.")
            if (System.currentTimeMillis() > deadline) {
                throw HwKeyException("Рутокен не найден: вставьте его в разъём USB-C или приложите к задней крышке телефона (NFC должен быть включён).")
            }
            val dev = um?.deviceList?.values?.firstOrNull { fidoInterface(it) != null }
            if (dev != null) {
                if (um.hasPermission(dev)) openUsb(um, dev)?.let { return it }
                else if (asked != dev.deviceName) {
                    asked = dev.deviceName
                    val pi = PendingIntent.getBroadcast(
                        app, 0, Intent("app.hy2setup.USB_PERMISSION").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE,
                    )
                    um.requestPermission(dev, pi)
                }
            }
            tags.poll(300, TimeUnit.MILLISECONDS)?.let { tag -> openNfc(tag)?.let { return it } }
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun close() {
        stopNfc()
        runCatching { usbIntf?.let { usb?.releaseInterface(it) } }
        runCatching { usb?.close() }
        runCatching { iso?.close() }
        usb = null; usbIntf = null; iso = null
    }

    // ---------------- USB ----------------

    /** HID-интерфейс с входной и выходной конечными точками (у клавиатур и мышей выходной нет). */
    private fun fidoInterface(d: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) return Triple(intf, inEp, outEp)
        }
        return null
    }

    private fun openUsb(um: UsbManager, d: UsbDevice): HwTransport? {
        val (intf, inEp, outEp) = fidoInterface(d) ?: return null
        val c = um.openDevice(d) ?: return null
        if (!c.claimInterface(intf, true)) { c.close(); return null }
        // описатель отчётов HID: у FIDO — страница использования 0xF1D0 (если прочитать не удалось — пробуем всё равно)
        val desc = ByteArray(512)
        val n = c.controlTransfer(0x81, 0x06, 0x2200, intf.id, desc, desc.size, 1000)
        if (n > 0 && !desc.copyOf(n).toList().windowed(3).any { it[0] == 0x06.toByte() && it[1] == 0xD0.toByte() && it[2] == 0xF1.toByte() }) {
            c.releaseInterface(intf); c.close(); return null
        }
        usb = c; usbIntf = intf
        val write: (ByteArray) -> Unit = { p ->
            if (c.bulkTransfer(outEp, p, p.size, 2_000) < 0) throw HwKeyException("Рутокен: не удалось отправить данные по USB — переподключите токен.")
        }
        val read: () -> ByteArray = {
            val buf = ByteArray(CtapHid.PACKET)
            val until = System.currentTimeMillis() + 40_000
            var got = -1
            while (got <= 0) {
                if (cancelled) throw HwKeyException("Вход по Рутокену отменён.")
                if (System.currentTimeMillis() > until) throw HwKeyException("Рутокен не отвечает по USB.")
                got = c.bulkTransfer(inEp, buf, buf.size, 500)
            }
            buf
        }
        return try {
            CtapHid.Transport(write, read, d.productName?.trim().orEmpty().ifEmpty { "USB-токен" })
        } catch (e: Exception) {
            close(); throw e
        }
    }

    // ---------------- NFC ----------------

    private fun startNfc() {
        val a = NfcHost.activity ?: return
        val nfc = NfcAdapter.getDefaultAdapter(a) ?: return
        a.runOnUiThread {
            runCatching {
                nfc.enableReaderMode(
                    a, { tag -> IsoDep.get(tag)?.let { tags.offer(it) } },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                readerOn = a
            }
        }
    }

    private fun stopNfc() {
        val a = readerOn ?: return
        readerOn = null
        a.runOnUiThread { runCatching { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } }
    }

    private fun openNfc(tag: IsoDep): HwTransport? = try {
        tag.connect()
        tag.timeout = 10_000
        iso = tag
        NfcU2f.Transport({ tag.transceive(it) })
    } catch (e: HwKeyException) {
        throw e
    } catch (e: Exception) {
        runCatching { tag.close() }
        null   // метку убрали слишком быстро — ждём следующую
    }
}
