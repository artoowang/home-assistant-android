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

### OpenCode Proposal how to upload image to Home Assistant for its component

**Goal**: Send captured images from Glasses to a multimodal LLM (e.g., Ollama with vision model) via Home Assistant.

**HA Endpoint**: `/api/image/upload` (confirmed exists in `homeassistant/components/image_upload/__init__.py`)
- Accepts multipart form data with image file
- Returns JSON with: `id` (image_id), `path` (local filesystem path, added in PR #152093), `content_type`

**Security Concern**: 
- `/api/image/serve/{image_id}/{filename}` is **public** (`requires_auth = False`)
- Anyone with the URL can access images

**Solution**: Use the `path` field from upload response
- The upload returns a local filesystem path like `/config/image/abc123/original`
- Pass this **local path** directly to Ollama/custom vision components
- Avoids the public HTTP serve endpoint entirely
- Works because Ollama runs locally on the same network as HA

**Flow for Glasses App**:
```
1. Capture photo in GlassesActivity.capturePhoto()
   ↓
2. Upload to /api/image/upload via WebSocketRepository
   ↓
3. Get response with "path" field (local filesystem path)
   ↓
4. Pass path to AssistRepository/GlassesViewModel
   ↓
5. Call Ollama vision component with local path:
   service: ollama_vision.analyze_image
   data:
     image_url: "/config/image/abc123/original"  # local path
     prompt: "What do you see?"
   ↓
6. LLM analyzes image and returns text description
   ↓
7. Text appears in GlassesViewModel.conversation
```

**Implementation Steps**:
1. Add `suspend fun uploadImage(data: ByteArray): String?` to `WebSocketRepository`
2. Implement in `WebSocketRepositoryImpl` using existing `okHttpClient` for multipart POST
3. Add `fun sendImage(imageBytes: ByteArray)` to `GlassesViewModel`
4. Update `AssistRepository` to handle image input (call Ollama service via HA API)
5. Integrate with `GlassesActivity.capturePhoto()` to trigger upload + analysis

**Note**: Official Ollama integration doesn't support images yet. Use custom components like `ollama_vision` or wait for official multimodal support (architectere discussion #1085 ongoing).

### Testing with Home Assistant image upload

1. Create a long-live token in HA Web UI, account > security.
2. Run
  ```shell
  curl -X POST \
    -H "Authorization: Bearer <long_live_token>" \
    -F "file=@/Users/artoowang/Programs/home-assistant-android/app/src/main/res/drawable-nodpi/widget_example_camera.jpg" \
    https://ha.cpwang.co/api/image/upload
  ```
3. Got something like `{"id":"<id>","filesize":37017,"content_type":"image/jpeg","name":"widget_example_camera.jpg","uploaded_at":"2026-05-04T04:20:22.188264+00:00"}`
4. Note the uploaded image will be public: I can access it through https://ha.cpwang.co/api/image/serve/{id}/original  
  So the only security is the ID is unknown.
5. It is also listed at https://ha.cpwang.co/media-browser/browser/app%2Cmedia-source%3A%2F%2Fimage_upload
