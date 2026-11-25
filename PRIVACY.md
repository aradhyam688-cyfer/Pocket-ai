# Privacy & Permissions Notice

PocketAI is designed as an **on-device, local-first** Android assistant.

## Data Storage
- Chat history, user facts (`/remember`), and task state are stored locally on your device using Android Room (`pocketai.db`).
- No telemetry, analytics, or background tracking is included.

## Permissions Used
- **Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`):** Used solely to read visible UI elements (excluding password fields) and perform user-initiated navigation/automation on your device.
- **Microphone (`RECORD_AUDIO`):** Used only when you tap **Mic** for on-device speech recognition.
- **Internet (`INTERNET`):** Used **only** if you explicitly configure a third-party Cloud API key (Gemini, ChatGPT, or Claude) and trigger Cloud Help or `/cloud`. No network requests are made without your API key.
