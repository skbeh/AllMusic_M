package coloryr.allmusic_client.player.decoder.ogg;

import coloryr.allmusic_client.AllMusic;
import coloryr.allmusic_client.player.APlayer;
import coloryr.allmusic_client.player.decoder.BuffPack;
import coloryr.allmusic_client.player.decoder.IDecoder;
import org.gagravarr.ogg.OggPacketReader;
import org.gagravarr.opus.OpusAudioData;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusInfo;
import org.gagravarr.vorbis.VorbisAudioData;
import org.gagravarr.vorbis.VorbisFile;
import org.gagravarr.vorbis.VorbisInfo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decode an OGG file to PCM data. This class is based on the example
 * code that accompanies the Java OGG libraries (hence the lack of detailed)
 * explanation.
 *
 * @author Kevin Glass
 */
public class OggDecoder implements IDecoder {
    private final APlayer player;

    private final Semaphore decodeSemaphore = new Semaphore(0);
    private CountDownLatch parseLatch;
    private boolean isDone = false;
    private boolean isClose;
    private boolean isParseOK;
    /**
     * The buffer used to read decoded OGG stream
     */
    private byte[] convbuffer;
    private OggData ogg;

    /**
     * Create a new OGG decoder
     */
    public OggDecoder(APlayer player) {
        this.player = player;
    }

    /**
     * Get the data out of an OGG file
     *
     * @param input The input stream from which to read the OGG file
     */
    private void getData(InputStream input) throws IOException, InterruptedException {
        OpusFile opus = null;
        VorbisFile vorbis = null;
        BufferedInputStream source = new BufferedInputStream(input);
        source.mark(4096);
        try {
            opus = new OpusFile(new OggPacketReader(source));
            OpusInfo opusInfo = opus.getInfo();
            OggData ogg = new OggData();
            ogg.rate = opusInfo.getSampleRate();
            ogg.channels = opusInfo.getNumChannels();
            this.ogg = ogg;
            isParseOK = true;
        } catch (IOException ignored) {
            try {
                vorbis = new VorbisFile(new OggPacketReader(source));
                VorbisInfo opusInfo = vorbis.getInfo();
                OggData ogg = new OggData();
                ogg.rate = opusInfo.getSampleRate();
                ogg.channels = opusInfo.getNumChannels();
                this.ogg = ogg;
                isParseOK = true;
            } catch (IOException e2) {
                isParseOK = false;
            }
        } finally {
            parseLatch.countDown();
        }

        if (!isParseOK) {
            if (opus != null) opus.close();
            if (vorbis != null) vorbis.close();
            return;
        }

        while (true) {
            decodeSemaphore.acquire();
            if (isClose) return;
            if (opus != null) {
                OpusAudioData audioData = opus.getNextAudioPacket();
                if (audioData == null) {
                    isDone = true;
                    opus.close();
                    return;
                }
                convbuffer = audioData.getData();
            } else if (vorbis != null) {
                VorbisAudioData audioData = vorbis.getNextAudioPacket();
                if (audioData == null) {
                    isDone = true;
                    vorbis.close();
                    return;
                }
                convbuffer = audioData.getData();
            } else {
                break;
            }
        }
    }

    @Override
    public BuffPack decodeFrame() {
        if (isDone || isClose)
            return null;

        BuffPack buff = new BuffPack();
        buff.buff = convbuffer;
        buff.len = convbuffer.length;

        decodeSemaphore.release();
        return buff;
    }

    @Override
    public void close() throws Exception {
        isClose = true;
        decodeSemaphore.release();
        player.close();
    }

    @Override
    public boolean set() throws Exception {
        parseLatch = new CountDownLatch(1);
        new Thread(() -> {
            try {
                if (player.content != null) {
                    getData(player);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                isParseOK = false;
            }
        }, "allmusic_ogg").start();
        if (!parseLatch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("parseLatch timeout");
        }
        parseLatch = null;
        return isParseOK;
    }

    @Override
    public int getOutputFrequency() {
        return ogg.rate;
    }

    @Override
    public int getOutputChannels() {
        return ogg.channels;
    }

    @Override
    public void set(int time) {
        AllMusic.sendMessage("[AllMusic客户端]不支持中间播放");
    }
}
