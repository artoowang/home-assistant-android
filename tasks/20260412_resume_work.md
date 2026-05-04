## Goal

This task is to resume the work on home-assistant-android after pausing for a while.

## Tasks

### startAssistActivity()

Previously when I testing with camera, I changed what tapping in GlassesActivity does.
I believe I can restore the old behavior by adding startAssistActivity() back.\
- However, it seems killing HA app no longer stops the GlassesActivity?
- Try testing back at commit a8d548d9325cc816f3090374fb95dc8e48a7ebba, before starting
  to test with camera: well, it seems it was already like that back then?
- Anyway, added startAssistActivity() back to tap handler, but commented out.

### Find way to send photos to HA

**Current state:**
- No existing AI/model integration in codebase. App only downloads images from HA.
- No multipart file upload support exists (only JSON REST APIs, WebSocket for audio).

**Image handling today:**
- `capturePhoto()` returns `jpegBytes` (ByteArray)
- `Camera2Utils.saveBytesToFile()` saves to app storage

**Options:**

1. **HA Service call via WebSocket** - Send bytes to HA server, if it supports vision.
2. **Direct webhook/HTTP** - Add multipart upload using existing OkHttpClient.
3. **New API service** - Add Retrofit multipart to `HomeAssistantApis.kt`, create repository.

**Next step** Research into HA and see if extending the existing WebSocket makes sense or not.

### Summary of the current websocket usage

Here's a summary of each class in the Glasses → WebSocket chain:

1. **GlassesActivity** (`glasses` module)
   Entry point for the glasses XR experience. Manages camera/audio permissions, hosts the main tap-to-interact UI, and launches `GlassesAssistActivity` for assist sessions (currently commented out in favor of photo capture testing).

2. **GlassesAssistActivity** (`glasses` module)
   Hosts the active assist session for glasses. Uses `GlassesViewModel` to start assist/recording, monitors assist state to auto-close when the session ends, and renders the voice assist UI.

3. **GlassesViewModel** (`glasses` module)
   ViewModel bridging `GlassesAssistActivity` UI and `AssistRepository`. Exposes assist state, conversation history, mic toggle, and triggers assist session lifecycle methods.

4. **AssistRepository** (`common` module)
   Shared repository managing Home Assistant Assist pipeline logic. Handles pipeline selection, voice/text modes, and delegates binary data (voice) sending to `WebSocketRepository`.

5. **WebSocketRepository** (`common` module, interface + `WebSocketRepositoryImpl`)
   Defines feature-level WebSocket operations (assist, thread datasets). `sendVoiceData()` forwards voice bytes to `WebSocketCore.sendBytes()`; image support would add a similar `sendImageData()` method here.

6. **WebSocketCore** (`common` module, interface + `WebSocketCoreImpl`)
   Core OkHttp WebSocket handler. Manages connection lifecycle (auth, reconnect, shutdown), message queuing, and implements `sendBytes()` to transmit raw `ByteArray` data over the WebSocket.