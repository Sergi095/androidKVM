# Development

## Build

Requires JDK 17 or newer and Android SDK platform 37.0. For a local test APK:

```sh
./gradlew assembleGithubDebug
```

For a source-built F-Droid candidate, run `./gradlew assembleFdroidRelease` without signing secrets. The unsigned APK is in `app/build/outputs/apk/fdroid/release/`. The F-Droid variant omits the GitHub APK updater and its install permission; F-Droid manages its updates. See [F-Droid preparation](FDROID.md).

The GitHub debug APK is signed with a different key from releases, so it cannot update an installed release. Use a signed release APK for normal installation. An address without a scheme uses HTTPS and port 443; enter `http://` explicitly for HTTP and add a port when needed.

## Releases

[GitHub Actions](../.github/workflows/android.yml) builds a GitHub debug APK on pushes and manual runs. A `v*` tag also builds a signed GitHub APK and uploads it to a GitHub Release. Set these repository secrets for tagged releases:

| Secret | Value |
| --- | --- |
| `ANDROIDKVM_KEYSTORE_BASE64` | Base64 of the release `.jks` file, without line breaks |
| `ANDROIDKVM_STORE_PASSWORD` | Keystore password |
| `ANDROIDKVM_KEY_ALIAS` | Key alias |
| `ANDROIDKVM_KEY_PASSWORD` | Key password |

Keep the release keystore safe: Android updates require the same signing key. The in-app updater verifies the downloaded APK's SHA-256 digest, package, version, and signing certificate before opening Android's installer. Uninstalling the app first removes saved KVMs.

## Connection details

- PiKVM requires HTTPS. NanoKVM supports HTTPS and explicit `http://` addresses because its stock firmware uses HTTP; HTTP sends credentials without transport encryption. Saved passwords are encrypted with a key in Android Keystore. Android backup is disabled. An unrecognized HTTPS certificate must be explicitly trusted; the app then pins that certificate for the saved KVM. A changed certificate prompts again.
- Android 17 requires the Nearby devices permission for direct local network connections. The app requests `ACCESS_LOCAL_NETWORK` at connect time and resumes the connection after unlock if the system prompt temporarily locks the app. Backup and device transfer are both excluded by data extraction rules.
- For PiKVM with two-factor authentication, append the current one-time code to the password during login, as described in the [PiKVM API](https://docs.pikvm.org/api/).
- PiKVM video uses MJPEG. NanoKVM connects directly to its web API and uses H.264 Direct with MJPEG fallback. Audio and virtual media are not implemented.
- NanoKVM video settings use `POST /api/vm/screen`. Its API does not report current values, so the app shows the last values set on this phone. Capture resolution is controlled by the device and host; display scale changes only the phone view.
- NanoKVM shell and serial terminals use its authenticated `/api/vm/terminal` WebSocket. The serial view starts `picocom` inside that shell with the selected port and line settings. The terminal renderer bundles xterm.js and addon-fit under their MIT licenses in `app/src/main/assets/terminal`.
- NanoKVM device settings use its authenticated API. Password changes encrypt both password fields as the NanoKVM web client does, update an existing saved profile, then open a new session because the server revokes the old one.
- NanoKVM scripts use its authenticated `/api/vm/script` routes. The app limits uploads to 1 MB and permits only simple `.sh` or `.py` filenames because the upstream server runs script names through a shell. Foreground output is capped at 1 MB; background runs return without logs. Scripts execute on the NanoKVM device with its privileges. PiKVM's Macro menu controls keyboard macros and has no matching device script manager API.
- The optional full NanoKVM web interface receives the current session cookie inside a same-origin WebView. On HTTPS, certificate errors are accepted only when the presented certificate matches the fingerprint already trusted for that KVM.
- Reset HID uses each device's `/api/hid/reset` endpoint.
- ATX buttons follow the KVM's reported power LED state. If the screen is live but the power LED is reported off, check the KVM's ATX sensing.
- Wake-on-LAN is sent by the KVM, not the phone. NanoKVM uses its native WOL API. PiKVM uses a configured [WOL GPIO output](https://docs.pikvm.org/wol/); enter the output channel in the app if several exist. WOL requires support in the computer's firmware and network adapter, and sending a packet does not confirm the computer woke.
- The app PIN is stored as a salted PBKDF2 hash. Biometric checks use Android's system prompt. If a PIN-only lock is forgotten, clearing app data removes both the lock and saved KVMs.

## Protocol references

- [PiKVM HTTP API](https://docs.pikvm.org/api/)
- [PiKVM input WebSocket API](https://github.com/pikvm/kvmd/blob/master/kvmd/apps/kvmd/api/hid.py)
- [PiKVM video modes](https://docs.pikvm.org/video/)
- [NanoKVM Direct H.264 stream](https://github.com/sipeed/NanoKVM/blob/main/web/src/pages/desktop/screen/direct.worker.ts)
- [NanoKVM HID WebSocket](https://github.com/sipeed/NanoKVM/blob/main/server/service/ws/client.go)
- [NanoKVM video settings API](https://github.com/sipeed/NanoKVM/blob/main/server/service/vm/screen.go)
- [NanoKVM terminal client](https://github.com/sipeed/NanoKVM/blob/main/web/src/pages/terminal/index.tsx)
- [NanoKVM script API](https://github.com/sipeed/NanoKVM/blob/main/server/service/vm/script.go)
- [PiKVM Macro menu](https://docs.pikvm.org/webui/#the-macro-menu)
- [NanoKVM device settings routes](https://github.com/sipeed/NanoKVM/blob/main/server/router/vm.go)
- [NanoKVM account routes](https://github.com/sipeed/NanoKVM/blob/main/server/router/auth.go)
