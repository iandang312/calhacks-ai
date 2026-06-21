# Daisy 🌼

### An accessibility-first phone agent for blind and low-vision users

> Screen readers tell you what's on screen. Daisy actually does the task — by voice, one request at a time.

![Deepgram](https://img.shields.io/badge/Deepgram-STT%2FTTS-blue)
![Claude](https://img.shields.io/badge/Anthropic-Claude-orange)
![Android](https://img.shields.io/badge/Android-Accessibility%20Service-green)
![Python](https://img.shields.io/badge/Python-3.11%2B-blue)
![FastAPI](https://img.shields.io/badge/FastAPI-services-009688)

---

## Inspiration

Screen readers like TalkBack let blind and low-vision (BLV) users hear what's on
screen, but operating an app still means swiping through it one element at a time.
A single everyday task — ordering food, booking a ride, replying to a message,
changing a setting — can take dozens of sequential swipes and taps. It's slow,
fatiguing, and easy to lose track of.

Mainstream voice assistants don't fix this. They answer questions and fire off a
few built-in commands, but they can't actually drive third-party apps. We wanted an
assistant where a BLV user states a goal once, in plain language, and the agent does
the navigating — narrating every step and confirming before anything important — so
the user stays fully in control without needing to see the screen.

---

## What It Does

Daisy is an accessibility-first Android agent that sits between a user's voice and
their phone.

After a wake word, the user says what they want. Daisy interprets the intent,
explains its plan aloud, asks for confirmation, then uses Android automation to carry
out the multi-step task inside a real app — describing what's on screen as it goes and
pausing before any meaningful action.

The core goal isn't just app automation. It's **independence**: letting BLV users
complete multi-step mobile tasks by voice, without grinding through linear
screen-reader navigation, and without ever losing visibility into what the agent is doing.

---

## How We Built It

Daisy combines speech, LLM reasoning, user memory, and Android app control:

- **Deepgram** — speech-to-text and text-to-speech, powering the audio-first interface (the primary channel for our users).
- **Claude** — interprets the user's goal, plans the multi-step task, and reasons over the current screen to decide the next action.
- **Android Accessibility Service** — captures screen state (the same accessibility tree TalkBack uses) and performs taps, swipes, and text entry.
- **Evaluation tooling** — tracks whether the agent completed the task the user actually intended.

---

## Architecture

Rather than one monolithic chatbot, Daisy splits the work across agents with
genuinely distinct jobs — a divide-and-conquer design where each stage does one thing well:

```mermaid
flowchart TD
    Wake["🔔 Wake<br/>wake word + persistent listening cue"] --> Listen
    Listen["🎙️ Listen & Transcribe<br/>Deepgram STT"] --> Planner
    Planner["🧭 Planner (Claude)<br/>reconstruct intent → step-by-step roadmap"] --> Perceiver
    Perceiver["👁️ Perceiver<br/>read screen via Accessibility tree"] --> Verifier
    Verifier["🛡️ Verifier / Guard<br/>narrate, check success, gate risky steps"] --> Done{Task complete?}
    Done -- "no" --> Perceiver
    Done -- "yes" --> Eval["📊 Evaluation<br/>score intent + task success"]
