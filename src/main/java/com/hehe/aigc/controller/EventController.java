package com.hehe.aigc.controller;



import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class EventController {

    // 构建client
    @Value("${feishu.app-id}")
    private String appId;

    @Value("${feishu.app-secret}")
    private String appSecret;

    @Value("${baichuan.api-url}")
    private String baichuanURL;

    @Value("${baichuan.api-key}")
    private String baichuanKey;

    private Client client = null;
    // 接收 POST 请求并打印请求体
    @PostMapping("/receive")
    public Map<String, String> receiveEvent(@RequestBody Map<String, Object> request) {
        client = Client.newBuilder(appId, appSecret).build();
        System.out.println(appId + " " + appSecret);
        System.out.println("request: " +request.toString() );
        // 处理 url_verification 请求
        if ("url_verification".equals(request.get("type"))) {
            String challenge = (String) request.get("challenge");
            Map<String, String> response = new HashMap<>();
            response.put("challenge", challenge);
            return response;
        }
        // 通过 get 方法获取 "event" 字段
        Map<String, Object> event = (Map<String, Object>) request.get("event");

        // 从 "event" 字段中提取 "message" 字段
        Map<String, Object> message = (Map<String, Object>) event.get("message");

        // 获取 content 字段（它是一个 JSON 字符串）
        String contentJson = (String) message.get("content");

        // 使用 ObjectMapper 将 content 字符串解析成 Map
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> content = null;
        try {
            content = objectMapper.readValue(contentJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // 提取 'text' 字段
        String text = content.get("text");
        // 提取 chat_id
        String chatId = (String) message.get("chat_id");

        // 打印提取的 text
        System.out.println("Received message chat_id : " + chatId + " & text: " + text + " &api-key: " + baichuanKey);
        String result = null;
        try {
            result = sendBaichuanRequest(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 创建请求对象
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("chat_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType("text")
                        .content("{\"text\":\"" + result + "\"}")
                        .build())
                .build();

        // 发起请求
        CreateMessageResp resp = null;
        try {
            resp = client.im().message().create(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 处理服务端错误
        if (!resp.success()) {
            System.out.println(String.format("code:%s,msg:%s,reqId:%s, resp:%s",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(), Jsons.createGSON(true, false).toJson(JsonParser.parseString(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8)))));
            return null;
        }
        return null;
    }
    public String sendBaichuanRequest(String content) throws IOException {

        // 构建请求数据
        JSONObject data = new JSONObject();
        data.put("model", "Baichuan-NPC-Turbo");

        JSONObject characterProfile = new JSONObject();
        characterProfile.put("character_id", 33281);
        data.put("character_profile", characterProfile);

        // 构建消息
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));
        data.put("messages", messages);

        data.put("temperature", 0.86);
        data.put("top_p", 1.0);
        data.put("max_tokens", 512);
        data.put("stream", false);

        // 打印请求数据（可选）
        System.out.println("请求数据：" + data.toString());

        // 设置请求头
        String authorization = "Bearer " + baichuanKey;
        HttpURLConnection connection = (HttpURLConnection) new URL(baichuanURL).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", authorization);
        connection.setDoOutput(true);

        // 发送请求数据
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = data.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 获取响应
        int statusCode = connection.getResponseCode();
        String responseMessage = connection.getResponseMessage();

        // 读取响应体
        StringBuilder responseBody = new StringBuilder();
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()))) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                responseBody.append(inputLine);
            }
        }

        // 打印响应结果
        String result = null;
        if (statusCode == 200) {
            System.out.println("请求成功！");
            System.out.println("响应body: " + responseBody.toString());
            String requestId = connection.getHeaderField("X-BC-Request-Id");
            System.out.println("请求成功，X-BC-Request-Id: " + requestId);
            // 将 JSON 字符串转换为 JSONObject
            JSONObject jsonObject = new JSONObject(responseBody.toString());

            // 获取 choices 数组中的第一个元素
            JSONArray choices = jsonObject.getJSONArray("choices");
            JSONObject choice = choices.getJSONObject(0);

            // 提取 content 字段
            result = choice.getJSONObject("message").getString("content");
        } else {
            System.out.println("请求失败，状态码: " + statusCode);
            System.out.println("请求失败，body: " + responseBody.toString());
            String requestId = connection.getHeaderField("X-BC-Request-Id");
            System.out.println("请求失败，X-BC-Request-Id: " + requestId);
        }

        return result;
    }
}