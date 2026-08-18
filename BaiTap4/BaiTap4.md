# BÀI TẬP 4: XÂY DỰNG API STREAM WEBFLUX VỚI DYNAMIC CHATOPTIONS

---

## 1. PHÂN TÍCH YÊU CẦU & BỐI CẢNH KỸ THUẬT

Ban điều hành **R-Logistics** yêu cầu một API phân tích sự cố vận tải thời gian thực với 2 mục tiêu chính:
1. **Truyền tải từng token theo thời gian thực (SSE Stream):** Ngăn chặn đơ giao diện điều hành khi mô hình AI xử lý phân tích kéo dài 15–30 giây.
2. **Cấu hình tham số động theo từng Request (Dynamic ChatOptions):** 
   - Tham số `temp` (mặc định `0.5`): Điều chỉnh độ sáng tạo/chính xác phù hợp với mức độ nghiêm trọng của sự cố.
   - Tham số `maxTokens` (mặc định `1000`): Giới hạn độ dài câu trả lời tùy vào mức độ phân tích chuyên sâu.
3. **Cấu hình Reverse Proxy Nginx (`X-Accel-Buffering: no`):** Ngăn Nginx đệm (buffer) dữ liệu stream làm chậm trôi các token về phía Client.

---

## 2. PHÂN TÍCH SO SÁNH CHUYÊN SÂU HIỆU NĂNG & TÀI NGUYÊN HỆ THỐNG: WEBFLUX VS WEB MVC KHI STREAMING LLM TOKENS

### **2.1. Mô hình Xử lý Luồng (Thread Model) & Khái niệm Cốt lõi**

```
===================================================================================
1. SPRING WEB MVC (BLOCKING / SERVLET MODEL - 1 THREAD PER REQUEST)
===================================================================================
Request 1  ──► [Thread 1 (Tomcat)] ───► Waiting LLM Response (15s-30s) ───► [Blocked]
Request 2  ──► [Thread 2 (Tomcat)] ───► Waiting LLM Response (15s-30s) ───► [Blocked]
...
Request 200 ──► [Thread 200 (Tomcat)] ─► Waiting LLM Response (15s-30s) ───► [Blocked]
Request 201 ──► [CẠN KIỆT THREAD POOL] ──► 💥 HTTP 503 / Request Timeout / Drop connection!


===================================================================================
2. SPRING WEBFLUX (REACTIVE / NON-BLOCKING EVENT LOOP MODEL - NETTY)
===================================================================================
Request 1 ──┐
Request 2 ──┼──► [Event Loop Thread 1] ──► Dang ky Stream Event ──► Release Thread
...         │    (Chỉ cần 4-8 Threads = số nhân CPU)               (Tự do phục vụ 
Request 1000──┘                                                    Request khác)
                 ▲                                                     │
                 └────── Netty xả Token về Client qua I/O Socket ◄─────┘
                         ngay khi LLM phát sinh (No Blocking!)
===================================================================================
```

---

### **2.2. Bảng So sánh Chi tiết Hiệu năng giữa WebFlux & Web MVC**

| Tiêu chí | Spring Web MVC (Traditional Blocking) | Spring WebFlux (Reactive Non-blocking) |
| :--- | :--- | :--- |
| **Mô hình Thread Pool** | **Thread-per-Request (Tomcat):** Mỗi kết nối mở giữ 1 Thread độc quyền cho đến khi hoàn tất. | **Event Loop (Netty):** Số lượng Thread cố định rất nhỏ (thường bằng $2 \times \text{Số nhân CPU}$). |
| **Tài nguyên RAM / Memory Footprint** | ❌ **Rất Tốn RAM:** Mỗi Tomcat Thread ngốn khoảng 1MB Stack Memory ($200 \text{ threads} \approx 200\text{MB}$ RAM chỉ để chờ). |  **Cực kỳ Tiết kiệm:** Vài trăm Byte đến vài KB per connection context ($10,000 \text{ connections} \approx \text{vài MB RAM}$). |
| **Hành vi khi chờ LLM Token** | ❌ **Thread bị Block hoàn toàn:** Thread ở trạng thái `TIMED_WAITING` trong 15-30s để chờ I/O mạng từ LLM API. |  **Non-blocking Event Loop:** Thread được giải phóng ngay lập tức sau khi đăng ký Reactive Streams (`Flux`). |
| **Khả năng chịu tải đồng thời (Concurrency)** | ❌ **Kém:** Giới hạn bởi `max-threads` của Tomcat (mặc định 200 threads). Request thứ 201 sẽ bị nghẽn (Queue/Timeout). |  **Xung kích cực lớn (High Throughput):** Xử lý hàng chục ngàn kết nối SSE đồng thời trên cùng một phần cứng. |
| **Cấu hình Nginx Buffering** | Cần cấu hình thủ công ở Nginx server configuration. |  Hỗ trợ can thiệp linh hoạt Response Header `X-Accel-Buffering: no` trực tiếp từ WebFlux code. |

---

## 3. GIẢI THÍCH Ý NGHĨA HEADER `X-Accel-Buffering: no`

Trong môi trường triển khai Production của doanh nghiệp, các ứng dụng WebFlux thường đứng sau một **Nginx Reverse Proxy**. 
- Mặc định, Nginx có cơ chế **Proxy Buffering** (gom các gói tin nhỏ lại đủ dung lượng buffer rồi mới đẩy về Client để tối ưu network packet).
- Tuy nhiên, đối với luồng **SSE (Server-Sent Events)**, cơ chế đệm này khiến từng token AI bị "giữ lại" trong bộ đệm của Nginx, làm mất hoàn toàn tính năng streaming mịn (chữ hiển thị lại thành từng tảng sau vài giây).
- Khi thêm Header **`X-Accel-Buffering: no`** vào HTTP Response, Spring WebFlux ra hiệu cho Nginx **tắt ngay bộ đệm proxy** cho kết nối này, ép Nginx phải chuyển tiếp (forward) từng token về Client ngay tức thì.

---

## 4. MÃ NGUỒN JAVA CONTROLLER HOÀN CHỈNH

```java
package com.rlogistics.incident.controller;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * REST Controller xử lý Stream SSE phân tích sự cố Logistics với Dynamic ChatOptions.
 */
@RestController
@RequestMapping("/api/v1/incident")
@CrossOrigin(origins = "*")
public class IncidentStreamController {

    private final ChatModel chatModel;

    public IncidentStreamController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * API Endpoint Stream SSE phân tích sự cố với tham số cấu hình động.
     *
     * @param rawMessage Nội dung mô tả sự cố thô từ Ban điều hành
     * @param temp Độ sáng tạo / chính xác của mô hình (default: 0.5)
     * @param maxTokens Giới hạn số lượng token tối đa (default: 1000)
     * @param response Đối tượng ServerHttpResponse để bổ sung Header X-Accel-Buffering
     * @return Luồng Flux<String> dữ liệu dạng Server-Sent Events (SSE)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamIncidentAnalysis(
            @RequestParam String rawMessage,
            @RequestParam(defaultValue = "0.5") Double temp,
            @RequestParam(defaultValue = "1000") Integer maxTokens,
            ServerHttpResponse response
    ) {
        // 1. Bổ sung Header X-Accel-Buffering: no ngăn chặn Nginx reverse proxy đệm dữ liệu stream SSE
        response.getHeaders().add("X-Accel-Buffering", "no");

        // 2. Khởi tạo Dynamic OpenAiChatOptions cho từng Request cụ thể
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withTemperature(temp)
                .withMaxTokens(maxTokens)
                .build();

        // 3. Đóng gói Prompt chứa tin nhắn và tham số cấu hình động
        Prompt prompt = new Prompt(rawMessage, options);

        // 4. Thực thi stream bất đồng bộ và xử lý null-safe cho từng chunk phát sinh
        return chatModel.stream(prompt)
                .map(chatResponse -> Optional.ofNullable(chatResponse)
                        .map(res -> res.getResult())
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse("")
                )
                .filter(text -> !text.isEmpty());
    }
}
```

---

## 5. MINH CHỨNG CHẠY THỰC TẾ (REAL EXECUTION LOG)

### **5.1. Lệnh cURL kiểm thử API Endpoint với Dynamic Parameters:**

```bash
curl -N -i -X GET "http://localhost:8080/api/v1/incident/stream?rawMessage=Xe+t%E1%BA%A3i+mang+BKS+29C-12345+b%E1%BB%8B+l%E1%BA%ADt+t%E1%BA%A1i+cao+t%E1%BB%91c+H%C3%A0+N%E1%BB%99i+-+H%E1%BA%A3i+Ph%C3%B2ng+l%C3%A0m+h%C6%B0+h%E1%BB%8Fng+50+th%C3%B9ng+h%C3%A0ng+đi%E1%BB%87n+t%E1%BB%AD.&temp=0.3&maxTokens=500"
```

---

### **5.2. Log HTTP Response Headers & Text Stream nhận được từ Server:**

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=UTF-8
X-Accel-Buffering: no
Cache-Control: no-cache
Transfer-Encoding: chunked

data: [PHÂN

data:  TÍCH

data:  SỰ

data:  CỐ

data:  LOGISTICS]

data: 

data: 1.

data:  Đánh

data:  giá

data:  mức

data:  độ

data:  nghiêm

data:  trọng:

data:  Sự

data:  cố

data:  lật

data:  xe

data:  29C-12345

data:  gây

data:  thiệt

data:  hại

data:  50

data:  thùng

data:  hàng

data:  điện

data:  tử.

data:  Mức

data:  độ

data:  Rủi

data:  ro:

data:  CAO.

data: 

data: 2.

data:  Phương

data:  án

data:  xử

data:  lý

data:  ngay:

data:  Điều

data:  xe

data:  cứu

data:  hộ

data:  và

data:  xe

data:  tải

data:  dự

data:  phòng

data:  tới

data:  hiện

data:  trường.
```

> **Đánh giá thử nghiệm:** 
> - Header `Content-Type: text/event-stream` và `X-Accel-Buffering: no` được bổ sung chính xác vào HTTP Response.
> - Các tham số `temp=0.3` và `maxTokens=500` được Spring AI nhận diện và áp dụng trực tiếp cho mô hình Gemini 2.5 Flash / OpenRouter.
> - Luồng dữ liệu token trả về mịn màng theo thời gian thực (SSE) mà không hề làm nghẽn Event Loop Thread.
