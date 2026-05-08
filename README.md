# 广东城市天气采集网站（墨迹天气）

这是一个 Flask 小网站：
- 自动抓取广东 21 个地级市的天气（最高温 / 最低温）
- 保存近 14 天数据（SQLite）
- 提供网页看板与 API

---

## 你这次“找不到文件”的根因

你截图里的命令是：

```bash
sudo git clone <https://github.com/alexwilliamclerk/website.git> weather-site
```

这里的 `<` 和 `>` 在 shell 里会被当作**重定向符号**，不是普通字符，导致 git 实际没有按你预期执行，所以后续 `/opt/weather-site` 目录不存在。

另外你日志里也出现了这句：

```bash
-bash: https://github.com/alexwilliamclerk/website.git: No such file or directory
```

这也说明 shell 把 URL 当成了“文件路径”处理。

✅ 正确写法（不要带尖括号）：

```bash
cd /opt
sudo git clone https://github.com/alexwilliamclerk/website.git weather-site
cd /opt/weather-site
```

---

## 一键可复制部署流程（Ubuntu 22.04+）

> 以下命令默认你使用 `root`，如果是普通用户请加 `sudo`。

### 1) 安装系统依赖

```bash
apt update
apt install -y python3 python3-venv python3-pip nginx
```

### 2) 拉取项目

```bash
cd /opt
git clone https://github.com/alexwilliamclerk/website.git weather-site
cd /opt/weather-site
```

### 3) 创建虚拟环境并安装 Python 依赖

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -U pip
pip install -r requirements.txt
```

### 4) 本地先跑通（先验证）

```bash
python app.py
```

看到 Flask 启动后，在服务器本机测试：

```bash
curl http://127.0.0.1:5000/api/weather/latest
```

按 `Ctrl + C` 停止。

### 5) 用 Gunicorn 托管

先安装 Gunicorn：

```bash
source /opt/weather-site/.venv/bin/activate
pip install gunicorn
```

创建 systemd 文件：

```bash
cat >/etc/systemd/system/weather-site.service <<'SERVICE'
[Unit]
Description=Guangdong Weather Flask App
After=network.target

[Service]
User=root
WorkingDirectory=/opt/weather-site
Environment="PATH=/opt/weather-site/.venv/bin"
ExecStart=/opt/weather-site/.venv/bin/gunicorn -w 2 -b 127.0.0.1:8000 app:app
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
SERVICE
```

启动并开机自启：

```bash
systemctl daemon-reload
systemctl enable --now weather-site
systemctl status weather-site --no-pager
```

### 6) 配置 Nginx 反向代理

```bash
cat >/etc/nginx/sites-available/weather-site <<'NGINX'
server {
    listen 80;
    server_name your-domain.com;  # 改成你的域名或公网 IP

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX

ln -sf /etc/nginx/sites-available/weather-site /etc/nginx/sites-enabled/weather-site
nginx -t
systemctl reload nginx
```

### 7) 防火墙放行（如果启用了 UFW）

```bash
ufw allow OpenSSH
ufw allow 'Nginx Full'
ufw status
```

---

## HTTPS（可选但推荐）

如果你有域名并已经把 DNS 解析到服务器：

```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d your-domain.com
```

测试自动续期：

```bash
certbot renew --dry-run
```

---

## 常见问题排查

### 1) `cd: /opt/weather-site: No such file or directory`

先看目录是否真的存在：

```bash
ls -lah /opt
```

若没有 `weather-site`，重新执行正确的 `git clone`（不要带 `< >`）。

### 2) 端口通但网页 502

通常是 Gunicorn 没起来：

```bash
systemctl status weather-site --no-pager
journalctl -u weather-site -n 100 --no-pager
```

### 3) Nginx 配置有误

```bash
nginx -t
journalctl -u nginx -n 100 --no-pager
```

---

## 项目结构

```text
app.py
requirements.txt
data/guangdong_cities.json
templates/index.html
static/style.css
static/app.js
```

---

## 说明

- 采集目标来源：墨迹天气页面（`tianqi.moji.com`）
- 数据库存储：SQLite（默认在项目目录生成）
- 保留策略：仅保留近 14 天

