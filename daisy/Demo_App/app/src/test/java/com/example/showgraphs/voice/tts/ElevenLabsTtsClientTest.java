package com.example.showgraphs.voice.tts;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ElevenLabsTtsClientTest {

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private ElevenLabsTtsClient makeClient() {
        // AudioOutput that ignores bytes and immediately signals completion.
        ElevenLabsTtsClient.AudioOutput noopPlayer = (bytes, onDone) -> onDone.run();
        return new ElevenLabsTtsClient("test_key", "voice_id", noopPlayer, server.url("/").toString());
    }

    // ── Cycle 4: HTTP 200 → onDone ────────────────────────────────────────

    @Test
    public void http200_callsOnDone() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("fake_audio_bytes"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        makeClient().speak("Hello", new TtsCallback() {
            @Override public void onDone() { latch.countDown(); }
            @Override public void onError(String message) { error.set(message); latch.countDown(); }
        });

        latch.await(3, TimeUnit.SECONDS);
        assertNull("onError must not be called on 200", error.get());
    }

    // ── Cycle 5: HTTP non-200 → onError ───────────────────────────────────

    @Test
    public void http401_callsOnError() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(401));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        makeClient().speak("Hello", new TtsCallback() {
            @Override public void onDone() { latch.countDown(); }
            @Override public void onError(String message) { error.set(message); latch.countDown(); }
        });

        latch.await(3, TimeUnit.SECONDS);
        assertNotNull("onError must be called on 401", error.get());
        assertEquals("HTTP 401", error.get());
    }

    @Test
    public void http500_callsOnError() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(500));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        makeClient().speak("Hello", new TtsCallback() {
            @Override public void onDone() { latch.countDown(); }
            @Override public void onError(String message) { error.set(message); latch.countDown(); }
        });

        latch.await(3, TimeUnit.SECONDS);
        assertNotNull("onError must be called on 500", error.get());
        assertEquals("HTTP 500", error.get());
    }
}
