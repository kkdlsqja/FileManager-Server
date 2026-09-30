package com.filemanager.server.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.Base64;

@RestController
public class PairingPageController {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @GetMapping(value = "/pair", produces = MediaType.TEXT_HTML_VALUE)
    public String pairingPage() {
        String scheme = sslEnabled ? "https" : "http";
        List<String> addresses = findLocalIpv4Addresses();

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>FolderHelper PC 연결</title>
                  <style>
                    * { box-sizing: border-box; }
                    body {
                      margin: 0; padding: 28px 16px; background: #f4f6fa;
                      color: #202637; font-family: Arial, sans-serif;
                    }
                    main {
                      max-width: 560px; margin: 0 auto; padding: 28px;
                      background: white; border-radius: 18px;
                      box-shadow: 0 8px 28px #18243a18; text-align: center;
                    }
                    h1 { margin: 0 0 8px; font-size: 25px; }
                    .lead { color: #667085; margin: 0 0 24px; }
                    .pc-card {
                      padding: 20px 14px; margin: 16px 0;
                      border: 1px solid #e4e7ec; border-radius: 14px;
                    }
                    .qr {
                      width: 250px; height: 250px; max-width: 100%;
                      display: block; margin: 0 auto 14px;
                      image-rendering: pixelated;
                    }
                    .address {
                      font-size: 18px; font-weight: 700; overflow-wrap: anywhere;
                    }
                    .hint { color: #667085; font-size: 14px; line-height: 1.5; }
                    button {
                      border: 0; border-radius: 9px; padding: 10px 16px;
                      color: white; background: #2864dc; font-size: 15px;
                    }
                    .empty { padding: 18px; background: #fff4e5; border-radius: 12px; }
                  </style>
                </head>
                <body>
                <main>
                  <h1>FolderHelper PC 연결</h1>
                  <p class="lead">휴대폰과 이 PC를 같은 Wi-Fi에 연결한 뒤 QR을 스캔하세요.</p>
                """);

        if (addresses.isEmpty()) {
            html.append("""
                    <div class="empty">
                      PC의 사설 IPv4 주소를 찾지 못했습니다.
                      Windows에서 ipconfig를 실행해 Wi-Fi 또는 이더넷의 IPv4 주소를 확인하세요.
                    </div>
                    """);
        } else {
            for (String ip : addresses) {
                String address = scheme + "://" + ip + ":" + serverPort + "/";
                try {
                    String qrData = createQrDataUri(address);
                    html.append("<section class=\"pc-card\">");
                    html.append("<img class=\"qr\" alt=\"PC 연결 QR 코드\" src=\"");
                    html.append(qrData);
                    html.append("\">");
                    html.append("<div class=\"address\">PC IP: ");
                    html.append(escapeHtml(ip));
                    html.append(":" ).append(serverPort);
                    html.append("</div>");
                    html.append("<p class=\"hint\">서버 주소: ");
                    html.append(escapeHtml(address));
                    html.append("<br>QR을 스캔할 수 없으면 이 주소를 앱에 입력하세요.</p>");
                    html.append("<button type=\"button\" data-address=\"");
                    html.append(escapeHtml(address));
                    html.append("\" onclick=\"navigator.clipboard.writeText(this.dataset.address).then(() => this.textContent='복사 완료')\">주소 복사</button>");
                    html.append("</section>");
                } catch (WriterException | IOException exception) {
                    html.append("<p class=\"empty\">QR 코드를 생성하지 못했습니다: ");
                    html.append(escapeHtml(exception.getMessage()));
                    html.append("</p>");
                }
            }
        }

        html.append("""
                  <p class="hint">
                    표시된 주소는 이 PC의 현재 네트워크 주소입니다.
                    Wi-Fi가 바뀌거나 IP가 변경되면 이 페이지를 새로고침하세요.
                  </p>
                </main>
                </body>
                </html>
                """);
        return html.toString();
    }

    private List<String> findLocalIpv4Addresses() {
        Set<String> result = new LinkedHashSet<>();

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (IOException exception) {
            return new ArrayList<>();
        }

        return new ArrayList<>(result);
    }

    private String createQrDataUri(String content)
            throws WriterException, IOException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                300,
                300,
                hints
        );

        BufferedImage image = new BufferedImage(
                matrix.getWidth(),
                matrix.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(
                        x,
                        y,
                        matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF
                );
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}


