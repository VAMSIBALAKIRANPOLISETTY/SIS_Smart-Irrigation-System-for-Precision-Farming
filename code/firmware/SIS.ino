#include <DHT.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <ESP8266WiFi.h>
#include "ThingSpeak.h"

const char* WIFI_SSID = "XXXXXXXX";
const char* WIFI_PASSWORD = "XXXXXXXX";
const long THINGSPEAK_CHANNEL_ID = 0000000;
const char* THINGSPEAK_WRITE_API_KEY = "XXXXXXXX";
const char* THINGSPEAK_READ_API_KEY = "XXXXXXXX";

#define DHTPIN D4
const int PUMP_RELAY_PIN    = D1;
const int LCD_SDA_PIN       = D2;
const int LCD_SCL_PIN       = D3;
const int RAIN_SENSOR_PIN   = D5;
const int BUZZER_PIN        = D6;
const int FLOAT_SWITCH_PIN  = D7;
const int MANUAL_BUTTON_PIN = D8;
const int MOISTURE_SENSOR_PIN = A0;

const int MOISTURE_DRY_ANALOG = 850;
const int MOISTURE_WET_ANALOG = 350;
const int MOISTURE_DRY_THRESHOLD = 41;
const int MOISTURE_WET_THRESHOLD = 43;

const long LOCAL_UPDATE_INTERVAL = 2000;
const long CLOUD_UPDATE_INTERVAL = 30000;
const long LCD_SCREEN_TOGGLE_INTERVAL = 5000;

DHT dht(DHTPIN, DHT11);
LiquidCrystal_I2C lcd(0x27, 16, 2);
WiFiClient client;

float humidity = 0, temperature = 0, moisturePercentage = 0;
bool isRaining = false, isTankFull = true, pumpState = false;

bool manualOverride = false;
bool lcdFound = false;
bool wifiConnected = false;
String thingSpeakStatus = "Offline";

bool appPumpCommand = false;
bool appModeCommand = false;

bool autoPumpState_last = false; 

unsigned long lastLocalUpdateTime = 0;
unsigned long lastCloudUpdateTime = 0;
unsigned long lastLcdToggleTime = 0;
unsigned long lastButtonPressTime = 0;
int lastButtonState = HIGH;
bool lcdScreenOne = true;

void setup() {
  Serial.begin(115200);
  Serial.println("\n\n--- Smart Irrigation System (Full IoT v4) ---");

  pinMode(PUMP_RELAY_PIN, OUTPUT);
  digitalWrite(PUMP_RELAY_PIN, HIGH);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  Serial.println("Outputs Initialized.");

  pinMode(RAIN_SENSOR_PIN, INPUT);
  pinMode(FLOAT_SWITCH_PIN, INPUT_PULLUP);
  pinMode(MANUAL_BUTTON_PIN, INPUT_PULLUP);
  Serial.println("Inputs Initialized.");

  Serial.print("Connecting to Wi-Fi: ");
  Serial.print(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int wifi_retries = 20;
  while (WiFi.status() != WL_CONNECTED && wifi_retries > 0) {
    delay(500);
    Serial.print(".");
    wifi_retries--;
  }

  if (WiFi.status() == WL_CONNECTED) {
    wifiConnected = true;
    Serial.println("\nWi-Fi Connected! IP: " + WiFi.localIP().toString());
  } else {
    wifiConnected = false;
    Serial.println("\nWi-Fi Connection FAILED.");
  }

  dht.begin();
  Serial.println("DHT Sensor Initialized.");
  ThingSpeak.begin(client);
  Serial.println("ThingSpeak Initialized.");

  Serial.print("Checking for LCD at 0x27... ");
  Wire.begin(LCD_SDA_PIN, LCD_SCL_PIN);
  Wire.beginTransmission(0x27);
  byte error = Wire.endTransmission();

  if (error == 0) {
    lcdFound = true;
    Serial.println("LCD Found!");
    lcd.init();
    lcd.backlight();
    lcd.setCursor(0, 0);
    lcd.print("System Starting");
    lcd.setCursor(0, 1);
    lcd.print("Please Wait...");
  } else {
    lcdFound = false;
    Serial.println("LCD not found. Skipping.");
  }

  Serial.println("--- Setup Complete ---");
  delay(2000);
}

void loop() {
  unsigned long currentMillis = millis();

  handleButtonPress();

  if (currentMillis - lastLocalUpdateTime >= LOCAL_UPDATE_INTERVAL) {
    lastLocalUpdateTime = currentMillis;
    runLocalHardwareLogic();
  }

  if (currentMillis - lastCloudUpdateTime >= CLOUD_UPDATE_INTERVAL) {
    lastCloudUpdateTime = currentMillis;
    if (wifiConnected) {
      runCloudLogic();
    }
  }

  if (lcdFound && (currentMillis - lastLcdToggleTime >= LCD_SCREEN_TOGGLE_INTERVAL)) {
    lastLcdToggleTime = currentMillis;
    lcdScreenOne = !lcdScreenOne;
    updateDisplay();
  }
}

void runLocalHardwareLogic() {
  Serial.println("--------------------");
  Serial.println("Running Local Logic...");

  readSensors();

  bool autoPumpState = runControlLogic();

  handleAppCommands(autoPumpState);

  updateActuators();

  if (lcdFound && lcdScreenOne) {
    updateDisplay();
  }

  printToSerial();
}

void readSensors() {
  humidity = dht.readHumidity();
  temperature = dht.readTemperature();
  if (isnan(humidity) || isnan(temperature)) {
    Serial.println("WARN: Failed to read from DHT sensor!");
    humidity = 0; temperature = 0;
  }

  int rawMoisture = analogRead(MOISTURE_SENSOR_PIN);
  Serial.println("DEBUG: Raw Moisture (A0) = " + String(rawMoisture));
  moisturePercentage = map(rawMoisture, MOISTURE_DRY_ANALOG, MOISTURE_WET_ANALOG, 0, 100);
  moisturePercentage = constrain(moisturePercentage, 0, 100);

  isRaining = (digitalRead(RAIN_SENSOR_PIN) == LOW);
  isTankFull = (digitalRead(FLOAT_SWITCH_PIN) == LOW);

  Serial.println("DEBUG: Sensors Read.");
}

bool runControlLogic() {
  Serial.print("DEBUG: Evaluating auto-logic... ");
   
  bool desiredPumpState = false;

  if (isRaining) {
    Serial.println("Auto: OFF (Raining)");
    desiredPumpState = false;
  } else if (!isTankFull) {
    Serial.println("Auto: OFF (Tank Empty)");
    desiredPumpState = false;
  } else {
    if (moisturePercentage < MOISTURE_DRY_THRESHOLD) {
      desiredPumpState = true;
      Serial.println("Auto: ON (Soil Dry)");
    } else if (moisturePercentage >= MOISTURE_WET_THRESHOLD) {
      desiredPumpState = false;
      Serial.println("Auto: OFF (Soil Wet)");
    } else {
      desiredPumpState = autoPumpState_last; 
      Serial.println("Auto: No Change (Hysteresis)");
    }
  }

  autoPumpState_last = desiredPumpState;
  return desiredPumpState;
}

void handleAppCommands(bool autoPumpState) {
  if (appModeCommand == true) {
    Serial.println("MODE: MANUAL (App Control)");
    pumpState = appPumpCommand;
  } else {
    if (manualOverride) {
      Serial.println("MODE: MANUAL (Local Button)");
    } else {
      Serial.println("MODE: AUTO (Local Logic)");
      pumpState = autoPumpState;
    }
  }
}

void updateActuators() {
  Serial.print("DEBUG: Updating Actuators... ");
  digitalWrite(PUMP_RELAY_PIN, pumpState ? LOW : HIGH);
  Serial.print("Pump set to " + String(pumpState ? "ON (LOW)" : "OFF (HIGH)"));

  bool beep = (!isTankFull || isRaining);
  digitalWrite(BUZZER_PIN, beep ? HIGH : LOW);
  Serial.println(". Buzzer set to " + String(beep ? "ON" : "OFF"));
}

void printToSerial() {
  Serial.println("--- STATUS ---");
  Serial.print("  Moisture: " + String(moisturePercentage, 1) + "%");
  Serial.print(" | Temp: " + String(temperature, 1) + "C");
  Serial.print(" | Humid: " + String(humidity, 1) + "%");
  Serial.println();
  Serial.print("  Rain: " + String(isRaining ? "YES" : "NO"));
  Serial.print(" | Tank: " + String(isTankFull ? "FULL" : "LOW"));
  Serial.print(" | Pump: " + String(pumpState ? "ON" : "OFF"));
   
  if (appModeCommand) {
    Serial.println(" | Mode: MANUAL (App)");
  } else if (manualOverride) {
    Serial.println(" | Mode: MANUAL (Local)");
  } else {
    Serial.println(" | Mode: AUTO");
  }
}

void runCloudLogic() {
  Serial.println("*********************");
  Serial.println("Running Cloud Logic...");
   
  readFromThingSpeak();

  uploadToThingSpeak();
   
  Serial.println("*********************");
}

void uploadToThingSpeak() {
  Serial.print("Uploading data to ThingSpeak... ");

  ThingSpeak.setField(1, moisturePercentage);
  ThingSpeak.setField(2, temperature);
  ThingSpeak.setField(3, humidity);
  ThingSpeak.setField(4, pumpState ? 1 : 0);
  ThingSpeak.setField(5, (appModeCommand || manualOverride) ? 1 : 0);
  ThingSpeak.setField(6, isRaining ? 1 : 0);
  ThingSpeak.setField(7, isTankFull ? 1 : 0);

  int httpCode = ThingSpeak.writeFields(THINGSPEAK_CHANNEL_ID, THINGSPEAK_WRITE_API_KEY);

  if (httpCode == 200) {
    thingSpeakStatus = "Online (Sent)";
    Serial.println("Success!");
  } else {
    thingSpeakStatus = "Error: " + String(httpCode);
    Serial.println("FAILED. (Error code " + String(httpCode) + ")");
  }
}

void readFromThingSpeak() {
  Serial.print("Checking for app commands... ");

  int modeCmd = ThingSpeak.readIntField(THINGSPEAK_CHANNEL_ID, 5, THINGSPEAK_READ_API_KEY);
  int httpCode = ThingSpeak.getLastReadStatus();
   
  if (httpCode == 200) {
    appModeCommand = (modeCmd == 1);
    Serial.print("Mode Command: " + String(appModeCommand ? "MANUAL" : "AUTO"));

    if (appModeCommand) {
      int pumpCmd = ThingSpeak.readIntField(THINGSPEAK_CHANNEL_ID, 4, THINGSPEAK_READ_API_KEY);
      appPumpCommand = (pumpCmd == 1);
      Serial.print(". Pump Command: " + String(appPumpCommand ? "ON" : "OFF"));
    }
    Serial.println();
  } else {
    Serial.println("Check FAILED. (Error code " + String(httpCode) + ")");
  }
}

void handleButtonPress() {
  int reading = digitalRead(MANUAL_BUTTON_PIN);

  if (reading != lastButtonState) {
    lastButtonPressTime = millis();
  }

  if ((millis() - lastButtonPressTime) > 50) {
    if (reading == LOW && lastButtonState == HIGH) {
       
      manualOverride = !manualOverride;

      if (manualOverride) {
        pumpState = !pumpState;
        Serial.println("ACTION: Manual Override ON. Pump Toggled.");
      } else {
        Serial.println("ACTION: Automatic Mode Resumed.");
      }
       
      if (appModeCommand) {
        appModeCommand = false;
      }
    }
  }
  lastButtonState = reading;
}

void updateDisplay() {
  if (!lcdFound) return;

  lcd.clear();
  lcd.setCursor(0, 0);

  if (lcdScreenOne) {
    lcd.print("M:" + String((int)moisturePercentage) + "% ");
    lcd.print(pumpState ? "PMP:ON " : "PMP:OFF");

    lcd.setCursor(0, 1);
    if (!isTankFull) {
      lcd.print("! WATER LOW !");
    } else if (isRaining) {
      lcd.print("! RAINING !");
    } else {
      String tempStr = String(temperature, 0);
      String humStr = String(humidity, 0);   
      lcd.print("T:" + tempStr + "C H:" + humStr + "%");
    }
  } else {
    lcd.print("WiFi: " + String(wifiConnected ? "CONNECTED" : "OFFLINE"));
    
    lcd.setCursor(0, 1);
    lcd.print("Cloud: " + thingSpeakStatus);
  }
}
