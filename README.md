# NovelStudio Next

Clean rebuild of NovelStudio around Mozilla GeckoView for the main browser and a separate Android WebView for chatbot providers.

## Included in this foundation
- GeckoView main browser
- URL/search bar and Back/Forward/Reload
- Multiple in-memory tabs
- Separate ChatGPT/Gemini chatbot WebView
- Split-pane toggle and adjustable ratio
- GeckoView WebExtension install/list entry points
- Engine-independent chapter job state and translation cache boundary

## Next milestones
1. Build a WebExtension native-messaging bridge for DOM extraction and page insertion.
2. Port site profiles and extraction rules from the original NovelStudio into adapters.
3. Add provider-specific ChatGPT/Gemini/Claude/DeepSeek/Grok input and response handlers.
4. Automate site-specific Next Chapter navigation and SPA route detection.
5. Implement persistent history/bookmarks/downloads, private mode, permissions, and full extension controls.
6. Run CI and device regression tests.

## Build
Open in Android Studio with JDK 17. The GitHub Actions workflow runs `gradle assembleDebug`.
GeckoView is resolved from Mozilla's Maven repository. Official docs: https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start

## Status
This commit is the clean architectural foundation. It does not claim that every legacy feature has already been ported: the extraction bridge, auto-translation, site-specific Next Chapter actions, and full browser management remain to be implemented.
