# Phone Keyboard (p2keyboard)

Type on an Android smart board (Newline etc.) from your phone instead of using the board's
oversized on-screen keyboard.

## Downloads

| Install on | APK | |
| --- | --- | --- |
| your phone | [`PhoneKeyboard-phone.apk`](https://github.com/Undertaker-afk/p2keyboard/raw/ccr-2711fcaa-kf41p4/dist/PhoneKeyboard-phone.apk) | pairing, scan screen, landscape keyboard |
| the smart board | [`PhoneKeyboard-board.apk`](https://github.com/Undertaker-afk/p2keyboard/raw/ccr-2711fcaa-kf41p4/dist/PhoneKeyboard-board.apk) | switches keyboards by itself (accessibility service) |
| the smart board, if Play Protect blocks the one above | [`PhoneKeyboard-board-lite.apk`](https://github.com/Undertaker-afk/p2keyboard/raw/ccr-2711fcaa-kf41p4/dist/PhoneKeyboard-board-lite.apk) | no accessibility service; automatic switching via a one-time adb command |

Google Play Protect blocks sideloaded apps that declare an accessibility service, so the phone
APK has none, and the board comes in two versions. Each APK only contains its own side:

- **Board:** keeps its normal keyboard. Phone Keyboard (an **input method** with no keys) takes
  over only while a paired phone is connected. A **background service** keeps the link open:
  foreground notification, exemption from battery optimization, Wi-Fi lock, restart after
  reboot, and an on/off switch.
- **Phone:** opens **locked to landscape, full screen**, with the app's **own on-screen keyboard**,
  drawn by the app rather than using Android's keyboard or a text box. A strip above the keys
  shows what the board's text box holds, live.

Devices pair once, RustDesk style: every install has a permanent **device ID** (like
`482 913 077`) and a name. On the phone, **Pair device** asks for the camera, scans the board's
QR code, and adds the board to your **paired devices** list. After that you tap the board to
connect; no code needed. The board keeps a list of its paired phones, and you can disconnect or
unpair each one.

Pairing and transport go over **Tailscale's public DERP relays**, so phone and board don't need
to be on the same network and nothing needs port forwarding. Both connect out to the same relay
over HTTPS (port 443), which gets through NAT, guest Wi-Fi and school firewalls.

## Setup

**Board**
1. Install `PhoneKeyboard-board.apk` (or `-board-lite`) and open *Phone Keyboard*.
2. **1. Enable keyboard** → turn on *Phone Keyboard (pair with QR)*. Don't select it: the board
   keeps its normal keyboard, and Phone Keyboard switches in by itself when a phone connects.
3. **2. Allow background running** → allow notifications and "don't optimize battery".
   On boards with their own app freezer or cleaner, whitelist the app there too.
4. **3. Allow automatic keyboard switching** (board APK) → under Accessibility, turn on *Phone Keyboard*
   (Android 11+). It only switches keyboards and reads nothing on screen. On older Android, or
   instead of that, run once from a computer:
   `adb shell pm grant app.p2keyboard android.permission.WRITE_SECURE_SETTINGS`.
   Without either, the board still works: when a phone connects, tap the notification and pick
   Phone Keyboard.
5. The board's QR code is on the app's start screen. Phones pair with it once.

**When is Phone Keyboard active?** Only while a paired phone is connected. The phone sends a
heartbeat every 2 seconds. When the phone leaves, or nothing arrives for about 6 seconds (app
closed, phone locked, Wi-Fi gone), the board switches back to its normal keyboard on its own.

**Turning it off:** use the **Phone Keyboard on this board** switch in the app, or **Turn off** in
the notification. Then nothing runs on the board, which keeps its normal keyboard, until you turn
it back on. On the phone, **Disconnect** (or just leaving the keyboard screen) ends the
connection at once.

**Phone**
1. Install `PhoneKeyboard-phone.apk` and open it. The top card shows this phone's name (tap it to rename) and ID.
2. **Pair device** → allow the camera → point it at the board's QR code. The QR code is in the
   board's keyboard area when you tap a text box, and on the board's start screen.
   You can also scan with the stock camera: `p2k://` links open the app and pair.
3. The board appears under **Paired devices** and the landscape keyboard opens. Next time,
   just tap the board in the list. The board switches to Phone Keyboard while you're connected.

On the board, **New code** replaces the QR code so it can't pair new phones; already-paired
phones keep working. To remove a phone, tap it under *Phones paired with this board* →
**Unpair**. On the phone, long-press a device to remove it from the list.

## The phone keyboard

A PC-style layout made for landscape:

- Number row with shifted symbols, full punctuation, Tab, Caps, two Shifts.
- Ctrl and Esc, Paste (sends the phone's clipboard), and arrow keys.
- **Enter matches the board's field.** It shows Search / Go / Send / Next when the field has
  that action and runs it; in multi-line fields it inserts a new line.
- **Ctrl shortcuts:** Ctrl+A / C / V / X / Z go to the board as real key combinations.
- **Shift+arrows select text**, and Ctrl+arrows jump by word. Hold Shift or Ctrl with one finger
  and they stay on, like a real keyboard; tap them and they apply to the next key only.
- Multi-touch, so two thumbs can type fast. Backspace and the arrows repeat while held.

Keys are sent exactly as a hardware keyboard sends them, with modifiers pressed first and
released last, so the board's apps treat them like a real keyboard.

## How it works

```
 phone: KeyboardView ──keys──▶ PhoneSession ─┐                         ┌─▶ BoardLinkService (foreground)
                                             │  SecureLink  ──DERP──▶  │        │
 phone: preview strip ◀── board's text ──────┘  NaCl box E2E (TLS 443) └──      ▼
                                                                         RemoteKeyboardService (IME)
                                                                           └─ OpApplier → focused text box
```

- **DERP:** `DerpClient` speaks Tailscale's DERP protocol (HTTP upgrade, then `ServerKey` /
  `ClientInfo` handshake and `SendPacket` / `RecvPacket` frames). The board picks the nearest
  region from `login.tailscale.com/derpmap/default` and puts that relay in the QR code.
- **Security:** each device has a Curve25519 key (its device ID is a hash of it). The QR code
  holds the board's public key, the relay host, its name and a random 128-bit secret. A phone
  proves it scanned the code once; the board then trusts that phone's key, and the phone drops
  the secret. Every message is sealed end-to-end with NaCl box
  (X25519 + XSalsa20-Poly1305) and carries an increasing sequence number, so the relay sees only
  ciphertext and can't replay keystrokes. Password fields are shown as dots in the phone's preview.
- **Background:** `BoardLinkService` owns the link, so it survives the keyboard being hidden, the
  screen going off or another app being in front. The IME binds to it, which Android allows even
  when a background start isn't. `BootReceiver` brings it back after a reboot.

## Code

| Path | What |
| --- | --- |
| `core/DerpClient.java` | Tailscale DERP protocol client |
| `core/NaclBox.java` | NaCl `crypto_box` (BouncyCastle primitives), verified against tweetnacl and the NaCl spec vectors |
| `core/SecureLink.java` | Reconnecting, end-to-end encrypted, replay-protected DERP link |
| `core/BoardSession.java`, `core/PhoneSession.java` | Pairing and trust, liveness, disconnect and unpair |
| `core/DeviceId.java`, `Identity.java` | Device ID and name |
| `board/PairedPhones.java`, `phone/PairedBoards.java` | Paired-device lists on each side |
| `MainActivity.java`, `phone/ScanActivity.java` | Home screen (this device, Pair device, device lists) and the scan screen |
| `core/RemoteOps.java` | Wire format |
| `board/BoardLinkService.java`, `board/BootReceiver.java` | Background link (on/off switch, starts at boot) |
| `board/KeyboardSwitch.java`, `board/SwitcherAccessibilityService.java` | Switching to Phone Keyboard and back as phones come and go |
| `board/RemoteKeyboardService.java`, `board/OpApplier.java` | Board input method: QR code, typing into the focused field, live preview |
| `phone/PhoneActivity.java`, `phone/KeyboardView.java`, `phone/KeyPress.java` | Landscape phone screen and its keyboard |

## Build

```
./gradlew testPhoneDebugUnitTest testBoardDebugUnitTest testBoardliteDebugUnitTest assembleRelease
# -> app/build/outputs/apk/{phone,board,boardlite}/release/*.apk
```

Needs JDK 17 and the Android SDK (platform 34). The release APK is minified and signed with the
debug key so it can be sideloaded. GitHub Actions builds it on every push.

`KeyboardSwitchTest` covers when to switch and the switching itself.
Tests: `CoreTest` covers the crypto vectors, pairing codes, device IDs, op parsing and DERP map
parsing. `HomeScreenTest` checks that Pair device asks for the camera before opening the scanner,
that a scanned or camera-linked board is saved and opened, and that the device list works.
`KeyboardRelayTest` (Robolectric) taps the real keyboard view at its key positions and replays
the result on a real `EditText` through the board's code. It covers Shift, Caps, symbols,
Backspace, arrows, held Shift for selection, Enter versus the field's Search action, and Ctrl
shortcuts. Set `KEYBOARD_PNG=/path/out.png` to render the keyboard, or `ROBOLECTRIC_DEPS_DIR` if
Robolectric can't download its Android jar.

## Notes

- Tailscale runs the public DERP servers. For heavy use, run your own `derper` and use its host.
- Typing latency is the round trip to the relay region: roughly 30–120 ms.
