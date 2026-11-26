package com.miniwms;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final Warehouses warehouses;
    private final QrCodes qrCodes;

    WarehouseApi(WarehouseDb db, Catalog catalog, Accounts accounts, Stock stock, Warehouses warehouses, QrCodes qrCodes,
                 @Value("${wms.public-url}") String publicUrl) {
        this.db = db;
        this.catalog = catalog;
        this.accounts = accounts;
        this.stock = stock;
        this.warehouses = warehouses;
        this.qrCodes = qrCodes;
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

    @PutMapping("/auth/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody Accounts.PasswordChange form,
                        @AuthenticationPrincipal Accounts.Principal user, HttpServletRequest request) {
        accounts.changePassword(user.id(), form);
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

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

    @GetMapping("/warehouses")
    List<Warehouses.Warehouse> warehouses() { return warehouses.list(); }

    @GetMapping("/products")
    List<Catalog.Product> products(@RequestParam(defaultValue="1") long warehouseId) { return catalog.products(warehouseId); }

    @PostMapping("/products")
    Catalog.Product createProduct(@RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Catalog.ProductForm form,
                                  @AuthenticationPrincipal Accounts.Principal user) {
        requireWarehouseForm(warehouseId, form.warehouseId());
        return catalog.saveProduct(null, form, user.id());
    }

    @PutMapping("/products/{id}")
    Catalog.Product editProduct(@PathVariable long id, @RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Catalog.ProductForm form,
                                @AuthenticationPrincipal Accounts.Principal user) {
        requireWarehouseForm(warehouseId, form.warehouseId());
        return catalog.saveProduct(id, form, user.id());
    }

    @GetMapping("/shelves")
    List<Catalog.Shelf> shelves(@RequestParam(defaultValue="1") long warehouseId) { return catalog.shelves(warehouseId); }

    @PostMapping("/shelves")
    Catalog.Shelf createShelf(@RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Catalog.ShelfForm form, @AuthenticationPrincipal Accounts.Principal user) {
        requireWarehouseForm(warehouseId, form.warehouseId());
        return catalog.saveShelf(null, form, user.id());
    }

    @PutMapping("/shelves/{id}")
    Catalog.Shelf editShelf(@PathVariable long id, @RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Catalog.ShelfForm form,
                            @AuthenticationPrincipal Accounts.Principal user) {
        requireWarehouseForm(warehouseId, form.warehouseId());
        return catalog.saveShelf(id, form, user.id());
    }

    private void requireWarehouseForm(long selected, Long submitted) {
        if (selected != Warehouses.id(submitted)) throw ApiError.conflict("资料不属于当前仓库，请重新选择");
    }

    @GetMapping("/slots")
    List<WarehouseDb.Slot> slots(@RequestParam(defaultValue="1") long warehouseId) { warehouses.get(warehouseId); return db.slots(warehouseId); }

    @PutMapping("/slots/{id}")
    WarehouseDb.Slot editSlot(@PathVariable long id, @RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Catalog.SlotForm form,
                             @AuthenticationPrincipal Accounts.Principal user) {
        return catalog.saveSlot(id, form, user.id(), warehouseId);
    }

    @GetMapping("/slots/by-code/{code}")
    WarehouseDb.Slot byCode(@PathVariable String code, @RequestParam(defaultValue="1") long warehouseId) {
        return db.byCode(code, warehouseId);
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
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"slot-" + id + ".png\"")
                .body(qrCodes.png(link));
    }

    @PostMapping("/stock/in")
    Stock.Result in(@RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Stock.Adjust form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.adjust(user.id(), Stock.Type.IN, form, warehouseId);
    }

    @PostMapping("/stock/out")
    Stock.Result out(@RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Stock.Adjust form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.adjust(user.id(), Stock.Type.OUT, form, warehouseId);
    }

    @PostMapping("/stock/transfer")
    Stock.Result move(@RequestParam(defaultValue="1") long warehouseId, @Valid @RequestBody Stock.Transfer form, @AuthenticationPrincipal Accounts.Principal user) {
        return stock.transfer(user.id(), form, warehouseId);
    }
}
