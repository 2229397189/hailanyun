# TokenArk 开发机镜像：JupyterLab + SSH + VNC 桌面（A-10 / A-12）
# 构建：docker build -t tokenark/devbox:1.0 .
FROM python:3.11-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        openssh-server \
        xvfb x11vnc openbox \
        novnc websockify \
        net-tools curl procps vim-tiny \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd /root/.vnc

# JupyterLab（pip 装，避免 apt 版本过旧）
RUN pip install --no-cache-dir "jupyterlab==4.2.5" "notebook==7.2.2"

# SSH：允许 root 用密码登录，改端口留给运行时
RUN echo 'root:tokenark' | chpasswd \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config

# 桌面入口脚本：Xvfb + openbox + x11vnc + noVNC 一次拉起
COPY start-desktop.sh /usr/local/bin/start-desktop.sh
RUN chmod +x /usr/local/bin/start-desktop.sh

EXPOSE 22 8888 6080
CMD ["sleep", "infinity"]
