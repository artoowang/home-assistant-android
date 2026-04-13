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
