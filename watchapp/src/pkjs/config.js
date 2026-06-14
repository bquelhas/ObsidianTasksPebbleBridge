module.exports = [
  {
    "type": "heading",
    "defaultValue": "Obsidian Tasks"
  },
  {
    "type": "text",
    "defaultValue": "Choose the watch app background colour. Text colour adapts automatically (black or white) for the best contrast."
  },
  {
    "type": "section",
    "items": [
      {
        "type": "heading",
        "defaultValue": "Appearance"
      },
      {
        "type": "color",
        "messageKey": "BG_COLOR",
        "defaultValue": "0x5500AA",
        "label": "Background colour",
        "sunlight": true,
        "allowGray": true
      }
    ]
  },
  {
    "type": "section",
    "items": [
      {
        "type": "heading",
        "defaultValue": "Voice note"
      },
      {
        "type": "text",
        "defaultValue": "Adds a \u201cNew note\u201d row at the top of the list. Tap it to dictate and the text is saved to your notes on the phone. Requires a Pebble with a microphone and an active voice service (Rebble)."
      },
      {
        "type": "toggle",
        "messageKey": "VOICE_ON",
        "label": "Show \u201cNew note\u201d row",
        "defaultValue": true
      }
    ]
  },
  {
    "type": "section",
    "items": [
      {
        "type": "heading",
        "defaultValue": "Android companion app"
      },
      {
        "type": "text",
        "defaultValue": "Reads your Obsidian tasks and delivers reminders on your phone. Download the latest APK:"
      },
      {
        "type": "text",
        "defaultValue": "<a href='https://github.com/bquelhas/ObsidianTasksPebbleBridge/releases'>github.com/bquelhas/ObsidianTasksPebbleBridge</a><br><a href='https://apps.rebble.io/en_US/application/6a2eb7a169dd300009bf84e4'>Rebble store</a>"
      }
    ]
  },
  {
    "type": "submit",
    "defaultValue": "Save"
  }
];
