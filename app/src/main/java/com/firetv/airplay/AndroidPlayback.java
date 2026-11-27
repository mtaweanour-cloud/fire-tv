package com.firetv.airplay;

import android.view.Surface;

import com.firetv.airplay.log.LogBuffer;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

/** Connects the AirPlay server to the Fire TV's video decoder, audio output and screen. */
final class AndroidPlayback implements Playback {

    interface Ui {
        void onConnecting();

        void onMirroringStarted();

        void onAudioOnly();

        void onVideoSize(int width, int height);

        void onIdle();
    }

    private static final String TAG = "Playback";

    private final VideoRenderer video;
    private final AudioRenderer audio = new AudioRenderer();
    private volatile Ui ui;
    private volatile Observer observer = Observer.NONE;
    private volatile boolean videoActive;
    private volatile boolean audioActive;
    private volatile double volume = 1.0;

    AndroidPlayback() {
        video = new VideoRenderer(new VideoRenderer.Listener() {
            @Override
            public void onVideoSize(int width, int height) {
                Ui u = ui;
                if (u != null) {
                    u.onVideoSize(width, height);
                }
            }

            @Override
            public void onFirstFrame() {
                Ui u = ui;
                if (u != null) {
                    u.onMirroringStarted();
                }
                observer.onPresented();
            }
        });
    }

    void setUi(Ui ui) {
        this.ui = ui;
    }

    void setSurface(Surface surface) {
        video.setSurface(surface);
    }

    String stats() {
        return "video frames in=" + video.framesIn + " rendered=" + video.framesRendered
                + " dropped=" + video.framesDropped
                + " | audio packets in=" + audio.packetsIn + " decoded=" + audio.packetsDecoded
                + " overruns=" + audio.overruns;
    }

    @Override
    public void preparePresentation() {
        LogBuffer.i(TAG, "Phone is connecting");
        Ui u = ui;
        if (u != null) {
            u.onConnecting();
        }
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        LogBuffer.i(TAG, "Screen mirroring requested");
        videoActive = true;
        video.reset();
        Ui u = ui;
        if (u != null) {
            u.onConnecting();
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        video.onData(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        LogBuffer.i(TAG, "Mirroring stopped. " + stats());
        videoActive = false;
        video.reset();
        showIdleOrAudio();
    }

    @Override
    public void onPresentationEnded() {
        LogBuffer.i(TAG, "Phone disconnected. " + stats());
        videoActive = false;
        audioActive = false;
        video.reset();
        audio.release();
        showIdleOrAudio();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioActive = true;
        audio.configure(audioStreamInfo);
        audio.setVolume(volume);
        if (!videoActive) {
            Ui u = ui;
            if (u != null) {
                u.onAudioOnly();
            }
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        audio.onData(bytes, 0, bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        audio.onData(bytes, offset, length);
    }

    @Override
    public void onAudioSrcDisconnect() {
        LogBuffer.i(TAG, "Audio stopped");
        audioActive = false;
        audio.release();
        showIdleOrAudio();
    }

    @Override
    public void onVolume(double volumeLinear) {
        volume = volumeLinear;
        audio.setVolume(volumeLinear);
    }

    @Override
    public double volume() {
        return volume;
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer = observer != null ? observer : Observer.NONE;
    }

    void shutdown() {
        video.reset();
        audio.release();
    }

    private void showIdleOrAudio() {
        Ui u = ui;
        if (u == null) {
            return;
        }
        if (videoActive) {
            return;
        }
        if (audioActive) {
            u.onAudioOnly();
        } else {
            u.onIdle();
        }
    }
}
