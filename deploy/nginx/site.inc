root /opt/miniwms/current/public;
index index.html;

add_header X-Content-Type-Options nosniff always;
add_header Referrer-Policy same-origin always;
add_header X-Frame-Options DENY always;

location /api/ {
    proxy_pass http://127.0.0.1:18089;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Prefix "";
    proxy_set_header Forwarded "";
    proxy_connect_timeout 5s;
    proxy_read_timeout 60s;
}

location /assets/ {
    gzip on;
    gzip_vary on;
    gzip_types text/css application/javascript image/svg+xml;
    gzip_min_length 1024;
    gzip_comp_level 5;
    try_files $uri =404;
    expires 1y;
}

location = /index.html {
    expires -1;
}

location / {
    try_files $uri $uri/ /index.html;
}
