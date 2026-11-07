# Third-party notices

MapMirror's own code and designs are MIT licensed: see [LICENSE](LICENSE). It builds on the open source projects below, each under its own licence. None of their code is copied into this repository. The build tools download them, and a built app or firmware image contains them.

## Android app

| Library | Licence | Source |
|---|---|---|
| AndroidX Core, AppCompat, Lifecycle | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| Conscrypt | Apache-2.0 | https://github.com/google/conscrypt |
| libadb-android, by Muntashir Al-Islam | GPL-3.0-or-later **or** Apache-2.0. MapMirror uses it under Apache-2.0 | https://github.com/MuntashirAkon/libadb-android |
| sun-security-android, by Muntashir Al-Islam, from OpenJDK | GPL-2.0 with the Classpath exception | https://github.com/MuntashirAkon/sun-security-android |

**About sun-security-android:** it's OpenJDK code, and its source files carry the Classpath exception. That allows it to be linked into an app under a different licence, so a built MapMirror app stays MIT. The library itself stays under GPL-2.0, and its source is available at the link above. Anyone distributing a built app should pass that on, for example by keeping this file with it.

## Firmware

| Library | Licence | Source |
|---|---|---|
| Arduino core for the ESP32 | LGPL-2.1 | https://github.com/espressif/arduino-esp32 |
| ESP-IDF, including Mbed TLS | Apache-2.0 | https://github.com/espressif/esp-idf |
| NimBLE-Arduino | Apache-2.0 | https://github.com/h2zero/NimBLE-Arduino |
| LovyanGFX, including code from Adafruit GFX and TFT_eSPI | FreeBSD (BSD-2-Clause), BSD and MIT | https://github.com/lovyan03/LovyanGFX |

**About the Arduino core:** it's LGPL-2.1. Anyone distributing a built firmware image should let people relink it against a modified core. Publishing this repository's source, which builds the image, meets that.

## Case build tools

The case's `build.py` uses these, which aren't part of the printed parts:

| Library | Licence | Source |
|---|---|---|
| manifold3d | Apache-2.0 | https://github.com/elalish/manifold |
| trimesh | MIT | https://github.com/mikedh/trimesh |
| NumPy | BSD-3-Clause | https://github.com/numpy/numpy |
| Matplotlib | Matplotlib licence (BSD-style) | https://github.com/matplotlib/matplotlib |

The full licence texts are in each project's repository.
