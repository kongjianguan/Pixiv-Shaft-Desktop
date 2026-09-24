### 1. Rust ClientHello 字节分析（本地 TLS 服务，域名 localtest.me）
--- enable_sni=true（对照） ---
文件: hello_sni_dns.bin  字节数: 239
  legacy_version: 0303
  扩展数量: 10
  扩展类型列表: [45, 23, 13, 10, 5, 0, 51, 35, 11, 43]
  SNI(server_name, type=0): 出现 1 次 -> [(0, 'localtest.me')]

--- enable_sni=false（目标配置） ---
文件: hello_nosni_dns.bin  字节数: 218
  legacy_version: 0303
  扩展数量: 9
  扩展类型列表: [45, 11, 35, 51, 10, 13, 43, 5, 23]
  SNI(server_name, type=0): 未出现 —— 未发送 SNI


### 2. 真实 pximg CDN（Rust, enable_sni=false + 信任任意证书）
目标 210.140.139.134:443  层内 server_name=i.pximg.net（不发出）
  握手完成: Some(TLSv1_3)
  响应字节数: 295
  状态行: HTTP/1.1 404 Not Found
  头: Server: nginx
  头: Content-Type: text/html
  头: Content-Length: 58
  头: Cache-Control: no-store
  原始响应已写入 resp_210_140_139_134.bin

### 3. 同一 IP 发送 SNI（OpenSSL 对照）
--- Python ssl: 210.140.139.134:443 server_hostname=i.pximg.net
  握手失败: ConnectionResetError: [Errno 54] Connection reset by peer

### 4. 同一 IP 不发送 SNI（OpenSSL 对照）
--- Python ssl: 210.140.139.134:443 server_hostname=None
  握手成功
  协商协议: TLSv1.3
  响应状态行: HTTP/1.1 404 Not Found
  响应头: Server: nginx
  响应头: Content-Type: text/html
  响应头: Content-Length: 58
