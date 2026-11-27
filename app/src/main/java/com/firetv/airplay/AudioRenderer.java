package com.firetv.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import com.beatofthedrum.alacdecoder.AlacDecodeUtils;
import com.beatofthedrum.alacdecoder.AlacFile;
import com.firetv.airplay.log.LogBuffer;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays AirPlay audio: AAC-ELD (screen mirroring), AAC-LC and ALAC (music) and raw PCM.
 * Writes to AudioTrack without blocking so network bursts never build up delay; excess audio is
 * dropped instead.
 */
final class AudioRenderer {

    private static final String TAG = "Audio";
    private static final String AAC = "audio/mp4a-latm";

    private AudioStreamInfo.CompressionType type;
    private int sampleRate = 44100;
    private int channels = 2;
    private int bitDepth = 16;

    private MediaCodec codec;
    private AlacFile alac;
    private int[] alacOut;
    private short[] pcm = new short[8192];
    private byte[] pcmBytes = new byte[16384];
    private AudioTrack track;
    private float volume = 1f;

    long packetsIn;
    long packetsDecoded;
    long overruns;

    synchronized void configure(AudioStreamInfo info) {
        release();
        type = info.getCompressionType();
        parseFormat(info);
        LogBuffer.i(TAG, "Audio " + type + " " + sampleRate + " Hz, " + channels + " ch, "
                + info.getSamplesPerFrame() + " samples/frame");
        try {
            switch (type) {
                case AAC_ELD:
                    startAac(39, eldConfig(sampleRate, channels));
                    break;
                case AAC:
                    startAac(2, lcConfig(sampleRate, channels));
                    break;
                case ALAC:
                    startAlac(info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 352);
                    break;
                case LPCM:
                    break;
                default:
                    LogBuffer.w(TAG, "Unsupported audio codec " + type + ", audio will be silent");
                    type = null;
                    return;
            }
            startTrack();
        } catch (Exception e) {
            LogBuffer.e(TAG, "Could not start audio", e);
            release();
        }
    }

    synchronized void onData(byte[] data, int offset, int length) {
        if (type == null || track == null || length <= 0) {
            return;
        }
        packetsIn++;
        try {
            switch (type) {
                case AAC_ELD:
                case AAC:
                    decodeAac(data, offset, length);
                    break;
                case ALAC:
                    decodeAlac(data, offset, length);
                    break;
                case LPCM:
                    writeBigEndianPcm(data, offset, length);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            LogBuffer.e(TAG, "Audio decode error", e);
        }
    }

    synchronized void setVolume(double linear) {
        volume = (float) Math.max(0, Math.min(1, linear));
        if (track != null) {
            track.setVolume(volume);
        }
    }

    synchronized void release() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        alac = null;
        type = null;
    }

    private void parseFormat(AudioStreamInfo info) {
        sampleRate = 44100;
        channels = 2;
        bitDepth = 16;
        AudioStreamInfo.AudioFormat format = info.getAudioFormat();
        if (format == null) {
            return;
        }
        // Enum names look like AAC_ELD_44100_2, ALAC_48000_24_2 or PCM_44100_16_2.
        Matcher m = Pattern.compile("_(\\d{4,5})_(?:(\\d{2})_)?(\\d)$").matcher(format.name());
        if (m.find()) {
            sampleRate = Integer.parseInt(m.group(1));
            if (m.group(2) != null) {
                bitDepth = Integer.parseInt(m.group(2));
            }
            channels = Integer.parseInt(m.group(3));
        }
    }

    // ---------------------------------------------------------------- AAC

    private void startAac(int profile, byte[] config) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile);
        format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        Exception last = null;
        for (String name : aacDecoders()) {
            MediaCodec candidate = null;
            try {
                candidate = MediaCodec.createByCodecName(name);
                candidate.configure(format, null, null, 0);
                candidate.start();
                codec = candidate;
                LogBuffer.i(TAG, "Using audio decoder " + name);
                return;
            } catch (Exception e) {
                last = e;
                LogBuffer.w(TAG, "Audio decoder " + name + " refused the stream: " + e);
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("No AAC decoder");
    }

    /** Software decoders first: they are the ones that reliably handle AAC-ELD. */
    private static List<String> aacDecoders() {
        List<String> software = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(AAC)) {
                    String lower = info.getName().toLowerCase();
                    if (lower.startsWith("c2.android.") || lower.startsWith("omx.google.")) {
                        software.add(info.getName());
                    } else {
                        other.add(info.getName());
                    }
                }
            }
        }
        software.addAll(other);
        return software;
    }

    private void decodeAac(byte[] data, int offset, int length) {
        if (codec == null) {
            return;
        }
        // Strip an ADTS header if the sender wrapped the frame in one.
        if (length > 7 && (data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xF0) == 0xF0) {
            int header = (data[offset + 1] & 0x01) == 0 ? 9 : 7;
            offset += header;
            length -= header;
        }
        int index = codec.dequeueInputBuffer(5_000);
        if (index >= 0) {
            ByteBuffer in = codec.getInputBuffer(index);
            if (in != null && in.capacity() >= length) {
                in.clear();
                in.put(data, offset, length);
                codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0);
            } else {
                codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, 0);
            }
        } else {
            overruns++;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(out);
                if (buffer != null && info.size > 0) {
                    if (pcmBytes.length < info.size) {
                        pcmBytes = new byte[info.size];
                    }
                    buffer.position(info.offset);
                    buffer.get(pcmBytes, 0, info.size);
                    writePcm(pcmBytes, info.size);
                    packetsDecoded++;
                }
                codec.releaseOutputBuffer(out, false);
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (rate != sampleRate || ch != channels) {
                    LogBuffer.i(TAG, "Decoder output " + rate + " Hz, " + ch + " ch");
                    sampleRate = rate;
                    channels = ch;
                    restartTrack();
                }
            } else {
                break;
            }
        }
    }

    /** AudioSpecificConfig for AAC-ELD with 480-sample frames, as sent by iOS when mirroring. */
    static byte[] eldConfig(int rate, int ch) {
        int bits = (31 << 27) | (7 << 21) | (sampleRateIndex(rate) << 17) | (ch << 13) | (1 << 12);
        return new byte[]{(byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits};
    }

    static byte[] lcConfig(int rate, int ch) {
        int bits = (2 << 11) | (sampleRateIndex(rate) << 7) | (ch << 3);
        return new byte[]{(byte) (bits >>> 8), (byte) bits};
    }

    private static int sampleRateIndex(int rate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == rate) {
                return i;
            }
        }
        return 4;
    }

    // ---------------------------------------------------------------- ALAC

    private void startAlac(int frameLength) {
        alac = AlacDecodeUtils.create_alac(bitDepth, channels);
        int[] cookie = new int[48];
        int p = 24; // alac_set_info skips the atom headers
        p = putInt(cookie, p, frameLength);
        cookie[p++] = 0;           // compatible version
        cookie[p++] = bitDepth;    // sample size
        cookie[p++] = 40;          // rice history mult
        cookie[p++] = 10;          // rice initial history
        cookie[p++] = 14;          // rice limit
        cookie[p++] = channels;
        cookie[p++] = 0;           // max run (0x00ff)
        cookie[p++] = 0xff;
        p = putInt(cookie, p, 0);  // max frame bytes
        p = putInt(cookie, p, 0);  // average bit rate
        putInt(cookie, p, sampleRate);
        AlacDecodeUtils.alac_set_info(alac, cookie);
        alacOut = new int[frameLength * channels + 64];
    }

    private static int putInt(int[] dst, int p, int value) {
        dst[p] = (value >>> 24) & 0xff;
        dst[p + 1] = (value >>> 16) & 0xff;
        dst[p + 2] = (value >>> 8) & 0xff;
        dst[p + 3] = value & 0xff;
        return p + 4;
    }

    private void decodeAlac(byte[] data, int offset, int length) {
        if (alac == null) {
            return;
        }
        byte[] frame = new byte[length + 8];
        System.arraycopy(data, offset, frame, 0, length);
        int bytes = AlacDecodeUtils.decode_frame(alac, frame, alacOut, alacOut.length);
        int bytesPerSample = bitDepth / 8;
        int samples = Math.min(bytes / Math.max(1, bytesPerSample), alacOut.length);
        if (samples <= 0) {
            return;
        }
        if (pcm.length < samples) {
            pcm = new short[samples];
        }
        int shift = bitDepth > 16 ? bitDepth - 16 : 0;
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (alacOut[i] >> shift);
        }
        writeShorts(pcm, samples);
        packetsDecoded++;
    }

    // ---------------------------------------------------------------- PCM output

    private void writeBigEndianPcm(byte[] data, int offset, int length) {
        int samples = length / 2;
        if (pcm.length < samples) {
            pcm = new short[samples];
        }
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (((data[offset + 2 * i] & 0xff) << 8) | (data[offset + 2 * i + 1] & 0xff));
        }
        writeShorts(pcm, samples);
        packetsDecoded++;
    }

    private void writePcm(byte[] bytes, int size) {
        int written = track.write(bytes, 0, size, AudioTrack.WRITE_NON_BLOCKING);
        if (written >= 0 && written < size) {
            overruns++;
        }
    }

    private void writeShorts(short[] samples, int count) {
        int written = track.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING);
        if (written >= 0 && written < count) {
            overruns++;
        }
    }

    private void restartTrack() {
        if (track != null) {
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        startTrack();
    }

    private void startTrack() {
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        // About a quarter second of headroom: enough to ride out Wi-Fi jitter, small enough to stay in sync.
        int bufferSize = Math.max(minBuffer * 2, sampleRate * channels * 2 / 4);
        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        track = builder.build();
        track.setVolume(volume);
        track.play();
    }
}
