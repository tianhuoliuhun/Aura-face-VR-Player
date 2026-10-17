# Audio Gender Filter Design Document

**Date:** 2026-10-16  
**Status:** 🚧 **未完成 / Work in progress**（本文是设计提案；离线分析面板与单段处理已跑通，
长片流式处理与效果验收仍未完成，暂不作为正式功能）  
**Audience:** Audio Processing Team

## Objective

Add an audio processing feature to Aura-face-VR-Player that removes male voices while preserving female voices and ambient sounds, optimized for Japanese language and VR real-time playback constraints.

## Problem Statement

- VR videos often contain mixed audio: male voice + female voice + ambient sound
- Current playback includes all audio sources without selective filtering
- User requirement: Remove male dialogue while retaining female dialogue and background
- Constraint: Real-time DSP processing would overwhelm VR rendering pipeline

## Solution Overview

**Offline Preprocessing Pipeline** (one-time batch processing per video):

```
Input Video Audio
    ↓
[1] VAD (Voice Activity Detection)
    ↓ Identify speech segments
[2] Speaker Diarization (Who spoke when?)
    ↓ Time segments + speaker clusters
[3] Gender Classification
    ↓ Label each speaker cluster as male/female
[4] Selective Source Separation
    ↓ Apply vocal removal only to male-labeled segments
[5] Audio Stitching with Crossfade
    ↓
Output Audio Track (cached)
```

## Technical Stack

| Component | Model | Size | Format | Language Support | Japanese |
|-----------|-------|------|--------|------------------|----------|
| **VAD** | Silero-VAD (Qualcomm) | 2.27 MB | ONNX | Multilingual (ML-trained) | Native ✓ |
| **Segmentation** | pyannote/segmentation-3.0 | ~100 MB | ONNX | Tested: EN/CN | Acoustic-domain ✓ |
| **Embeddings** | WeSpeaker: resnet34 | 26 MB | ONNX | Multilingual | Acoustic-domain ✓ |
| **Clustering** | PLDA/VBx (sherpa-onnx built-in) | <5 MB | Numeric arrays | Language-agnostic | Native ✓ |
| **Gender Classifier** | voice-gender-classifier-onnx-q8 | ~15 MB | ONNX (Int8) | VoxCeleb2 training | Good* |
| **Vocal Separation** | Spleeter 2-stem | 30–50 MB | ONNX | Acoustic-domain | Native ✓ |

**Note:** Gender classifier trained on VoxCeleb2 (primarily English speakers); performance on non-English voices like Japanese is anticipated to be good but not extensively tested. Recommend A/B testing with sample Japanese dialogue.

## Integration Points

### 1. Dependency

Already using `sherpa-onnx` for local LLM audio processing → reuse for diarization + source separation.

### 2. Android Audio Path

- Extend `AudioTee`/FFmpeg pipeline with preprocessing stage
- Add JNI bridge to C++ layer
- Cache processed audio locally (by video hash)
- Load cached track on playback

### 3. User Experience

- First load: 30–60s preprocessing (depends on video duration and device)
- Subsequent loads: Instant (cached)
- Playback: Zero additional CPU/GPU cost

## Key Design Decisions

1. **Offline, not real-time** – Avoids VR performance impact
2. **One-time preprocessing** – Caches result for repeat plays
3. **Selective processing** – Only male segments get vocal separation
4. **Acoustic-only** – No ASR/text dependency; works across languages
5. **Quantized models** – Fit within mobile memory budgets

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Gender classifier misclassifies non-English voices | Test with Japanese samples; fallback to UVR for uncertain segments |
| Audio quality degradation from source separation | Apply Spleeter only; fallback to original if confidence < threshold |
| Long preprocessing on first load | Background job; user sees progress; can skip and fall back to original |
| Model size bloat | Quantize all models (Int8/w8a16); ~150 MB total |

## Timeline & Milestones

- **Week 1:** Prototype VAD + Diarization pipeline (test with sample audio)
- **Week 2:** Add Gender Classification + Spleeter integration
- **Week 3:** Android JNI integration + caching
- **Week 4:** Testing (Japanese dialogue, edge cases), optimization

## Success Criteria

✓ Remove male voice from test videos while preserving female voice intelligibility  
✓ Preprocessing completes in <60s on mid-range Android device (8 min video)  
✓ Zero impact on VR playback performance (cached mode)  
✓ Works reliably on Japanese, Chinese, and English dialogue  

---

**Design Document Link:** https://claude.ai/code/artifact/9a4ca019-18d1-4d41-8c5d-4cc30cf26ae1

**References:**
- sherpa-onnx Speaker Diarization: https://huggingface.co/spaces/k2-fsa/speaker-diarization
- voice-gender-classifier-onnx-q8: https://huggingface.co/Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8
- Silero-VAD: https://huggingface.co/qualcomm/Silero-VAD
