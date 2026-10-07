package za.co.cameraontv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Small LAN-only HTTP API that translates REST requests into overlay controller operations.
 *
 * <p>The server intentionally has no Internet-facing features. Deploy it only on a trusted local
 * network, or isolate the TV and camera network with firewall rules.</p>
 */
final class CameraApiServer extends NanoHTTPD {

    interface Controller {
        StartResult startStream(String rtspUrl, boolean muted, boolean soundRequested);

        void stopStream();

        JSONObject getStatus();
    }

    static final class StartResult {
        final boolean accepted;
        final String message;

        StartResult(boolean accepted, String message) {
            this.accepted = accepted;
            this.message = message;
        }
    }

    private final Controller controller;

    CameraApiServer(int port, Controller controller) {
        super(port);
        this.controller = controller;
    }

    /** Routes status, start, stop, and CORS preflight requests. */
    @Override
    public Response serve(IHTTPSession session) {
        if (Method.OPTIONS.equals(session.getMethod())) {
            return response(Response.Status.NO_CONTENT, new JSONObject());
        }

        String path = normalizePath(session.getUri());
        try {
            if (Method.GET.equals(session.getMethod()) && "/status".equals(path)) {
                return response(Response.Status.OK, controller.getStatus());
            }
            if (Method.GET.equals(session.getMethod()) && "/".equals(path)) {
                JSONObject body = new JSONObject();
                body.put("name", "Camera on TV");
                body.put("start", "POST /stream with JSON "
                        + "{\"url\":\"rtsp://...\",\"muted\":true,\"sound\":false}");
                body.put("stop", "DELETE /stream or POST /stop");
                body.put("status", "GET /status");
                return response(Response.Status.OK, body);
            }
            if ((Method.POST.equals(session.getMethod()) || Method.PUT.equals(session.getMethod()))
                    && "/stream".equals(path)) {
                return startStream(session);
            }
            if ((Method.DELETE.equals(session.getMethod()) && "/stream".equals(path))
                    || (Method.POST.equals(session.getMethod()) && "/stop".equals(path))) {
                controller.stopStream();
                return response(Response.Status.OK, message("Stream stop requested"));
            }
            return response(Response.Status.NOT_FOUND, message("Endpoint not found"));
        } catch (JSONException exception) {
            return response(Response.Status.INTERNAL_ERROR, message("Could not encode response"));
        }
    }

    private Response startStream(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String rawBody = files.get("postData");
            JSONObject request = rawBody == null || rawBody.trim().isEmpty()
                    ? new JSONObject()
                    : new JSONObject(rawBody);

            String url = request.optString("url", "");
            if (url.isEmpty()) {
                url = firstParameter(session, "url");
            }
            url = url == null ? "" : url.trim();
            boolean muted = request.has("muted")
                    ? request.optBoolean("muted", true)
                    : parseBoolean(firstParameter(session, "muted"), true);
            boolean soundRequested = request.has("sound")
                    ? request.optBoolean("sound", false)
                    : parseBoolean(firstParameter(session, "sound"), false);
            if (!isValidRtspUrl(url)) {
                return response(Response.Status.BAD_REQUEST,
                        message("A valid rtsp:// URL is required"));
            }

            StartResult result = controller.startStream(url, muted, soundRequested);
            JSONObject body = message(result.message);
            body.put("accepted", result.accepted);
            return response(result.accepted ? Response.Status.ACCEPTED : Response.Status.CONFLICT, body);
        } catch (IOException | ResponseException exception) {
            return response(Response.Status.BAD_REQUEST, message("Could not read request body"));
        } catch (JSONException exception) {
            return response(Response.Status.BAD_REQUEST, message("Body must be valid JSON"));
        }
    }

    private static boolean isValidRtspUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "rtsp".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static String firstParameter(IHTTPSession session, String name) {
        List<String> values = session.getParameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private static JSONObject message(String value) {
        JSONObject result = new JSONObject();
        try {
            result.put("message", value);
        } catch (JSONException ignored) {
            // A string value cannot fail JSON encoding in Android's JSONObject implementation.
        }
        return result;
    }

    private static Response response(Response.Status status, JSONObject body) {
        Response response = newFixedLengthResponse(status, "application/json", body.toString());
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
