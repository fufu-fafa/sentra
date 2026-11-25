# res

Android resources of the [`app`](../../../README.md) module. Most visuals are drawn in Compose
(see [`ui/theme`](../java/com/sentra/ui/README.md)), so this folder is small.

## Contents

| File | What it is |
|---|---|
| `drawable/ic_launcher_foreground.xml` | Launcher icon foreground: an arrow over a vibration arc (dark purple and pink); also used as the monochrome icon |
| `drawable/ic_stat_sentra.xml` | White status-bar icon for notifications |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive launcher icon on `@color/icon_bg` |
| `values/colors.xml` | Window background, card, accent and icon background colours (match `Tokens` in `Theme.kt`) |
| `values/strings.xml` | `app_name` and `default_relay_url` (the VPS relay) |
| `values/themes.xml` | `Theme.Sentra`: light, no action bar, lavender window background, dark status-bar icons |
| `xml/file_paths.xml` | `FileProvider` path `recordings/`, so CSV test logs can be shared |

## Usage notes

Colours here only cover what Android draws before Compose starts; keep them in step with
`ui/theme/Theme.kt`.
