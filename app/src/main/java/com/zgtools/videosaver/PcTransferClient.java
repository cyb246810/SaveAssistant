package com.zgtools.videosaver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 电脑端传输客户端：与「保存助手 · 传输工作台」（电脑端默认端口 18765）在同一局域网内通信。
 *
 * <p>纯 Java，不引用任何 {@code android.*}，因此可以在 JVM 上直接编译并做端到端单测。
 *
 * <p>元数据（文件名、类别、设备名）一律走 URL query 而不是 HTTP 请求头——
 * JDK 的 {@code HttpURLConnection} 会按 ISO-8859-1 写请求头，中文会被破坏；
 * query 是百分号编码的 UTF-8，天然支持中文。
 */
public final class PcTransferClient {

    public static final int DEFAULT_PORT = 18765;
    private static final byte[] PROBE = "SA_DISCOVER".getBytes(StandardCharsets.US_ASCII);

    private PcTransferClient() {
    }

    /** 电脑端返回的身份信息。 */
    public static final class PcInfo {
        public final String deviceName;
        public final int port;

        PcInfo(String deviceName, int port) {
            this.deviceName = deviceName == null ? "" : deviceName;
            this.port = port;
        }
    }

    /** 上传进度回调。 */
    public interface Progress {
        void onProgress(long sent, long total);
    }

    /**
     * UDP 广播探测同一局域网内的电脑。
     *
     * @return 形如 {@code [ip, 电脑名]} 的列表；广播被系统拦截时返回空列表（此时用户可手动填地址）。
     */
    public static List<String[]> discover(int port, int timeoutMs) {
        List<String[]> found = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);
            // 全局广播在部分路由器/手机热点上会被丢弃；同时向每个 IPv4 网卡的定向广播发送。
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null && !targets.contains(broadcast)) {
                        targets.add(broadcast);
                    }
                }
            }
            for (InetAddress target : targets) {
                DatagramPacket out = new DatagramPacket(PROBE, PROBE.length, target, port);
                socket.send(out);
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (SocketTimeoutException te) {
                    break;
                }
                String ip = in.getAddress().getHostAddress();
                String text = new String(in.getData(), in.getOffset(), in.getLength(),
                        StandardCharsets.UTF_8);
                found.add(new String[]{ip, extract(text, "device_name")});
            }
        } catch (Exception e) {
            // 广播受限：返回空，交给手动填地址；HTTP 错误由 announce 原样反馈。
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return found;
    }

    /** 先用极小响应验证 HTTP 端口可达，失败时异常更容易定位。 */
    public static void ping(String host, int port, int timeoutMs) throws Exception {
        HttpURLConnection c = open("http://" + host + ":" + port + "/api/ping", timeoutMs, timeoutMs);
        try {
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            String resp = readAll(code == 200 ? c.getInputStream() : c.getErrorStream());
            if (code != 200 || !resp.contains("SaveAssistantPC")) {
                throw new Exception("电脑端口可达，但服务响应异常（HTTP " + code + "）");
            }
        } finally {
            c.disconnect();
        }
    }

    /** 向电脑报到配对；成功返回电脑信息，失败抛异常。 */
    public static PcInfo announce(String host, int port, String name, String model, String ip,
                                  int timeoutMs) throws Exception {
        String body = "{\"name\":\"" + jsonEscape(name) + "\",\"model\":\"" + jsonEscape(model)
                + "\",\"ip\":\"" + jsonEscape(ip) + "\"}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = open("http://" + host + ":" + port + "/api/device/announce",
                timeoutMs, 15000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            String resp = readAll(code == 200 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) {
                throw new Exception("电脑返回 HTTP " + code);
            }
            return new PcInfo(extract(resp, "device_name"), port);
        } finally {
            c.disconnect();
        }
    }

    /**
     * 把一路输入流上传给电脑。
     *
     * @param size     文件字节数；&gt;0 时用定长流，否则用分块流。
     * @param fileName 原始文件名（中文可直接传，内部做 UTF-8 百分号编码）。
     */
    public static void upload(String host, int port, InputStream in, long size,
                              String fileName, String category, String device,
                              int connectTimeoutMs, int readTimeoutMs,
                              Progress callback) throws Exception {
        String query = "?device=" + enc(device)
                + "&name=" + enc(fileName)
                + "&category=" + enc(category)
                + "&size=" + size;

        HttpURLConnection c = open("http://" + host + ":" + port + "/api/upload" + query,
                connectTimeoutMs, readTimeoutMs);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (size > 0) {
                c.setFixedLengthStreamingMode(size);
            } else {
                c.setChunkedStreamingMode(64 * 1024);
            }

            OutputStream os = c.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long sent = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                sent += n;
                if (callback != null) {
                    callback.onProgress(sent, size);
                }
            }
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code != 200) {
                String err = readAll(c.getErrorStream());
                throw new Exception("电脑返回 HTTP " + code + (err.isEmpty() ? "" : "：" + err));
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static HttpURLConnection open(String url, int connectTimeoutMs, int readTimeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        return c;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从简易 JSON 文本里取一个字符串字段（够用即可，不引入 JSON 库）。 */
    static String extract(String json, String key) {
        if (json == null) {
            return "";
        }
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int p = q1 + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '\\' && p + 1 < json.length()) {
                char nx = json.charAt(p + 1);
                if (nx == 'n') {
                    sb.append('\n');
                    p++;
                    continue;
                }
                if (nx == 'r') {
                    sb.append('\r');
                    p++;
                    continue;
                }
                if (nx == 't') {
                    sb.append('\t');
                    p++;
                    continue;
                }
                sb.append(nx);
                p++;
                continue;
            }
            if (ch == '"') {
                break;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }
}
