package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * MCAP 后端 API 客户端
 * 负责与 MCAP 服务进行 HTTP/JSON 通信
 */
public class MCAPApiClient {

    private final String serverAddress;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Logger logger;

    public MCAPApiClient(String serverAddress, String apiKey, Logger logger) {
        this.serverAddress = serverAddress.endsWith("/")
                ? serverAddress.substring(0, serverAddress.length() - 1)
                : serverAddress;
        this.apiKey = apiKey;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 服务健康检查
     * GET /api/server/ping (无需鉴权)
     *
     * @return 服务是否在线
     */
    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverAddress + "/api/server/ping"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                return "ok".equals(json.get("status").getAsString());
            }
            return false;
        } catch (Exception e) {
            logger.warn("MCAP 服务健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 注册游戏 UID (获取绑定链接和绑定码)
     * GET /api/server/registerUserID?uid={uid}&gameName={gameName}
     *
     * @param uid      玩家唯一标识 (Minecraft UUID)
     * @param gameName 游戏内角色名
     * @return 响应 JSON, 包含 bindLink / bindCode / alreadyBound 等字段; 失败返回 null
     */
    public JsonObject registerUserID(String uid, String gameName) {
        try {
            String url = serverAddress + "/api/server/registerUserID?uid="
                    + java.net.URLEncoder.encode(uid, java.nio.charset.StandardCharsets.UTF_8)
                    + "&gameName="
                    + java.net.URLEncoder.encode(gameName, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } else {
                logger.warn("registerUserID 失败, HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            logger.warn("registerUserID 请求异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询用户实名信息
     * GET /api/server/getUserInfo?uid={uid}
     *
     * @param uid 玩家唯一标识 (Minecraft UUID)
     * @return 响应 JSON, 包含 registered / is_minor / ageRange 等字段; 失败返回 null
     */
    public JsonObject getUserInfo(String uid) {
        try {
            String url = serverAddress + "/api/server/getUserInfo?uid="
                    + java.net.URLEncoder.encode(uid, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } else {
                logger.warn("getUserInfo 失败, HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            logger.warn("getUserInfo 请求异常: {}", e.getMessage());
            return null;
        }
    }
}
