package com.zgtools.videosaver;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTPS 客户端：直接开 TLS socket、手写 HTTP/1.1 请求。
 *
 * <p><b>为什么不用 {@code HttpURLConnection}</b>：JDK 的 HttpURLConnection 把
 * {@code Origin} 列为受限请求头并<b>静默丢弃</b>，而视频号预览接口恰好要求同时带
 * {@code Origin} 与 {@code Referer} 才放行，缺一个就返回
 * {@code {"errCode":-1,"errMsg":"permission verification failed"}}。
 * 这种"头没发出去但不报错"的失败极难排查，所以这里自己控制字节流，
 * 发了什么就是什么，不再依赖平台实现是否会过滤。
 *
 * <p>本类不引用任何 Android API，因此可以在 JVM 上直接跑真实代码路径做验证。
 * 只实现了项目真正需要的能力：POST JSON、读 Content-Length / chunked 响应、超时。
 * 不做重定向（本项目的三个接口都不重定向）。
 */
final class SimpleHttps {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private SimpleHttps() {}

    /** 一次响应。body 为 UTF-8 解码后的文本。 */
    static final class Response {
        final int status;
        final String body;
        final Map<String, String> headers;

        Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 发一个 POST 请求（body 为 UTF-8 文本）。
     *
     * @param headers 形如 {{"Content-Type","application/json"}, …}；Host 与 Content-Length
     *                由本类自动补，调用方不要传。
     */
    static Response post(String urlString, String body, String[][] headers) throws IOException {
        URL url = new URL(urlString);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("只允许 https");
        }
        String host = url.getHost();
        int port = url.getPort() == -1 ? 443 : url.getPort();

        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (url.getQuery() != null) path = path + "?" + url.getQuery();

        byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);

        SSLSocket socket = openTls(host, port);
        try (OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            StringBuilder head = new StringBuilder();
            head.append("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append("\r\n");
            head.append("Connection: close\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n");
            for (String[] header : headers) {
                if (header == null || header.length < 2) continue;
                if (header[0] == null || header[1] == null) continue;
                if ("host".equalsIgnoreCase(header[0])
                        || "content-length".equalsIgnoreCase(header[0])
                        || "connection".equalsIgnoreCase(header[0])) {
                    continue;
                }
                head.append(header[0]).append(": ").append(header[1]).append("\r\n");
            }
            head.append("\r\n");

            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();

            return readResponse(in);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 开一条带 SNI 与主机名校验的 TLS 连接。 */
    private static SSLSocket openTls(String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        // 用带 host 的重载创建，让实现自动带上 SNI
        SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true);
        SSLParameters parameters = socket.getSSLParameters();
        // 打开主机名校验，避免退化成"只加密不认证"
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) throw new EOFException("服务器没有返回任何内容");
        int status = parseStatus(statusLine);

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            String existing = headers.get(name);
            headers.put(name, existing == null ? value : existing + ", " + value);
        }

        byte[] body = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"))
                ? readChunked(in, MAX_BODY_BYTES)
                : readFixed(in, headers.get("content-length"), MAX_BODY_BYTES);

        return new Response(status, new String(body, StandardCharsets.UTF_8), headers);
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("状态行无法解析: " + statusLine);
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("状态行无法解析: " + statusLine);
        }
    }

    /** 读一行（到 \n 为止，去掉 \r）。返回 null 表示流已结束。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
        }
        if (!any && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFixed(InputStream in, String contentLength, int maxBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        if (contentLength != null) {
            long declared;
            try {
                declared = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                declared = -1;
            }
            if (declared >= 0) {
                long remaining = Math.min(declared, maxBytes);
                byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                return out.toByteArray();
            }
        }
        // 没有 Content-Length：以 Connection: close 为前提读到流结束
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > maxBytes) break;
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) break;
            byte[] chunk = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = in.read(chunk, offset, size - offset);
                if (read == -1) break;
                offset += read;
            }
            out.write(chunk, 0, offset);
            if (out.size() > maxBytes) break;
            readLine(in); // 块尾 CRLF
        }
        return out.toByteArray();
    }

    /** 便于日志/报错时截取片段。 */
    static String brief(String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength
                ? normalized.substring(0, maxLength) + "…"
                : normalized;
    }

    static List<String[]> headersOf(String[][] raw) {
        List<String[]> list = new ArrayList<>();
        if (raw == null) return list;
        for (String[] header : raw) list.add(header);
        return list;
    }
}
