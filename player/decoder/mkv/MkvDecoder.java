package coloryr.allmusic_client.player.decoder.mkv;

import coloryr.allmusic_client.player.APlayer;
import coloryr.allmusic_client.player.decoder.BuffPack;
import coloryr.allmusic_client.player.decoder.IDecoder;
import org.jcodec.common.AudioCodecMeta;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.containers.mkv.demuxer.MKVDemuxer;
import org.jcodec.containers.mkv.demuxer.MKVDemuxer.AudioTrack;
import ws.schild.jave.Encoder;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.process.ProcessLocator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

public class MkvDecoder implements IDecoder {
    private final APlayer player;

    private ByteBuffer pcmBuffer;

    private int outputFrequency;
    private int outputChannels;

    public MkvDecoder(APlayer player) {
        this.player = player;
    }

    @Override
    public BuffPack decodeFrame() {
        if (pcmBuffer == null) return null;

        BuffPack buffPack = new BuffPack();
        byte[] buff = new byte[4608];
        try {
            pcmBuffer.get(buff);
        } catch (BufferUnderflowException e) {
            pcmBuffer = null;
            return null;
        }

        buffPack.buff = buff;
        buffPack.len = buff.length;
        return buffPack;
    }

    @Override
    public void close() throws Exception {
        pcmBuffer = null;
    }

    @Override
    public boolean set() throws IOException {
        File source = Files.createTempFile("allmusic-", ".mkv").toFile();
        try {
            byte[] mkvBytes = player.content.readAllBytes();

            try (SeekableByteChannel channel = new ByteBufferSeekableByteChannel(
                    ByteBuffer.wrap(mkvBytes), mkvBytes.length)) {
                MKVDemuxer demuxer;
                demuxer = new MKVDemuxer(channel);

                List<DemuxerTrack> audioTracks = demuxer.getTracks().stream().filter(Objects::nonNull).collect(Collectors.toList());
                if (audioTracks.isEmpty()) {
                    return false;
                }

                AudioTrack audioTrack = (AudioTrack) audioTracks.get(0);
                AudioCodecMeta audioCodecMeta = audioTrack.getMeta().getAudioCodecMeta();
                outputFrequency = audioCodecMeta.getSampleRate();
                outputChannels = audioCodecMeta.getChannelCount();
                if (outputChannels != 1 && outputChannels != 2) return false;
            }

            try (FileOutputStream mkvOutput = new FileOutputStream(source)) {
                mkvOutput.write(mkvBytes);
            }

            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("pcm_s16le");
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("s16le");
            attrs.setAudioAttributes(audio);
            Encoder encoder = new Encoder(new ProcessLocator() {
                ProcessLocator defaultFFMPEGLocator;

                @Override
                public String getExecutablePath() {
                    if (SystemUtils.IS_OS_UNIX) {
                        return "ffmpeg";
                    } else {
                        if (defaultFFMPEGLocator == null) {
                            defaultFFMPEGLocator = new DefaultFFMPEGLocator();
                        }
                        return defaultFFMPEGLocator.getExecutablePath();
                    }
                }
            });

            File target = Files.createTempFile("allmusic-", ".pcm").toFile();
            try {
                try {
                    encoder.encode(new MultimediaObject(source), target, attrs);
                } catch (EncoderException e) {
                    e.printStackTrace();
                    return false;
                }

                try (FileInputStream pcmInput = new FileInputStream(target)) {
                    pcmBuffer = ByteBuffer.allocate((int) target.length()).order(ByteOrder.LITTLE_ENDIAN).put(pcmInput.readAllBytes());
                    pcmBuffer.flip();
                }
            } finally {
                target.delete();
            }
        } finally {
            source.delete();
        }
        return true;
    }

    @Override
    public int getOutputFrequency() {
        return outputFrequency;
    }

    @Override
    public int getOutputChannels() {
        return outputChannels;
    }

    @Override
    public void set(int time) {
        throw new UnsupportedOperationException();
    }
}
