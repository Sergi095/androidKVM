# androidKVM

A small, open-source Android client for PiKVM-compatible devices. It uses the
PiKVM HTTP and input WebSocket APIs with live video. It also works with a NanoKVM
running a PiKVM-compatible bridge that exposes those endpoints. No TouchKVM code
or Termux code is included.

## Features

- Log in with the KVM web account. Save several KVM profiles and tap one to
  reconnect; saved passwords are encrypted with a key in Android Keystore.
- Pick a KVM from a compact device list or add a new one. The dark console
  keeps the video prominent, with quick keyboard, mouse-mode, and power controls.
- View the live screen in a console that expands when the Android keyboard is hidden.
- On a NanoKVM bridge running on the same host as NanoKVM, use its native H.264
  Direct stream with Android's video decoder when the certificate pin matches.
  Other PiKVM-compatible devices use MJPEG, and MJPEG remains the fallback.
- Move the mouse by touching or dragging on the screen. Tap for left click,
  double tap for double click, long press then move for left-button drag,
  two-finger tap for right click, and two-finger vertical swipe to scroll.
  The mouse control button can switch between direct touch and relative trackpad mode.
- See the reported ATX power LED state and send On or soft Off when ATX control is available.
- Use a Termux-style extra-key bar with Escape, Tab, Ctrl, Alt, arrows,
  Home, End, Page Up, Page Down, and a swipeable F1–F12 row. It sits directly
  above the Android keyboard and hides with it. Tap a modifier first for a shortcut.
- Type directly with the Android keyboard. The keyboard button in the console
  toolbar shows or hides it, and the remote screen adjusts to the space available.
- Mouse movement and ordinary keys use the input WebSocket for lower latency.
  Short text uses it when the KVM reports the US keymap; other text and longer
  pastes use `/api/hid/print`.

The app saves a password only when "Save this KVM and password on this phone" is
checked. Saved profiles contain the address, username, certificate fingerprint,
and encrypted password. The encryption key stays in Android Keystore, and Android
backup is disabled for the app. It requires HTTPS. If Android
does not trust the KVM certificate, the app shows its SHA-256 fingerprint and
waits for confirmation before sending credentials. Compare that fingerprint
through a trusted channel. For example, on the KVM itself:

```sh
openssl x509 -in /path/to/server.crt -noout -fingerprint -sha256
```

For PiKVM with two-factor authentication, append the current one-time code to
the password during login, as specified by the PiKVM API.

## Install a local APK

Requires Android 8.0 or later. Build with JDK 17 and Android SDK platform 35:

```sh
./gradlew assembleDebug
```

The debug APK is for ADB testing and uses a different signing key. Use a signed
release APK for normal installation; Android cannot install the debug APK over
the release APK. Enter the
device hostname or HTTPS URL. The default port is 443; for a bridge listening
on another port, include it in the address, such as `nanokvm:8443`.

The phone must be able to reach the KVM over the LAN or a VPN. General PiKVM
video uses `/streamer/stream` (MJPEG). On a bridge identified through `/api/info`,
the app also tries `https://<same-host>:443/api/stream/h264/direct` after verifying
that the native service presents the same pinned certificate. Audio and virtual
media are outside this initial app.

Power buttons follow the KVM's reported ATX LED state. If the screen is live
but the app reports the power LED as off, check the KVM or bridge's ATX sensing
before relying on those buttons.

## GitHub Actions releases

The workflow builds a debug APK on pushes and manual runs. A `v*` tag also
builds a signed release APK and uploads it to a GitHub Release. Before using
tagged releases, configure these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROIDKVM_KEYSTORE_BASE64` | Base64 of the release `.jks` file, without line breaks |
| `ANDROIDKVM_STORE_PASSWORD` | Keystore password |
| `ANDROIDKVM_KEY_ALIAS` | Key alias |
| `ANDROIDKVM_KEY_PASSWORD` | Key password |

Keep the release keystore safe. Android updates require APKs signed with the
same key. The project has no remote repository configured at this stage.

## Protocol references

- [PiKVM HTTP API](https://docs.pikvm.org/api/)
- [PiKVM input WebSocket API](https://github.com/pikvm/kvmd/blob/master/kvmd/apps/kvmd/api/hid.py)
- [PiKVM video modes](https://docs.pikvm.org/video/)
- [NanoKVM Direct H.264 stream](https://github.com/sipeed/NanoKVM/blob/main/web/src/pages/desktop/screen/direct.worker.ts)
- A PiKVM-compatible NanoKVM bridge can be used as an endpoint when it exposes
  the same login, ATX, HID and MJPEG paths.

## License

MIT. See [LICENSE](LICENSE).
The raccoon app icon is an original vector included under the same license.
