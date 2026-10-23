package dev.ad.tv;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Base64;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The platform MediaPlayer cannot play common IP cameras: it crashes on RTP-Info URLs it does not
// recognise and misreads HEVC parameter sets, so RTSP over TCP is handled here and fed to MediaCodec.
final class Rtsp {
    private static final long KEEPALIVE_MS = 20_000;

    private final String rawUrl;
    private final Socket socket = new Socket();
    private volatile boolean closed;
    private PushbackInputStream pushback;
    private DataInputStream in;
    private OutputStream out;
    private String url, user, pass, session, realm, nonce;
    private int cseq;
    private boolean hevc, configSeen;
    private final ByteArrayOutputStream au = new ByteArrayOutputStream();
    private MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private BiConsumer<Integer, Integer> onSize;
    private int snapshotWidth;
    private Bitmap snapshot;

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    Rtsp(String url) {
        rawUrl = url;
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    static Bitmap snapshot(String url, int width) throws IOException {
        Rtsp r = new Rtsp(url);
        r.snapshotWidth = width;
        r.play(null, null);
        return r.snapshot;
    }

    void play(Surface surface, BiConsumer<Integer, Integer> onSize) throws IOException {
        this.onSize = onSize;
        parseUrl();
        Uri u = Uri.parse(url);
        socket.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 554), 5000);
        socket.setSoTimeout(10_000);
        pushback = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()));
        in = new DataInputStream(pushback);
        out = socket.getOutputStream();
        try {
            Response describe = request("DESCRIBE", url, "Accept: application/sdp\r\n");
            String base = describe.headers.getOrDefault("content-base", url);
            String track = setupTrack(describe.body, base);
            Response setup = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            String s = setup.headers.get("session");
            if (s == null) throw new IOException("SETUP: no session");
            session = s.split(";")[0].trim();
            String mime = hevc ? "video/hevc" : "video/avc";
            codec = MediaCodec.createDecoderByType(mime);
            MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
            if (surface == null) format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec.configure(format, surface, null, 0);
            codec.start();
            request("PLAY", base, "Range: npt=0.000-\r\n");
            receive(base);
        } finally {
            if (codec != null) codec.release();
            socket.close();
        }
    }

    // userinfo is split at the last '@' so a raw '@' in the password works as well as %40.
    private void parseUrl() throws IOException {
        if (!rawUrl.regionMatches(true, 0, "rtsp://", 0, 7)) throw new IOException("not rtsp://");
        int slash = rawUrl.indexOf('/', 7);
        String authority = slash < 0 ? rawUrl.substring(7) : rawUrl.substring(7, slash);
        String path = slash < 0 ? "/" : rawUrl.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String info = authority.substring(0, at);
            int colon = info.indexOf(':');
            user = Uri.decode(colon < 0 ? info : info.substring(0, colon));
            pass = colon < 0 ? "" : Uri.decode(info.substring(colon + 1));
            authority = authority.substring(at + 1);
        }
        url = "rtsp://" + authority + path;
    }

    private String setupTrack(String sdp, String base) throws IOException {
        String encoding = null, control = null;
        StringBuilder sprop = new StringBuilder();
        boolean video = false;
        for (String line : sdp.split("\r?\n")) {
            if (line.startsWith("m=")) video = line.startsWith("m=video");
            else if (video && encoding == null && line.startsWith("a=rtpmap:")) {
                encoding = line.substring(line.indexOf(' ') + 1).split("/")[0].trim().toUpperCase();
            } else if (video && control == null && line.startsWith("a=control:")) {
                control = line.substring(10).trim();
            } else if (video && line.startsWith("a=fmtp:")) {
                for (String param : line.substring(line.indexOf(' ') + 1).split(";")) {
                    String[] kv = param.trim().split("=", 2);
                    if (kv.length == 2 && kv[0].matches("sprop-(parameter-sets|vps|sps|pps)")) sprop.append(kv[1]).append(',');
                }
            }
        }
        if (!"H264".equals(encoding) && !"H265".equals(encoding)) throw new IOException("unsupported video: " + encoding);
        hevc = encoding.equals("H265");
        // Some cameras send parameter sets only here, never in-band.
        for (String set : sprop.toString().split(",")) {
            if (set.isEmpty()) continue;
            byte[] nal = Base64.decode(set, Base64.DEFAULT);
            if (nal.length > 0) nal(nal, 0, nal.length);
        }
        if (control == null || control.equals("*")) return base;
        if (control.regionMatches(true, 0, "rtsp://", 0, 7)) return control;
        return base + (base.endsWith("/") ? "" : "/") + control;
    }

    private Response request(String method, String target, String headers) throws IOException {
        send(method, target, headers);
        Response r = readResponse();
        if (r.status == 401 && user != null) {
            String auth = r.headers.getOrDefault("www-authenticate", "");
            realm = param(auth, "realm");
            nonce = auth.startsWith("Digest") ? param(auth, "nonce") : null;
            send(method, target, headers);
            r = readResponse();
        }
        if (r.status != 200) throw new IOException(method + ": " + r.status);
        return r;
    }

    private void send(String method, String target, String headers) throws IOException {
        StringBuilder sb = new StringBuilder()
            .append(method).append(' ').append(target).append(" RTSP/1.0\r\n")
            .append("CSeq: ").append(++cseq).append("\r\n");
        if (session != null) sb.append("Session: ").append(session).append("\r\n");
        if (nonce != null) {
            String ha1 = md5(user + ":" + realm + ":" + pass);
            String ha2 = md5(method + ":" + target);
            // shortcut: no qop support, cameras seen so far send plain Digest; add qop=auth if one asks for it.
            sb.append("Authorization: Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(target)
                .append("\", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append("\"\r\n");
        } else if (realm != null) {
            String token = Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            sb.append("Authorization: Basic ").append(token).append("\r\n");
        }
        sb.append(headers).append("\r\n");
        synchronized (this) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private Response readResponse() throws IOException {
        Response r = new Response();
        String status = readLine();
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) throw new IOException("bad response: " + status);
        r.status = Integer.parseInt(parts[1]);
        for (String line = readLine(); !line.isEmpty(); line = readLine()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (!r.headers.containsKey(key) || key.equals("www-authenticate") && value.startsWith("Digest")) r.headers.put(key, value);
        }
        int length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        byte[] body = new byte[length];
        in.readFully(body);
        r.body = new String(body, StandardCharsets.UTF_8);
        return r;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) throw new IOException("connection closed");
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    private void receive(String base) throws IOException {
        byte[] buf = new byte[65536];
        long ping = System.currentTimeMillis() + KEEPALIVE_MS;
        long giveUp = System.currentTimeMillis() + 15_000;
        while (snapshot == null) {
            if (snapshotWidth > 0 && System.currentTimeMillis() > giveUp) throw new IOException("no frame");
            if (System.currentTimeMillis() > ping) {
                send("GET_PARAMETER", base, "");
                ping = System.currentTimeMillis() + KEEPALIVE_MS;
            }
            int b = in.read();
            if (b < 0) throw new IOException("connection closed");
            if (b != '$') {
                pushback.unread(b);
                readResponse();
                continue;
            }
            int channel = in.readUnsignedByte();
            int len = in.readUnsignedShort();
            in.readFully(buf, 0, len);
            if (channel == 0 && len > 12) rtp(buf, len);
        }
    }

    private void rtp(byte[] p, int len) {
        int off = 12 + (p[0] & 0x0f) * 4;
        if ((p[0] & 0x10) != 0 && off + 4 <= len) off += 4 + (((p[off + 2] & 0xff) << 8) | (p[off + 3] & 0xff)) * 4;
        int end = (p[0] & 0x20) != 0 ? len - (p[len - 1] & 0xff) : len;
        if (off < end) {
            if (hevc) h265(p, off, end);
            else h264(p, off, end);
        }
        if ((p[1] & 0x80) != 0) frame();
    }

    private void h264(byte[] p, int off, int end) {
        int type = p[off] & 0x1f;
        if (type == 24) {
            aggregate(p, off + 1, end);
        } else if (type == 28 && off + 2 <= end) {
            if ((p[off + 1] & 0x80) != 0) {
                nalStart(p[off + 1] & 0x1f);
                au.write((p[off] & 0xe0) | (p[off + 1] & 0x1f));
            }
            au.write(p, off + 2, end - off - 2);
        } else {
            nal(p, off, end - off);
        }
    }

    private void h265(byte[] p, int off, int end) {
        int type = (p[off] >> 1) & 0x3f;
        if (type == 48) {
            aggregate(p, off + 2, end);
        } else if (type == 49 && off + 3 <= end) {
            int fu = p[off + 2];
            if ((fu & 0x80) != 0) {
                nalStart(fu & 0x3f);
                au.write((p[off] & 0x81) | ((fu & 0x3f) << 1));
                au.write(p[off + 1]);
            }
            au.write(p, off + 3, end - off - 3);
        } else {
            nal(p, off, end - off);
        }
    }

    private void aggregate(byte[] p, int off, int end) {
        while (off + 2 <= end) {
            int n = ((p[off] & 0xff) << 8) | (p[off + 1] & 0xff);
            off += 2;
            if (n == 0 || off + n > end) return;
            nal(p, off, n);
            off += n;
        }
    }

    private void nal(byte[] p, int off, int len) {
        nalStart(hevc ? (p[off] >> 1) & 0x3f : p[off] & 0x1f);
        au.write(p, off, len);
    }

    private void nalStart(int type) {
        if (hevc ? type >= 32 && type <= 34 : type == 7 || type == 8) configSeen = true;
        au.write(0);
        au.write(0);
        au.write(0);
        au.write(1);
    }

    private void frame() {
        if (configSeen && au.size() > 0) {
            int i = codec.dequeueInputBuffer(10_000);
            if (i >= 0) {
                byte[] data = au.toByteArray();
                codec.getInputBuffer(i).put(data);
                codec.queueInputBuffer(i, 0, data.length, System.nanoTime() / 1000, 0);
            }
        }
        au.reset();
        for (int o; (o = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER; ) {
            if (o >= 0) {
                if (snapshotWidth > 0) {
                    try (Image image = codec.getOutputImage(o)) {
                        if (image == null) throw new IllegalStateException("decoder gives no image");
                        snapshot = toBitmap(image, snapshotWidth);
                    }
                }
                codec.releaseOutputBuffer(o, snapshotWidth == 0);
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && onSize != null) {
                MediaFormat f = codec.getOutputFormat();
                int w = f.containsKey("crop-right") ? f.getInteger("crop-right") - f.getInteger("crop-left") + 1 : f.getInteger(MediaFormat.KEY_WIDTH);
                int h = f.containsKey("crop-bottom") ? f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 : f.getInteger(MediaFormat.KEY_HEIGHT);
                onSize.accept(w, h);
            }
        }
    }

    private static Bitmap toBitmap(Image image, int outW) {
        Rect crop = image.getCropRect();
        int outH = outW * crop.height() / crop.width();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
        int[] pixels = new int[outW * outH];
        for (int j = 0; j < outH; j++) {
            int sy = crop.top + j * crop.height() / outH;
            for (int i = 0; i < outW; i++) {
                int sx = crop.left + i * crop.width() / outW;
                float l = 1.164f * ((y.get(sy * planes[0].getRowStride() + sx * planes[0].getPixelStride()) & 0xff) - 16);
                int cu = (u.get(sy / 2 * planes[1].getRowStride() + sx / 2 * planes[1].getPixelStride()) & 0xff) - 128;
                int cv = (v.get(sy / 2 * planes[2].getRowStride() + sx / 2 * planes[2].getPixelStride()) & 0xff) - 128;
                int r = clamp(l + 1.596f * cv), g = clamp(l - 0.392f * cu - 0.813f * cv), b = clamp(l + 2.017f * cu);
                pixels[j * outW + i] = 0xff000000 | r << 16 | g << 8 | b;
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(float c) {
        return Math.max(0, Math.min(255, Math.round(c)));
    }

    private static String param(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static String md5(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
