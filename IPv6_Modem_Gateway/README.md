# IPv6 Modem Gateway 0.7.0 – HTTPS / Telnet TCP Tunnel

## Mục tiêu
Android B truy cập modem qua IPv6 Cellular của Android A. Android A chuyển tiếp TCP nguyên bản từ kết nối Cellular sang modem qua Wi-Fi. Gateway không giải mã TLS, không xử lý HTTP và không diễn giải nội dung Telnet.

## Hai phương thức kết nối modem
- **HTTPS:** chọn HTTPS, cổng modem mặc định `443`; Android B dùng trình duyệt với địa chỉ `https://[IPv6-Cellular-cua-Android-A]:8080/`.
- **Telnet:** chọn TELNET, cổng modem mặc định `23`; Android B dùng ứng dụng Telnet client kết nối tới IPv6 Cellular của Android A, cổng Gateway `8080` (lệnh thường dùng: `telnet <IPv6> 8080`; nếu client yêu cầu cú pháp IPv6 có ngoặc vuông, dùng `telnet [<IPv6>] 8080`).

## Luồng kết nối
1. Android A kết nối Wi-Fi của modem và bật dữ liệu di động.
2. Listener trên Android A nhận TCP tại địa chỉ IPv6 Cellular, cổng Gateway đã cấu hình (mặc định `8080`).
3. APK tạo socket đích bằng `wifiNetwork.getSocketFactory()` tới IP modem trong ô cấu hình và cổng modem đã chọn.
4. Hai luồng byte được chuyển tiếp nguyên bản hai chiều cho đến khi kết nối đóng.

## Phần được giữ nguyên
- Giao diện classic, cấu trúc các khu vực, nút bấm, log, sao chép log, chia sẻ log và xóa log.
- Cơ chế yêu cầu và theo dõi riêng Cellular và Wi-Fi bằng NetworkCallback.
- Tự tìm IPv6 global của Cellular và tự mở lại listener khi địa chỉ IPv6 thay đổi.
- Cổng Gateway có thể cấu hình; mặc định `8080`.
- IP modem có thể cấu hình; mặc định `192.168.1.1`.
- Foreground Service và cơ chế TCP Relay hai chiều.

## Cấu hình và sử dụng
1. Trên Android A, kết nối Wi-Fi modem và bật dữ liệu di động.
2. Nhập IP modem (mặc định `192.168.1.1`).
3. Chọn HTTPS hoặc TELNET:
   - HTTPS: cổng mặc định `443`.
   - TELNET: cổng mặc định `23`.
   Có thể nhập cổng khác nếu thiết bị dùng cổng tùy chỉnh.
4. Giữ cổng TCP Tunnel IPv6 mặc định `8080`, hoặc nhập cổng khác nếu cần.
5. Nhấn **LƯU CẤU HÌNH**, sau đó **KHỞI ĐỘNG GATEWAY**.
6. Chờ ứng dụng phát hiện IPv6 global Cellular và Wi-Fi.
7. Android B kết nối tới IPv6 Cellular của Android A tại cổng Gateway:
   - HTTPS: mở link HTTPS được hiển thị trong ứng dụng.
   - Telnet: mở Telnet client và nhập IPv6 Android A cùng cổng `8080`. Nút sao chép sẽ sao chép lệnh Telnet tương ứng.
8. Nhấn **DỪNG** để đóng listener và các phiên TCP Tunnel.

## Lưu ý bảo mật
- Telnet truyền dữ liệu và thông tin đăng nhập không mã hóa. Chỉ dùng qua mạng tin cậy; nên giới hạn IPv6 nguồn được phép truy cập bằng firewall/chính sách mạng.
- Gateway không có Token; bất kỳ thiết bị nào kết nối được tới IPv6 và cổng Gateway đều có thể tạo TCP Tunnel tới modem.
- HTTPS vẫn đi xuyên suốt từ trình duyệt Android B tới modem. Trình duyệt có thể cảnh báo chứng chỉ tự ký hoặc không khớp địa chỉ truy cập.
- Telnet chỉ hoạt động nếu modem đã bật dịch vụ Telnet và cho phép truy cập từ mạng Wi-Fi/LAN mà Android A đang kết nối.
- IP modem phải là IPv4 nội bộ hợp lệ theo kiểm tra cấu hình hiện có.

## Build
Mở thư mục `IPv6_Modem_Gateway` bằng Android Studio hoặc chạy workflow GitHub Actions hiện có để build `assembleDebug`.
