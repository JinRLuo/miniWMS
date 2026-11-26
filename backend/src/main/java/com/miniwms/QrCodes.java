package com.miniwms;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
class QrCodes {
    @Cacheable(cacheNames = "qr-images", key = "#link")
    public byte[] png(String link) throws Exception {
        var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320);
        var stream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", stream);
        return stream.toByteArray();
    }
}
