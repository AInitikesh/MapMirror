#pragma once

// ---------------------------------------------------------------- Wi-Fi
// Station mode: join your phone's hotspot (recommended in a car) or home Wi-Fi.
// Put the network's name and password in secrets.h, next to this file: copy
// secrets.h.example to secrets.h. Git ignores secrets.h, so they stay out of the
// repo. Without it, or with WIFI_STA_SSID empty, station mode is skipped. If joining
// fails within STA_TIMEOUT_MS the display starts its own access point instead, IP
// 192.168.4.1. Its name ends in part of the board's MAC, and its random password is
// made on first boot and only given to the paired phone over encrypted BLE.
#if __has_include("secrets.h")
  #include "secrets.h"
#endif
#ifndef WIFI_STA_SSID
  #define WIFI_STA_SSID ""
#endif
#ifndef WIFI_STA_PASS
  #define WIFI_STA_PASS ""
#endif
#define STA_TIMEOUT_MS  15000

#define WIFI_AP_SSID    "MapMirror-"

// ---------------------------------------------------------------- BLE
#define BLE_NAME        "MapMirror"

// ---------------------------------------------------------------- Panel
// Common 1.69" / 1.83" ST7789 240x280 modules (rounded corners) use a 20-row
// offset inside the 240x320 controller RAM. If the image is shifted or has a
// garbage strip, try PANEL_OFFSET_Y 0. If colours look inverted, flip PANEL_INVERT.
#define PANEL_OFFSET_Y  20
#define PANEL_INVERT    true
#define PANEL_SPI_HZ    40000000         // try 80 MHz on short wires
#define PANEL_ROTATION  0                // 0 or 2 (upside down) keep 240x280

// ---------------------------------------------------------------- Pins
#if defined(CONFIG_IDF_TARGET_ESP32S3)
  #define PIN_SCLK  12
  #define PIN_MOSI  11
  #define PIN_DC     9
  #define PIN_CS    10
  #define PIN_RST    8
  #define PIN_BL     7                   // -1 if backlight is tied to 3V3
#elif defined(CONFIG_IDF_TARGET_ESP32C3)  // Seeed XIAO ESP32-C3
  #define PIN_SCLK   8                   // D8
  #define PIN_MOSI  10                   // D10
  #define PIN_DC     4                   // D2
  #define PIN_CS     3                   // D1
  #define PIN_RST    5                   // D3
  #define PIN_BL     6                   // D4
#else  // classic ESP32 (VSPI pins)
  #define PIN_SCLK  18
  #define PIN_MOSI  23
  #define PIN_DC    16
  #define PIN_CS     5
  #define PIN_RST   17
  #define PIN_BL     4
#endif
