package com.miniwms;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api")
class WarehouseApi {
    private final WarehouseDb db;
    private final Catalog catalog;
    private final Accounts accounts;
    private final Stock stock;
    private final String publicUrl;

    WarehouseApi(WarehouseDb db, Catalog catalog, Accounts accounts, Stock stock,
                 @Value("${wms.public-url}") String publicUrl) {
        this.db = db;
        this.catalog = catalog;
        this.accounts = accounts;
        this.stock = stock;
        this.publicUrl = publicUrl;
        if (!publicUrl.isBlank()) {
            URI origin = URI.create(publicUrl);
            if (!List.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                    || origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !List.of("", "/").contains(origin.getPath())) {
                throw new IllegalArgumentException("WMS_PUBLIC_URL 必须是站点根地址");
            }
        }
    }

    @GetMapping("/auth/csrf")
    CsrfToken csrf(CsrfToken token) { return token; }

    @GetMapping("/auth/me")
    Accounts.View me(@AuthenticationPrincipal Accounts.Principal user) { return user.view(); }

    @GetMapping("/admin/users")
    List<Accounts.View> users() { return accounts.list(); }

    @PostMapping("/admin/users")
    Accounts.View createUser(@Valid @RequestBody Accounts.Form form, @AuthenticationPrincipal Accounts.Principal user) {
        return accounts.save(null, form, user.id());
    }

    @PutMapping("/admin/users/{id}")
    Accounts.View updateUser(@PathVariable long id, @Valid @RequestBody Accounts.Form form,
                             @AuthenticationPrincipal Accounts.Principal user) {
        return accounts.save(id, form, user.id());
    }

    @GetMapping("/products")
    List<Catalog.Product> products() { return catalog.products(); }

    @PostMapping("/products")
    Catalog.Product createProduct(@Valid @RequestBody Catalog.ProductForm form,
                                  @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveProduct(null, form, user.id());
    }

    @PutMapping("/products/{id}")
    Catalog.Product editProduct(@PathVariable long id, @Valid @RequestBody Catalog.ProductForm form,
                                @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveProduct(id, form, user.id());
    }

    @GetMapping("/shelves")
    List<Catalog.Shelf> shelves() { return catalog.shelves(); }

    @PostMapping("/shelves")
    Catalog.Shelf createShelf(@Valid @RequestBody Catalog.ShelfForm form, @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveShelf(null, form, user.id());
    }

    @PutMapping("/shelves/{id}")
    Catalog.Shelf editShelf(@PathVariable long id, @Valid @RequestBody Catalog.ShelfForm form,
                            @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveShelf(id, form, user.id());
    }

    @GetMapping("/slots")
    List<WarehouseDb.Slot> slots() { return db.slots(); }

    @PutMapping("/slots/{id}")
    WarehouseDb.Slot editSlot(@PathVariable long id, @Valid @RequestBody Catalog.SlotForm form,
                             @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveSlot(id, form, user.id());
    }

    @GetMapping("/slots/resolve/{token}")
    WarehouseDb.Slot resolve(@PathVariable UUID token) { return db.resolve(token); }

    @GetMapping("/slots/{id}/qrcode")
    ResponseEntity<byte[]> qr(@PathVariable long id, HttpServletRequest request) throws Exception {
        var token = db.one("SELECT qr_token FROM shelf_slots WHERE id=?", (r, n) -> r.getString(1), "货架层不存在", id);
        UriComponentsBuilder uri = publicUrl.isBlank()
                ? UriComponentsBuilder.newInstance().scheme(request.getScheme()).host(request.getServerName())
                    .port(request.getServerPort())
                : UriComponentsBuilder.fromUriString(publicUrl).replacePath("");
        String link = uri.pathSegment("m", "slot", token).build().toUriString();
        var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320);
        var stream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", stream);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"slot-" + id + ".png\"")
                .body(stream.toByteArray());
    }

    @PostMapping("/stock/in")
    Stock.Result in(@Valid @RequestBody Stock.Adjust form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.adjust(user.id(), Stock.Type.IN, form);
    }

    @PostMapping("/stock/out")
    Stock.Result out(@Valid @RequestBody Stock.Adjust form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.adjust(user.id(), Stock.Type.OUT, form);
    }

    @PostMapping("/stock/transfer")
    Stock.Result move(@Valid @RequestBody Stock.Transfer form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.transfer(user.id(), form);
    }
}
