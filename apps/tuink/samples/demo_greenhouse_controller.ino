// Demo: Arduino greenhouse controller with sensor polling and fan/pump control.
#include <Arduino.h>

static const int TEMP_PIN = A0;
static const int SOIL_PIN = A1;
static const int FAN_PIN = 5;
static const int PUMP_PIN = 6;
static const int LED_PIN = 13;

struct Config {
  float tempHigh;
  float tempLow;
  int soilDry;
  int wateringMs;
  unsigned long sensorIntervalMs;
};

struct State {
  float temperature;
  int soil;
  bool fanOn;
  bool pumpOn;
  unsigned long pumpUntil;
  unsigned long lastSensorAt;
};

Config cfg = {29.0f, 25.0f, 420, 2500, 1500};
State st = {0.0f, 0, false, false, 0, 0};

float readTemperatureC() {
  int raw = analogRead(TEMP_PIN);
  float voltage = (raw / 1023.0f) * 5.0f;
  return (voltage - 0.5f) * 100.0f;
}

int readSoil() {
  int raw = analogRead(SOIL_PIN);
  return map(raw, 1023, 180, 0, 1000);
}

void setFan(bool on) {
  st.fanOn = on;
  digitalWrite(FAN_PIN, on ? HIGH : LOW);
}

void setPump(bool on, unsigned long now) {
  st.pumpOn = on;
  digitalWrite(PUMP_PIN, on ? HIGH : LOW);
  if (on) {
    st.pumpUntil = now + cfg.wateringMs;
  } else {
    st.pumpUntil = 0;
  }
}

void printState(unsigned long now) {
  Serial.print("t=");
  Serial.print(now);
  Serial.print(" temp=");
  Serial.print(st.temperature, 1);
  Serial.print("C soil=");
  Serial.print(st.soil);
  Serial.print(" fan=");
  Serial.print(st.fanOn ? "on" : "off");
  Serial.print(" pump=");
  Serial.println(st.pumpOn ? "on" : "off");
}

void controlClimate(unsigned long now) {
  if (st.temperature >= cfg.tempHigh) {
    setFan(true);
  } else if (st.temperature <= cfg.tempLow) {
    setFan(false);
  }

  if (st.soil <= cfg.soilDry && !st.pumpOn) {
    setPump(true, now);
  }

  if (st.pumpOn && now >= st.pumpUntil) {
    setPump(false, now);
  }

  digitalWrite(LED_PIN, st.fanOn || st.pumpOn ? HIGH : LOW);
}

void readSensors(unsigned long now) {
  if (now - st.lastSensorAt < cfg.sensorIntervalMs) {
    return;
  }
  st.lastSensorAt = now;
  st.temperature = readTemperatureC();
  st.soil = readSoil();
}

void processSerial() {
  if (!Serial.available()) {
    return;
  }

  String cmd = Serial.readStringUntil('\n');
  cmd.trim();
  if (cmd.startsWith("tempHigh=")) {
    cfg.tempHigh = cmd.substring(9).toFloat();
  } else if (cmd.startsWith("tempLow=")) {
    cfg.tempLow = cmd.substring(8).toFloat();
  } else if (cmd.startsWith("soilDry=")) {
    cfg.soilDry = cmd.substring(8).toInt();
  } else if (cmd == "status") {
    printState(millis());
  }
}

void setup() {
  pinMode(FAN_PIN, OUTPUT);
  pinMode(PUMP_PIN, OUTPUT);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(FAN_PIN, LOW);
  digitalWrite(PUMP_PIN, LOW);

  Serial.begin(115200);
  while (!Serial) {
    delay(10);
  }
  Serial.println("greenhouse controller ready");
}

void loop() {
  unsigned long now = millis();
  readSensors(now);
  controlClimate(now);
  processSerial();

  static unsigned long lastReport = 0;
  if (now - lastReport > 3000) {
    lastReport = now;
    printState(now);
  }
}
