package com.firetv.airplay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.firetv.airplay.log.LogBuffer;
import com.github.serezhka.airplay.protocol.mirror.H264SpsDimensions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the AirPlay mirror stream (Annex-B H.264) straight onto a Surface with MediaCodec.
 * The stream sends SPS+PPS whenever the picture size changes (for example when the phone rotates);
 * the decoder is rebuilt then.
 */
final class VideoRenderer {

    interface Listener {
        void onVideoSize(int width, int height);

        void onFirstFrame();
    }

    private static final String TAG = "Video";
    private static final String MIME = "video/avc";

    private final Listener listener;

    private Surface surface;
    private MediaCodec codec;
    private String codecName;
    private byte[] sps;
    private byte[] pps;
    private boolean waitingForKeyFrame = true;
    private volatile boolean outputRunning;
    private Thread outputThread;
    private boolean firstFrameShown;

    long framesIn;
    long framesDropped;
    volatile long framesRendered;

    VideoRenderer(Listener listener) {
        this.listener = listener;
    }

    synchronized void setSurface(Surface newSurface) {
        if (surface == newSurface) {
            return;
        }
        releaseCodec();
        surface = newSurface;
        if (surface != null && sps != null && pps != null) {
            createCodec();
        }
    }

    synchronized void reset() {
        releaseCodec();
        sps = null;
        pps = null;
        firstFrameShown = false;
    }

    synchronized void onData(byte[] annexB) {
        List<int[]> nals = findNals(annexB);
        if (nals.isEmpty()) {
            return;
        }
        int firstType = annexB[nals.get(0)[0]] & 0x1f;
        if (firstType == 7) {
            byte[] newSps = null;
            byte[] newPps = null;
            for (int[] nal : nals) {
                int type = annexB[nal[0]] & 0x1f;
                if (type == 7 && newSps == null) {
                    newSps = Arrays.copyOfRange(annexB, nal[0], nal[1]);
                } else if (type == 8 && newPps == null) {
                    newPps = Arrays.copyOfRange(annexB, nal[0], nal[1]);
                }
            }
            if (newSps != null && newPps != null
                    && (!Arrays.equals(newSps, sps) || !Arrays.equals(newPps, pps) || codec == null)) {
                sps = newSps;
                pps = newPps;
                releaseCodec();
                if (surface != null) {
                    createCodec();
                }
            }
            return;
        }

        framesIn++;
        if (codec == null) {
            framesDropped++;
            return;
        }
        if (waitingForKeyFrame) {
            boolean idr = false;
            for (int[] nal : nals) {
                if ((annexB[nal[0]] & 0x1f) == 5) {
                    idr = true;
                    break;
                }
            }
            if (!idr) {
                framesDropped++;
                return;
            }
            waitingForKeyFrame = false;
        }
        queue(annexB, 0);
    }

    private void queue(byte[] data, int flags) {
        try {
            int index = -1;
            for (int attempt = 0; attempt < 10 && index < 0; attempt++) {
                index = codec.dequeueInputBuffer(20_000);
            }
            if (index < 0) {
                framesDropped++;
                LogBuffer.w(TAG, "Decoder input full, frame dropped");
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < data.length) {
                LogBuffer.w(TAG, "Frame of " + data.length + " bytes does not fit decoder input buffer");
                codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, 0);
                framesDropped++;
                return;
            }
            buffer.clear();
            buffer.put(data);
            codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, flags);
        } catch (Exception e) {
            LogBuffer.e(TAG, "Decoder rejected a frame, restarting decoder", e);
            releaseCodec();
            if (surface != null) {
                createCodec();
            }
        }
    }

    private void createCodec() {
        int width = 1920;
        int height = 1080;
        H264SpsDimensions.Dimensions dims = H264SpsDimensions.parse(sps).orElse(null);
        if (dims != null && dims.width() > 0 && dims.height() > 0) {
            width = dims.width();
            height = dims.height();
        }
        LogBuffer.i(TAG, "Stream " + width + "x" + height + ", starting decoder");
        listener.onVideoSize(width, height);

        for (String name : decoderNames()) {
            MediaCodec candidate = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(2 * 1024 * 1024, width * height));
                if (Build.VERSION.SDK_INT >= 23) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                candidate = MediaCodec.createByCodecName(name);
                candidate.configure(format, surface, null, 0);
                candidate.start();
                codec = candidate;
                codecName = name;
                waitingForKeyFrame = true;
                startOutputThread(candidate);
                LogBuffer.i(TAG, "Using decoder " + name);
                return;
            } catch (Exception e) {
                LogBuffer.e(TAG, "Decoder " + name + " failed to start", e);
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        LogBuffer.e(TAG, "No H.264 decoder could be started", null);
    }

    private static List<String> decoderNames() {
        List<String> hardware = new ArrayList<>();
        List<String> software = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MIME)) {
                    String name = info.getName();
                    String lower = name.toLowerCase();
                    if (lower.startsWith("omx.google.") || lower.startsWith("c2.android.")) {
                        software.add(name);
                    } else {
                        hardware.add(name);
                    }
                }
            }
        }
        List<String> all = new ArrayList<>(hardware);
        all.addAll(software);
        return all;
    }

    private void startOutputThread(MediaCodec target) {
        outputRunning = true;
        outputThread = new Thread(() -> drain(target), "video-output");
        outputThread.start();
    }

    private void drain(MediaCodec target) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (outputRunning) {
            try {
                int index = target.dequeueOutputBuffer(info, 10_000);
                if (index >= 0) {
                    target.releaseOutputBuffer(index, true);
                    framesRendered++;
                    if (!firstFrameShown) {
                        firstFrameShown = true;
                        LogBuffer.i(TAG, "First frame on screen");
                        listener.onFirstFrame();
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = target.getOutputFormat();
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                        w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                    }
                    if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                        h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                    }
                    LogBuffer.i(TAG, "Decoder output " + w + "x" + h);
                    if (w > 0 && h > 0) {
                        listener.onVideoSize(w, h);
                    }
                }
            } catch (Exception e) {
                if (outputRunning) {
                    LogBuffer.e(TAG, "Decoder output error", e);
                }
                return;
            }
        }
    }

    private void releaseCodec() {
        outputRunning = false;
        if (outputThread != null) {
            try {
                outputThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            outputThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            LogBuffer.i(TAG, "Decoder " + codecName + " released");
            codec = null;
        }
        waitingForKeyFrame = true;
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /** @return [start, end) of each NAL payload (after the start code). */
    private static List<int[]> findNals(byte[] data) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int start = -1;
        while (i + 3 <= data.length) {
            int codeLength = 0;
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    codeLength = 3;
                } else if (i + 4 <= data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    codeLength = 4;
                }
            }
            if (codeLength > 0) {
                if (start >= 0 && i > start) {
                    out.add(new int[]{start, i});
                }
                start = i + codeLength;
                i = start;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < data.length) {
            out.add(new int[]{start, data.length});
        }
        return out;
    }
}
