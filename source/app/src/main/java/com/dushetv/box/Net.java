package com.dushetv.box;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 原生 HTTP 网络层。
 * 关键：Android 4.1~4.4 的 SSL 栈默认关闭 TLS1.1/1.2，现代 HTTPS 站点（尤其带 CDN 的）
 * 会直接握手失败。这里强制开启所有 TLS 协议并放宽证书校验，绕开老电视的根证书过期问题。
 */
public final class Net {
    private static final String TAG = "DuSheNet";
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 4.4.2; TV Box Build/KOT49H) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/60.0 Safari/537.36";

    private static SSLSocketFactory sFactory;
    private static final HostnameVerifier sVerifier = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return true;
        }
    };

    private Net() {
    }

    private static boolean sGlobalInstalled = false;

    /**
     * v1.6：把宽松 TLS 装成 JVM 全局默认。
     * ExoPlayer 的 DefaultHttpDataSource 内部自己 open HttpURLConnection，
     * 拿不到 Net 私有的 factory —— 老盒子上 https 源会直接握手失败，
     * 而且失败发生在 native 层之前，界面只剩黑屏。
     * 装成全局默认后，播放器的网络请求才能复用同一套 TLS 放宽策略。
     */
    /**
     * v4.7：启用磁盘 HTTP 响应缓存。
     * 图片 / 接口配置会在首次请求后落到 cacheDir/http，二次访问（翻页回来、重进分类、重启）
     * 直接读磁盘，避免每次都要走网络、显得「线路慢、图片加载不了」。
     * 进程级全局（ResponseCache.setDefault），只需在 MainActivity.onCreate 调一次。
     */
    private static boolean sCacheInstalled = false;

    public static synchronized void installHttpCache(Context ctx) {
        if (sCacheInstalled) return;
        sCacheInstalled = true;
        try {
            File dir = new File(ctx.getCacheDir(), "http");
            if (!dir.exists()) dir.mkdirs();
            android.net.http.HttpResponseCache.install(dir, 16 * 1024 * 1024); // 16MB，足够几十张海报
        } catch (Throwable t) {
            Log.w(TAG, "installHttpCache failed", t);
        }
    }

    public static synchronized void installGlobalTls() {
        if (sGlobalInstalled) return;
        sGlobalInstalled = true;
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(factory());
            HttpsURLConnection.setDefaultHostnameVerifier(sVerifier);
        } catch (Throwable t) {
            Log.w(TAG, "installGlobalTls failed", t);
        }
    }

    static synchronized SSLSocketFactory factory() {
        if (sFactory != null) return sFactory;
        try {
            TrustManager[] tm = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext ctx = null;
            String[] tries = {"TLSv1.2", "TLSv1.1", "TLS", "SSL"};
            for (String t : tries) {
                try {
                    ctx = SSLContext.getInstance(t);
                    break;
                } catch (NoSuchAlgorithmException ignored) {
                }
            }
            if (ctx == null) ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            final SSLSocketFactory base = ctx.getSocketFactory();
            sFactory = new TlsAllFactory(base);
        } catch (Throwable e) {
            Log.w(TAG, "init ssl factory failed, fallback default", e);
            sFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        return sFactory;
    }

    /** 强制开启连接上所有受支持的 TLS 协议（老设备默认只开 TLS1.0） */
    private static final class TlsAllFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        TlsAllFactory(SSLSocketFactory delegate) {
            this.d = delegate;
        }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                try {
                    String[] sup = ss.getSupportedProtocols();
                    List<String> want = new ArrayList<String>();
                    for (int i = 0; i < sup.length; i++) {
                        if (sup[i] != null && sup[i].startsWith("TLS")) want.add(sup[i]);
                    }
                    if (!want.isEmpty()) {
                        ss.setEnabledProtocols(want.toArray(new String[want.size()]));
                    }
                } catch (Throwable ignored) {
                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return fix(d.createSocket());
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return fix(d.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return fix(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost,
                                   int localPort) throws IOException {
            return fix(d.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return fix(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port,
                                   java.net.InetAddress localAddress, int localPort)
                throws IOException {
            return fix(d.createSocket(address, port, localAddress, localPort));
        }
    }

    public static class Resp {
        public int code;
        public Map<String, List<String>> headers;
        public String body;
        public byte[] raw;
    }

    // ---------------- URL 规范化（中文域名 / 中文路径） ----------------

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 把用户/源站里常见的「中文域名」「中文路径」转成 Java URL 能处理的形式。
     * 例：http://www.饭太硬.com/tv        -> http://www.xn--sss604efuw.com/tv
     * 例：http://xhww.fun:63/小米/D.json -> http://xhww.fun:63/%E5%B0%8F%E7%B1%B3/D.json
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return s;
        // 去掉可能被复制进来的零宽字符
        s = s.replace("\u200b", "").replace("\ufeff", "");
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        try {
            int schemeEnd = s.indexOf("://") + 3;
            String scheme = s.substring(0, schemeEnd);
            String rest = s.substring(schemeEnd);
            int slash = rest.indexOf('/');
            String authority = (slash < 0) ? rest : rest.substring(0, slash);
            String tail = (slash < 0) ? "" : rest.substring(slash);

            String userinfo = "";
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                userinfo = authority.substring(0, at + 1);
                authority = authority.substring(at + 1);
            }
            String port = "";
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(']') < colon) {
                port = authority.substring(colon);
                authority = authority.substring(0, colon);
            }
            String host = authority;
            if (!isAscii(host)) {
                try {
                    host = java.net.IDN.toASCII(host);
                } catch (Throwable ignored) {
                }
            }
            return scheme + userinfo + host + port + encodeTail(tail);
        } catch (Throwable t) {
            return s;
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /** path + query 里的非 ASCII 字符做 UTF-8 百分号编码，已编码的 %XX 原样保留 */
    private static String encodeTail(String p) {
        if (p == null || p.length() == 0) return "";
        if (isAscii(p) && p.indexOf(' ') < 0) return p;
        byte[] bytes;
        try {
            bytes = p.getBytes("utf-8");
        } catch (Throwable t) {
            return p;
        }
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b < 0x80) {
                char c = (char) b;
                if (c == ' ') {
                    sb.append("%20");
                } else if (c == '"' || c == '<' || c == '>' || c == '\\'
                        || c == '^' || c == '`' || c == '{' || c == '|' || c == '}') {
                    sb.append('%').append(HEX[b >> 4]).append(HEX[b & 0xf]);
                } else {
                    sb.append(c);
                }
            } else {
                sb.append('%').append(HEX[b >> 4]).append(HEX[b & 0xf]);
            }
        }
        return sb.toString();
    }

    // ---------------- 测速探针 ----------------

    public static class Probe {
        public boolean ok;
        public int code;
        public long ms = -1;
        public String note = "";
        public String head = "";
    }

    /**
     * 轻量测速：连上去只读前 maxBytes 字节就断开，不整包下载（老盒子内存小，绝不能全读）。
     * 返回耗时毫秒与响应片段，供上层判断是否像 TVBox 配置。
     */
    public static Probe probe(String url, int connMs, int readMs, int maxBytes) {
        Probe p = new Probe();
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL u = new URL(normalize(url));
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(factory());
                https.setHostnameVerifier(sVerifier);
            }
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(connMs);
            conn.setReadTimeout(readMs);
            // v4.7：测速必须走真实网络，禁止命中磁盘缓存，否则测的是缓存速度而非线路
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", DEFAULT_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.connect();
            p.code = conn.getResponseCode();
            in = (p.code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
                byte[] buf = new byte[2048];
                int n, total = 0;
                while (total < maxBytes && (n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    total += n;
                }
                try {
                    p.head = new String(bo.toByteArray(), "utf-8");
                } catch (Throwable ignored) {
                }
            }
            p.ms = System.currentTimeMillis() - t0;
            p.ok = (p.code >= 200 && p.code < 400);
            if (!p.ok) p.note = "HTTP " + p.code;
            return p;
        } catch (SocketTimeoutException e) {
            p.ms = System.currentTimeMillis() - t0;
            p.note = "超时";
            return p;
        } catch (Throwable t) {
            p.ms = System.currentTimeMillis() - t0;
            String m = t.getMessage();
            if (m == null) m = t.getClass().getSimpleName();
            if (m.length() > 40) m = m.substring(0, 40);
            p.note = m;
            return p;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 执行一次 GET。text=true 返回解码后的字符串（body），false 时 raw 为字节、body 为 null。
     */
    public static Resp get(String url, Map<String, String> headers, boolean text)
            throws IOException {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(normalize(url));
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(factory());
                https.setHostnameVerifier(sVerifier);
            }
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", DEFAULT_UA);
            conn.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.connect();
            r.code = conn.getResponseCode();
            r.headers = conn.getHeaderFields();
            InputStream in = (r.code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                r.body = "";
                r.raw = new byte[0];
                return r;
            }
            if (text) {
                r.body = readString(in, conn.getContentType());
                r.raw = r.body.getBytes("utf-8");
            } else {
                r.raw = readAll(in);
            }
            return r;
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("net error: " + t.getMessage(), t);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String getText(String url, Map<String, String> headers) throws IOException {
        return get(url, headers, true).body;
    }

    public static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        return get(url, headers, false).raw;
    }

    private static String readString(InputStream in, String contentType) throws IOException {
        String enc = "utf-8";
        if (contentType != null) {
            int i = contentType.toLowerCase().indexOf("charset=");
            if (i >= 0) {
                String c = contentType.substring(i + 8).trim();
                if (c.length() > 0 && !c.equalsIgnoreCase("utf-8")) enc = c;
            }
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        try {
            in.close();
        } catch (Throwable ignored) {
        }
        byte[] data = bo.toByteArray();
        try {
            return new String(data, enc);
        } catch (Throwable t) {
            return new String(data);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        try {
            in.close();
        } catch (Throwable ignored) {
        }
        return bo.toByteArray();
    }

    public static Map<String, String> hdr(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
