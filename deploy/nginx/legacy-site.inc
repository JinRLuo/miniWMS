# Retain old QR paths while rejecting writes from cached clients.
add_header Cache-Control "no-store" always;

location = /api {
    add_header Cache-Control "no-store" always;
    add_header Retry-After 3600 always;
    default_type application/json;
    return 503 '{"message":"本站已迁移，请访问 https://wms.piuiq.com 并重新登录"}';
}

location /api/ {
    add_header Cache-Control "no-store" always;
    add_header Retry-After 3600 always;
    default_type application/json;
    return 503 '{"message":"本站已迁移，请访问 https://wms.piuiq.com 并重新登录"}';
}

location / {
    if ($request_method !~ ^(GET|HEAD)$) { return 503; }
    return 302 https://wms.piuiq.com$request_uri;
}
