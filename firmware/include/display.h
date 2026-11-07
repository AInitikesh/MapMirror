#pragma once
#define LGFX_USE_V1
#include <LovyanGFX.hpp>
#include "config.h"

// ST7789 240x280 on SPI, configured in code (no User_Setup.h to edit).
class LGFX : public lgfx::LGFX_Device {
  lgfx::Panel_ST7789 _panel;
  lgfx::Bus_SPI _bus;
  lgfx::Light_PWM _light;

 public:
  LGFX() {
    {
      auto cfg = _bus.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 0;
      cfg.freq_write = PANEL_SPI_HZ;
      cfg.freq_read = 16000000;
      cfg.spi_3wire = true;
      cfg.use_lock = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = PIN_SCLK;
      cfg.pin_mosi = PIN_MOSI;
      cfg.pin_miso = -1;
      cfg.pin_dc = PIN_DC;
      _bus.config(cfg);
      _panel.setBus(&_bus);
    }
    {
      auto cfg = _panel.config();
      cfg.pin_cs = PIN_CS;
      cfg.pin_rst = PIN_RST;
      cfg.pin_busy = -1;
      cfg.memory_width = 240;
      cfg.memory_height = 320;
      cfg.panel_width = 240;
      cfg.panel_height = 280;
      cfg.offset_x = 0;
      cfg.offset_y = PANEL_OFFSET_Y;
      cfg.offset_rotation = 0;
      cfg.readable = false;
      cfg.invert = PANEL_INVERT;
      cfg.rgb_order = false;
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      _panel.config(cfg);
    }
    if (PIN_BL >= 0) {
      auto cfg = _light.config();
      cfg.pin_bl = PIN_BL;
      cfg.invert = false;
      cfg.freq = 44100;
      cfg.pwm_channel = 5;               // ESP32-C3 only has channels 0-5
      _light.config(cfg);
      _panel.setLight(&_light);
    }
    setPanel(&_panel);
  }
};
