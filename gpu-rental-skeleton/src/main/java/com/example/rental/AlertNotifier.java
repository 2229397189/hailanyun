package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 告警外发：按渠道类型拼对应机器人协议，不做「一个 URL 打天下」。
 *
 *   dingtalk  钉钉群机器人  {"msgtype":"text","text":{"content":"..."}}
 *   feishu    飞书群机器人  {"msg_type":"text","content":{"text":"..."}}
 *   wecom     企业微信群机器人 {"msgtype":"text","text":{"content":"..."}}
 *   webhook   自定义         {"title":"...","text":"...","level":"..."}
 *
 * 每种渠道单独超时（3s），失败只记日志不抛异常——告警发不出去不能反过来把告警流程搞挂。
 */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final Db db;

    public AlertNotifier(Db db) {
        this.db = db;
    }

    /** 向单个渠道投递，返回是否成功 */
    public boolean send(Map<String, Object> channel, String title, String text, String level) {
        String type = String.valueOf(channel.get("type"));
        String url = String.valueOf(channel.get("url"));
        String body = switch (type) {
            case "dingtalk" -> "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + esc(title + "\n" + text) + "\"}}";
            case "feishu" -> "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + esc(title + "\n" + text) + "\"}}";
            case "wecom" -> "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + esc(title + "\n" + text) + "\"}}";
            default -> "{\"title\":\"" + esc(title) + "\",\"text\":\"" + esc(text)
                    + "\",\"level\":\"" + esc(level == null ? "" : level) + "\"}";
        };
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            log.info("告警外发 {} → {} HTTP {}", channel.get("name"), type, resp.statusCode());
            return ok;
        } catch (Exception e) {
            log.warn("告警外发 {} 失败：{}", channel.get("name"), e.getMessage());
            return false;
        }
    }

    /** 广播到所有启用的渠道，返回成功条数 */
    public int broadcast(String title, String text, String level) {
        int ok = 0;
        for (Map<String, Object> ch : db.enabledAlertChannels()) {
            if (send(ch, title, text, level)) {
                ok++;
            }
        }
        return ok;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
