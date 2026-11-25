IPv6 MODEM GATEWAY 0.6.0 - TCP TUNNEL

Gateway không đọc HTTP request/response. Mỗi kết nối inbound tạo một socket đích qua Network Wi-Fi, sau đó chạy hai luồng copy byte nguyên bản.

LOG TAGS:
START / STOP        Người dùng khởi động hoặc dừng Gateway
CELLULAR / WIFI     Trạng thái hai Network riêng
IPV6                IPv6 global Cellular
PORT / SERVER       Listener TCP IPv6
TUNNEL              Kết nối inbound, kết nối modem, hai chiều dữ liệu và đóng phiên
MODEM               Kiểm tra riêng kết nối HTTPS modem qua Wi-Fi
ERROR               Lỗi ở giai đoạn tương ứng

Khi gửi log, sao chép 200 dòng gần nhất sau khi Android B thử mở link HTTPS Tunnel.

Lưu ý: TLS chạy xuyên suốt từ Android B tới modem. Android A không giải mã TLS và không rewrite nội dung. Chứng chỉ modem có thể không khớp IPv6 công khai; trình duyệt B có thể cảnh báo.
