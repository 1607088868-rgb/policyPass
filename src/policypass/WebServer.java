package policypass;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 内置网页服务(JDK 自带 HttpServer,无第三方依赖)。
 *
 * 接口与 Python 版完全一致:
 *   GET  /api/health | /api/policies | /api/presets | /api/sample
 *   POST /api/match | /api/proposal
 */
public final class WebServer {

    private final Data data;

    public WebServer(Data data) {
        this.data = data;
    }

    public void start(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new Router());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("政合我意 · 政策资格体检 已启动");
        System.out.println("  本机访问: http://127.0.0.1:" + port);
        System.out.println("  按 Ctrl+C 停止");
    }

    private final class Router implements HttpHandler {
        public void handle(HttpExchange ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                if ("/api/health".equals(path)) {
                    int n = Engine.list(data.lib().get("policies")).size();
                    sendJson(ex, 200, Engine.map("ok", Boolean.TRUE, "policies", Integer.valueOf(n)));
                    return;
                }
                if ("/api/policies".equals(path)) {
                    sendJson(ex, 200, data.policiesPayload());
                    return;
                }
                if ("/api/presets".equals(path)) {
                    sendJson(ex, 200, Engine.map("presets", data.presetsPayload()));
                    return;
                }
                if ("/api/sample".equals(path)) {
                    sendJson(ex, 200, data.sampleProfile());
                    return;
                }
                if ("/api/match".equals(path)) {
                    if (!"POST".equalsIgnoreCase(method)) {
                        sendJson(ex, 405, Engine.map("ok", Boolean.FALSE, "error", "请用 POST"));
                        return;
                    }
                    Map<String, Object> profile = readBodyObject(ex);
                    sendJson(ex, 200, Engine.match(data.lib(), profile));
                    return;
                }
                if ("/api/proposal".equals(path)) {
                    if (!"POST".equalsIgnoreCase(method)) {
                        sendJson(ex, 405, Engine.map("ok", Boolean.FALSE, "error", "请用 POST"));
                        return;
                    }
                    Map<String, Object> body = readBodyObject(ex);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profile = (Map<String, Object>) body.get("profile");
                    String policyId = body.get("policy_id") == null ? null : String.valueOf(body.get("policy_id"));
                    sendJson(ex, 200, Proposal.build(data.lib(), profile == null ? new LinkedHashMap<String, Object>() : profile, policyId));
                    return;
                }
                if ("/".equals(path) || "/index.html".equals(path)) {
                    serveHtml(ex);
                    return;
                }
                sendJson(ex, 404, Engine.map("ok", Boolean.FALSE, "error", "未找到: " + path));
            } catch (Exception e) {
                try {
                    sendJson(ex, 500, Engine.map("ok", Boolean.FALSE, "error", String.valueOf(e.getMessage())));
                } catch (IOException ignored) {
                    // 响应已发出
                }
            } finally {
                ex.close();
            }
        }
    }

    private void serveHtml(HttpExchange ex) throws IOException {
        String html = Data.readText("web/index.html");
        if (html == null) {
            sendText(ex, 404, "text/plain; charset=utf-8", "未找到 web/index.html");
            return;
        }
        sendText(ex, 200, "text/html; charset=utf-8", html);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBodyObject(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) return new LinkedHashMap<String, Object>();
        Object parsed = Json.parse(text);
        return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<String, Object>();
    }

    private void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        sendText(ex, status, "application/json; charset=utf-8", Json.stringify(payload));
    }

    private void sendText(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.flush();
    }

    /** 供测试直接调用:返回与 /api/match 相同的结构。 */
    public Map<String, Object> match(Map<String, Object> profile) {
        return Engine.match(data.lib(), profile);
    }

    /** 供测试直接调用。 */
    public List<Object> policies() {
        return Engine.list(data.policiesPayload().get("policies"));
    }
}
