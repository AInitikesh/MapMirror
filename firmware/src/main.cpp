// MapMirror display: receives 240x280 JPEG frames from the Android app over
// Wi-Fi (TCP) or Bluetooth LE and draws them on an ST7789 panel.
//
// Each display makes a random 32-byte key K on first boot and gives it only to phones
// paired with the passkey. The phone checks K on every connection, so a look-alike
// display can't receive the map, and Wi-Fi frames are authenticated and encrypted.
//
// BLE:    service 4d4d0001-0000-4d61-7020-4d6972726f72, paired with a passkey shown on
//         the panel; the bond is kept in flash. New phones can only pair while the
//         display has no bonds, or in the first PAIR_WINDOW_MS after power-on.
//         char    4d4d0002-...  (write without response)
//         chunk = flags(u8: bit0 START, bit1 END) | frameId(u8) | [u32 LE total, START only] | data
//         char    4d4d0003-...  (read) ssid \n password \n bssid \n ip \n hex(K)
//         The password is empty when the display has joined an existing network.
//         char    4d4d0004-...  (write, then read) key check: the phone writes
//         flags(u8: bit0 show confirm code) | nonce(16) and reads HMAC(K, "MMBP" | nonce).
//         The confirm code is HMAC(K, "MMCF" | nonce), first 4 bytes big-endian, mod 10^6.
// Wi-Fi:  TCP :5005. The display sends "MMS2" | nb(16). The phone answers
//         "MMC2" | np(16) | HMAC(K, "MMTC" | nb | np), the display replies
//         HMAC(K, "MMTS" | nb | np), and the session key is HMAC(K, "MMTK" | nb | np)[0..16].
//         Frame = "MMF2" | u32 LE length | AES-128-GCM(JPEG) | tag(16), with the 8-byte
//         header as associated data and IV = 4 zero bytes | u64 BE frame counter.
//         The display replies 0x06 after drawing.
//         Broadcasts "MAPMIRROR 1" on UDP :5006 every second for discovery.

#include <Arduino.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <NimBLEDevice.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include "config.h"
#include "display.h"

static LGFX lcd;

static constexpr uint16_t TCP_PORT = 5005;
static constexpr uint16_t DISC_PORT = 5006;
static constexpr uint32_t MAX_FRAME = 48 * 1024;  // app caps frames at 45 000 bytes
static constexpr uint32_t PAIR_WINDOW_MS = 120000;  // new phones can pair this long after power-on
static constexpr uint32_t CONFIRM_MS = 120000;      // how long the confirm code stays up
static const char* SVC_UUID = "4d4d0001-0000-4d61-7020-4d6972726f72";
static const char* FRAME_UUID = "4d4d0002-0000-4d61-7020-4d6972726f72";
static const char* WIFI_INFO_UUID = "4d4d0003-0000-4d61-7020-4d6972726f72";
static const char* AUTH_UUID = "4d4d0004-0000-4d61-7020-4d6972726f72";

// ---------------------------------------------------------------- buffers
// Two buffers. BLE (NimBLE host task) assembles into asmBuf and swaps it with
// showBuf when a frame completes. The main loop draws showBuf, and also reads
// Wi-Fi frames straight into showBuf after claiming it (loopOwnsShow).
static uint8_t* asmBuf;
static uint8_t* showBuf;
static volatile uint32_t showLen = 0;
static volatile bool showReady = false;
static volatile bool loopOwnsShow = false;
static portMUX_TYPE bufMux = portMUX_INITIALIZER_UNLOCKED;

static uint32_t asmLen = 0, asmExpected = 0;
static uint8_t asmId = 0;
static bool asmActive = false;
static volatile bool bleConnected = false;
static volatile bool pairingActive = false;
static volatile uint32_t pairingPasskey = 0;
static volatile bool pairingRefused = false;
static volatile bool confirmActive = false;
static volatile uint32_t confirmCode = 0;
static NimBLECharacteristic* wifiInfoChar = nullptr;
static uint8_t devKey[32];

// ---------------------------------------------------------------- state
static WiFiServer server(TCP_PORT);
static WiFiClient client;
static mbedtls_gcm_context gcm;
static bool gcmReady = false;
static uint64_t frameCounter = 0;
static WiFiUDP udp;
static bool apMode = false;
static uint32_t lastFrameMs = 0, lastBeaconMs = 0;
static String idleSig;
static String apSsid, apPass;

static uint8_t* allocFrameBuf() {
  uint8_t* p = nullptr;
#ifdef BOARD_HAS_PSRAM
  if (psramFound()) p = (uint8_t*)ps_malloc(MAX_FRAME);
#endif
  if (!p) p = (uint8_t*)malloc(MAX_FRAME);
  return p;
}

static void drawFrame(const uint8_t* buf, uint32_t len) {
  if (pairingActive) return;  // keep the passkey on screen
  if (lcd.drawJpg(buf, len, 0, 0)) {
    lastFrameMs = millis();
    idleSig = "";
    confirmActive = false;  // the phone only sends frames once the code was confirmed
  }
}

// ---------------------------------------------------------------- crypto
// HMAC-SHA256(K, label | a | b), with a 4-character label.
static void hmac(const char* label, const uint8_t* a, size_t alen, const uint8_t* b, size_t blen,
                 uint8_t out[32]) {
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&ctx, devKey, sizeof(devKey));
  mbedtls_md_hmac_update(&ctx, (const uint8_t*)label, 4);
  if (alen) mbedtls_md_hmac_update(&ctx, a, alen);
  if (blen) mbedtls_md_hmac_update(&ctx, b, blen);
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
}

static bool sameBytes(const uint8_t* a, const uint8_t* b, size_t n) {
  uint8_t d = 0;
  for (size_t i = 0; i < n; i++) d |= a[i] ^ b[i];
  return d == 0;
}

static void loadDeviceKey() {
  Preferences prefs;
  prefs.begin("mapmirror", false);
  if (prefs.getBytes("devkey", devKey, sizeof(devKey)) != sizeof(devKey)) {
    esp_fill_random(devKey, sizeof(devKey));
    prefs.putBytes("devkey", devKey, sizeof(devKey));
  }
  prefs.end();
}

static bool pairingAllowed() {
  return NimBLEDevice::getNumBonds() == 0 || millis() < PAIR_WINDOW_MS;
}

// ---------------------------------------------------------------- BLE
static void publishBleFrame() {
  portENTER_CRITICAL(&bufMux);
  if (!showReady && !loopOwnsShow) {
    uint8_t* t = showBuf;
    showBuf = asmBuf;
    asmBuf = t;
    showLen = asmLen;
    showReady = true;
  }  // else: display busy, drop this frame (the next one will be newer anyway)
  portEXIT_CRITICAL(&bufMux);
}

class FrameCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    if (!info.isEncrypted() || !info.isAuthenticated()) return;
    NimBLEAttValue v = c->getValue();
    const uint8_t* d = v.data();
    size_t n = v.size();
    if (n < 2) return;
    uint8_t flags = d[0], id = d[1];
    size_t off = 2;
    if (flags & 0x01) {
      if (n < 6) return;
      uint32_t total = d[2] | (d[3] << 8) | (d[4] << 16) | ((uint32_t)d[5] << 24);
      off = 6;
      if (total == 0 || total > MAX_FRAME) { asmActive = false; return; }
      asmExpected = total;
      asmLen = 0;
      asmId = id;
      asmActive = true;
    } else if (!asmActive || id != asmId) {
      return;  // missed the start of this frame
    }
    size_t take = n - off;
    if (asmLen + take > asmExpected) { asmActive = false; return; }
    memcpy(asmBuf + asmLen, d + off, take);
    asmLen += take;
    if (flags & 0x02) {
      if (asmLen == asmExpected) publishBleFrame();
      asmActive = false;
    }
  }
};

// The phone proves this is the display it paired with, on every connection.
class AuthCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    if (!info.isEncrypted() || !info.isAuthenticated()) return;
    NimBLEAttValue v = c->getValue();
    if (v.size() != 17) return;
    const uint8_t* d = v.data();
    uint8_t nonce[16];
    memcpy(nonce, d + 1, sizeof(nonce));
    bool showCode = d[0] & 0x01;
    uint8_t proof[32];
    hmac("MMBP", nonce, sizeof(nonce), nullptr, 0, proof);
    c->setValue(proof, sizeof(proof));
    if (showCode) {
      uint8_t m[32];
      hmac("MMCF", nonce, sizeof(nonce), nullptr, 0, m);
      confirmCode = (((uint32_t)m[0] << 24) | ((uint32_t)m[1] << 16) | ((uint32_t)m[2] << 8) | m[3]) % 1000000;
      confirmActive = true;
    }
  }
};

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    bleConnected = true;
    Serial.printf("BLE connected %s\n", info.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& info, int reason) override {
    bleConnected = false;
    asmActive = false;
    pairingActive = false;
    confirmActive = false;
    Serial.printf("BLE disconnected (%d)\n", reason);
    NimBLEDevice::startAdvertising();
  }
  // Runs on the NimBLE host task, so the main loop does the drawing. Outside the
  // pairing window the code is never shown, so pairing can't succeed, and the main
  // loop drops the connection.
  uint32_t onPassKeyDisplay() override {
    pairingPasskey = esp_random() % 1000000;
    if (pairingAllowed()) pairingActive = true;
    else pairingRefused = true;
    return pairingPasskey;
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    pairingActive = false;
    if (!info.isEncrypted() || !info.isAuthenticated()) {
      Serial.println("BLE pairing failed");
      NimBLEDevice::getServer()->disconnect(info.getConnHandle());
    }
  }
};

static void setupBle() {
  NimBLEDevice::init(BLE_NAME);
  NimBLEDevice::setMTU(517);
  // Bond, with passkey protection and LE Secure Connections. Bonds persist in NVS.
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEServer* srv = NimBLEDevice::createServer();
  srv->setCallbacks(new ServerCallbacks());
  NimBLEService* svc = srv->createService(SVC_UUID);
  NimBLECharacteristic* ch = svc->createCharacteristic(
      FRAME_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN,
      512);
  ch->setCallbacks(new FrameCallbacks());
  // Reading this is what makes the phone pair, or encrypt with its existing bond.
  wifiInfoChar = svc->createCharacteristic(
      WIFI_INFO_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN, 200);
  NimBLECharacteristic* auth = svc->createCharacteristic(
      AUTH_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN |
          NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN,
      32);
  auth->setCallbacks(new AuthCallbacks());
  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);          // 128-bit UUID fills most of the adv packet,
  NimBLEAdvertisementData sr;             // so the name goes in the scan response
  sr.setName(BLE_NAME);
  adv->setScanResponseData(sr);
  adv->enableScanResponse(true);
  adv->start();
}

// ---------------------------------------------------------------- Wi-Fi
static void bootMessage(const char* msg) {
  lcd.fillScreen(0x0000);
  lcd.setTextDatum(textdatum_t::middle_center);
  lcd.setTextColor(0xFFFF);
  lcd.setFont(&fonts::Font2);
  lcd.drawString(msg, 120, 140);
}

static void loadApCredentials() {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
  char suffix[5];
  snprintf(suffix, sizeof(suffix), "%02X%02X", mac[4], mac[5]);
  apSsid = String(WIFI_AP_SSID) + suffix;

  Preferences prefs;
  prefs.begin("mapmirror", false);
  apPass = prefs.getString("appass", "");
  if (apPass.isEmpty()) {
    // Without visually similar characters, as it never needs typing anyway.
    static const char kChars[] = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    for (int i = 0; i < 20; i++) apPass += kChars[esp_random() % (sizeof(kChars) - 1)];
    prefs.putString("appass", apPass);
  }
  prefs.end();
}

static void publishWifiInfo() {
  String info = apMode
      ? apSsid + "\n" + apPass + "\n" + WiFi.softAPmacAddress() + "\n" + WiFi.softAPIP().toString()
      : WiFi.SSID() + "\n\n\n" + WiFi.localIP().toString();
  char hex[sizeof(devKey) * 2 + 1];
  for (size_t i = 0; i < sizeof(devKey); i++) snprintf(hex + 2 * i, 3, "%02x", devKey[i]);
  wifiInfoChar->setValue(info + "\n" + hex);
}

static void setupWifi() {
  // Note: no WiFi.setSleep(false) — modem sleep is mandatory while BLE is active.
  if (strlen(WIFI_STA_SSID) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_STA_SSID, WIFI_STA_PASS);
    bootMessage("Joining " WIFI_STA_SSID "...");
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < STA_TIMEOUT_MS) delay(200);
    if (WiFi.status() == WL_CONNECTED) {
      apMode = false;
      Serial.printf("STA connected, IP %s\n", WiFi.localIP().toString().c_str());
      return;
    }
    WiFi.disconnect(true);
  }
  WiFi.mode(WIFI_AP);
  WiFi.softAP(apSsid.c_str(), apPass.c_str());
  apMode = true;
  Serial.printf("AP %s, IP %s\n", apSsid.c_str(), WiFi.softAPIP().toString().c_str());
}

static IPAddress myIp() { return apMode ? WiFi.softAPIP() : WiFi.localIP(); }

static void sendBeacon() {
  if (!apMode && WiFi.status() != WL_CONNECTED) return;
  IPAddress bc = apMode ? IPAddress(192, 168, 4, 255) : WiFi.broadcastIP();
  udp.beginPacket(bc, DISC_PORT);
  udp.print("MAPMIRROR 1");
  udp.endPacket();
}

static bool readExact(WiFiClient& c, uint8_t* dst, size_t n, uint32_t timeoutMs) {
  size_t got = 0;
  uint32_t t0 = millis();
  while (got < n) {
    int avail = c.available();
    if (avail > 0) {
      int r = c.read(dst + got, min((size_t)avail, n - got));
      if (r > 0) { got += r; t0 = millis(); continue; }
    } else if (!c.connected()) {
      return false;
    }
    if (millis() - t0 > timeoutMs) return false;
    delay(1);
  }
  return true;
}

static void drawPendingBle() {
  if (showReady) {
    drawFrame(showBuf, showLen);
    showReady = false;
  }
}

// Both sides prove they hold K, and agree a fresh session key.
static bool handshake(WiFiClient& c) {
  uint8_t hello[20];
  memcpy(hello, "MMS2", 4);
  esp_fill_random(hello + 4, 16);
  const uint8_t* nb = hello + 4;
  if (c.write(hello, sizeof(hello)) != sizeof(hello)) return false;
  uint8_t m[52];
  if (!readExact(c, m, sizeof(m), 1000) || memcmp(m, "MMC2", 4) != 0) return false;
  const uint8_t* np = m + 4;
  uint8_t mac[32];
  hmac("MMTC", nb, 16, np, 16, mac);
  if (!sameBytes(mac, m + 20, 32)) return false;
  hmac("MMTS", nb, 16, np, 16, mac);
  if (c.write(mac, sizeof(mac)) != sizeof(mac)) return false;
  hmac("MMTK", nb, 16, np, 16, mac);
  if (gcmReady) mbedtls_gcm_free(&gcm);
  mbedtls_gcm_init(&gcm);
  gcmReady = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, mac, 128) == 0;
  frameCounter = 0;
  return gcmReady;
}

static void serviceWifiClient() {
  // Take the newest connection that passes the handshake. A phone that left without
  // closing its socket still looks connected here, and would otherwise block it forever.
  // Anyone else on the network can't knock the phone off, as they fail the handshake.
  WiFiClient c = server.accept();
  if (c) {
    c.setNoDelay(true);
    if (handshake(c)) {
      client.stop();
      client = c;
      Serial.printf("TCP client %s\n", client.remoteIP().toString().c_str());
    } else {
      Serial.printf("TCP handshake failed from %s\n", c.remoteIP().toString().c_str());
      c.stop();
    }
  }
  if (!client || !client.connected()) return;
  if (client.available() < 8) return;

  uint8_t h[8];
  if (!readExact(client, h, 8, 1000) || memcmp(h, "MMF2", 4) != 0) { client.stop(); return; }
  uint32_t len = h[4] | (h[5] << 8) | (h[6] << 16) | ((uint32_t)h[7] << 24);
  if (len == 0 || len > MAX_FRAME) { client.stop(); return; }

  // Claim showBuf (draw any pending BLE frame first so it is free).
  for (;;) {
    bool claimed = false;
    portENTER_CRITICAL(&bufMux);
    if (!showReady) { loopOwnsShow = true; claimed = true; }
    portEXIT_CRITICAL(&bufMux);
    if (claimed) break;
    drawPendingBle();
  }
  uint8_t tag[16];
  bool ok = readExact(client, showBuf, len, 2000) && readExact(client, tag, sizeof(tag), 1000);
  if (ok) {
    uint8_t iv[12] = {0};
    for (int i = 0; i < 8; i++) iv[4 + i] = (uint8_t)(frameCounter >> (56 - 8 * i));
    frameCounter++;
    // Decrypts in place. A frame that fails the check is dropped with the connection.
    ok = mbedtls_gcm_auth_decrypt(&gcm, len, iv, sizeof(iv), h, sizeof(h), tag, sizeof(tag),
                                  showBuf, showBuf) == 0;
  }
  if (ok) drawFrame(showBuf, len);
  portENTER_CRITICAL(&bufMux);
  loopOwnsShow = false;
  portEXIT_CRITICAL(&bufMux);

  if (ok) client.write((uint8_t)0x06);
  else client.stop();
}

// ---------------------------------------------------------------- pairing screen
static void drawPairingIfNeeded() {
  static bool shown = false;
  bool active = pairingActive;
  if (active && !shown) {
    char code[7];
    snprintf(code, sizeof(code), "%06lu", (unsigned long)pairingPasskey);
    lcd.fillScreen(0x0000);
    lcd.setTextDatum(textdatum_t::middle_center);
    lcd.setTextColor(0x07FF);
    lcd.setFont(&fonts::Font4);
    lcd.drawString("Pairing code", 120, 80);
    lcd.setTextColor(0xFFFF);
    lcd.setFont(&fonts::Font7);
    lcd.drawString(code, 120, 140);
    lcd.setFont(&fonts::Font2);
    lcd.setTextColor(0xFFE0);
    lcd.drawString("Enter this on your phone", 120, 200);
    shown = true;
  } else if (!active && shown) {
    shown = false;
    lcd.fillScreen(0x0000);
    lastFrameMs = 0;  // show the info screen until the next frame
    idleSig = "";
  }
}

// ---------------------------------------------------------------- confirm screen
// Shown when a phone sees this display's key for the first time. The phone shows
// the same code, and the person confirms they match.
static void drawConfirmIfNeeded() {
  static bool shown = false;
  static uint32_t since = 0;
  bool active = confirmActive && !pairingActive;
  if (active && shown && millis() - since > CONFIRM_MS) {
    confirmActive = false;
    active = false;
  }
  if (active && !shown) {
    char code[8];
    snprintf(code, sizeof(code), "%03lu %03lu", (unsigned long)(confirmCode / 1000),
             (unsigned long)(confirmCode % 1000));
    lcd.fillScreen(0x0000);
    lcd.setTextDatum(textdatum_t::middle_center);
    lcd.setTextColor(0x07FF);
    lcd.setFont(&fonts::Font4);
    lcd.drawString("Check code", 120, 80);
    lcd.setTextColor(0xFFFF);
    lcd.drawString(code, 120, 140);
    lcd.setFont(&fonts::Font2);
    lcd.setTextColor(0xFFE0);
    lcd.drawString("Does your phone show", 120, 195);
    lcd.drawString("the same code?", 120, 215);
    shown = true;
    since = millis();
  } else if (!active && shown) {
    shown = false;
    // A frame clears it by drawing over it. Otherwise go back to the info screen.
    if (millis() - lastFrameMs > 100) {
      lcd.fillScreen(0x0000);
      lastFrameMs = 0;
      idleSig = "";
    }
  }
}

// Drops a phone that tried to pair outside the pairing window.
static void dropRefusedPairing() {
  if (!pairingRefused) return;
  pairingRefused = false;
  NimBLEServer* srv = NimBLEDevice::getServer();
  for (uint16_t h : srv->getPeerDevices()) {
    if (!srv->getPeerInfoByHandle(h).isEncrypted()) srv->disconnect(h);
  }
  Serial.println("BLE pairing refused: outside the pairing window");
}

// ---------------------------------------------------------------- idle screen
static void drawIdleIfNeeded() {
  if (pairingActive || confirmActive) return;
  // The phone sends nothing while the map is static, so keep the last frame up
  // as long as a link exists; fall back to the info screen 5 s after it drops.
  bool linked = (client && client.connected()) || bleConnected;
  if (lastFrameMs != 0 && (linked || millis() - lastFrameMs < 5000)) return;

  String wifiLine = apMode ? String("AP: ") + apSsid : String("Wi-Fi: ") + WiFi.SSID();
  String ipLine = String("IP: ") + myIp().toString();
  String bleLine = String("BLE: " BLE_NAME) + (bleConnected ? " (connected)" : " (ready)");
  String tcpLine = (client && client.connected()) ? "Phone connected" : "Waiting for phone";
  String pairLine = NimBLEDevice::getNumBonds() == 0 ? String("Ready to pair")
                  : millis() < PAIR_WINDOW_MS
                      ? String("New phones can pair: ") + String((PAIR_WINDOW_MS - millis()) / 1000) + " s"
                      : String("Pairing closed. Restart to pair");
  String sig = wifiLine + ipLine + bleLine + tcpLine + pairLine;
  if (sig == idleSig) return;  // only redraw when something changed
  idleSig = sig;

  lcd.fillScreen(0x0000);
  lcd.setTextDatum(textdatum_t::middle_center);
  lcd.setTextColor(0x07FF);
  lcd.setFont(&fonts::Font4);
  lcd.drawString("MapMirror", 120, 70);
  lcd.setFont(&fonts::Font2);
  lcd.setTextColor(0xFFFF);
  lcd.drawString(wifiLine, 120, 120);
  lcd.drawString(ipLine, 120, 145);
  lcd.drawString(bleLine, 120, 170);
  lcd.drawString(pairLine, 120, 195);
  lcd.setTextColor(0xFFE0);
  lcd.drawString(tcpLine, 120, 230);
}

// ---------------------------------------------------------------- main
void setup() {
  Serial.begin(115200);
  lcd.init();
  lcd.setRotation(PANEL_ROTATION);
  lcd.setBrightness(220);
  bootMessage("Starting...");

  asmBuf = allocFrameBuf();
  showBuf = allocFrameBuf();
  if (!asmBuf || !showBuf) {
    bootMessage("Out of memory");
    for (;;) delay(1000);
  }

  setupBle();
  // After setupBle(): esp_random() needs a radio on to be truly random.
  loadApCredentials();
  loadDeviceKey();
  setupWifi();
  publishWifiInfo();
  server.begin();
  server.setNoDelay(true);
  Serial.printf("Free heap %u, PSRAM %u\n", ESP.getFreeHeap(), ESP.getFreePsram());
}

void loop() {
  uint32_t now = millis();
  if (now - lastBeaconMs >= 1000) {
    lastBeaconMs = now;
    sendBeacon();
  }
  serviceWifiClient();
  drawPendingBle();
  dropRefusedPairing();
  drawPairingIfNeeded();
  drawConfirmIfNeeded();
  drawIdleIfNeeded();
  delay(1);
}
