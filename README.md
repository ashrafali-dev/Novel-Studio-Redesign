# Webtoon Translator — Phase 1

A standalone Android WebView + native OCR app. This repository has been repurposed from the NovelStudio Redesign prototype into Webtoon Translator.

## Phase 1 features

- Open a Webtoon chapter URL in an Android WebView.
- Walk the page from top to bottom to trigger lazy-loaded image panels.
- Collect image URLs and intrinsic dimensions in page order.
- Download images from Android with the page URL as Referer and applicable WebView cookies.
- Run on-device Google ML Kit Text Recognition v2 using selectable Latin, Simplified Chinese, Japanese, or Korean recognition.
- Show recognized lines grouped by image, including normalized image-relative bounding boxes.
- Draw diagnostic bounding boxes over the source images in the WebView.
- Report image/network/OCR failures without silently dropping other images.
- GitHub Actions builds a debug APK.

## Build

Open the project in Android Studio with JDK 17 and Android SDK platform 37 installed. CI uses Gradle 9.3.1 and runs:

    gradle assembleDebug --no-daemon

The generated APK is uploaded as the webtoon-translator-debug workflow artifact.

## Notes and current limits

- Phase 1 is an OCR/bounding-box proof of concept. It does not call a chatbot or replace text inside panels yet.
- Select the source script (Latin, Chinese, Japanese, or Korean) before scanning.
- Images rendered as CSS backgrounds, protected blob URLs, or URLs requiring custom site authentication may not be downloadable by the native image loader. These appear as per-image failures; site adapters are a later improvement.
- Auto-scroll has a safety limit to prevent endless feeds from running forever.
- Four bundled ML Kit script models increase APK size but do not require recognition models to be downloaded on first use.
- The app does not bypass access controls.

## Roadmap

1. Chapter image collection, native OCR, and bounding-box validation. (This phase)
2. Add chatbot translation with stable image/line IDs and response validation.
3. Add translated overlay rendering, text masking, and fit-to-bubble layout.
