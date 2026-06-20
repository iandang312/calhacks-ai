# IntentBridge 🎙️

### Voice Intent Engine for People with Speech Disorders

> Siri understands your words. We understand *you* — even when the words don't come out right.

[![Deepgram](https://img.shields.io/badge/Deepgram-STT%2FTTS-blue)]()
[![Claude](https://img.shields.io/badge/Anthropic-Claude%20API-orange)]()
[![Redis](https://img.shields.io/badge/Redis-Agent%20Memory-red)]()
[![Agent S](https://img.shields.io/badge/Simular-Agent%20S-purple)]()

---

## The Problem

Most voice assistants are built for fluent, structured speech.

For many people with speech disorders, dementia, aphasia, stutters, or other communication-related conditions, speech can include repeated sounds, interrupted phrases, incomplete sentences, or unclear wording.

For example:

> "I w-w-wan-t a bur-bur-ger"

A traditional assistant may struggle to understand this reliably.

EchoMind is designed to recover the user's true intent from fragmented or disfluent speech and turn it into a clear, actionable plan.

---

## Our Solution

EchoMind is an accessibility-focused Android AI agent layer that listens after a wake word, reconstructs the user's intended request, explains the plan back to them, and then carries out the action inside a real Android app.

Instead of forcing users to speak like machines, EchoMind adapts to how the user naturally communicates.

---

## How It Works

1. The user says a wake word.
2. EchoMind begins listening.
3. Deepgram transcribes the user's speech.
4. Claude interprets the transcript and reconstructs the user's true intent.
5. EchoMind explains the interpreted intent and planned action back to the user.
6. The user confirms the action.
7. Agent S and the Android Accessibility API execute the task inside a real Android app.

---

## Example

User says:

> "I w-w-wan-t a bur-bur-ger"

EchoMind interprets:

> "The user wants to order a burger."

EchoMind responds:

> "I think you want to order a burger. I will open the app, search for burger options, and ask you to confirm before placing anything."

Then the Android agent begins executing the task.

---

## Architecture

```text
Wake Word
   ↓
Voice Input
   ↓
Deepgram Speech-to-Text
   ↓
Claude Intent Reconstruction
   ↓
Intent + Plan Generation
   ↓
User Confirmation
   ↓
Agent S
   ↓
Android Accessibility API
   ↓
Real Android App Action