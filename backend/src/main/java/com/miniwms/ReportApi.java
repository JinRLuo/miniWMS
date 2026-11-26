package com.miniwms;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
class ReportApi {
    private final Reports reports;

    ReportApi(Reports reports) { this.reports = reports; }

    @GetMapping("/reports/summary")
    Reports.Summary summary(@RequestParam(defaultValue="1") long warehouseId) { return reports.summary(warehouseId); }

    @GetMapping("/reports/trend")
    List<Reports.Trend> trend(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
                              @RequestParam(defaultValue="DAY") Reports.Period period, @RequestParam(defaultValue="1") long warehouseId,
                              @RequestParam(required=false) String unit, @RequestParam(required=false) String variety,
                              @RequestParam(required=false) Integer headCount) {
        return reports.trend(from, to, period, warehouseId, unit, variety, headCount);
    }

    @GetMapping("/reports/products")
    List<Reports.ProductStats> products(@RequestParam(required=false) LocalDate from,
                                       @RequestParam(required=false) LocalDate to, @RequestParam(defaultValue="1") long warehouseId,
                                       @RequestParam(required=false) String variety, @RequestParam(required=false) Integer headCount) {
        return reports.products(from, to, warehouseId, variety, headCount);
    }

    @GetMapping("/stock/movements")
    Reports.Page<Reports.Movement> movements(@ModelAttribute Reports.Filters filters,
            @RequestParam(defaultValue="1") int page, @AuthenticationPrincipal Accounts.Principal actor) {
        return reports.movements(filters, actor, page);
    }

    @GetMapping("/stock/transfers")
    Reports.Page<Reports.Transfer> transfers(@ModelAttribute Reports.Filters filters,
            @RequestParam(defaultValue="1") int page, @AuthenticationPrincipal Accounts.Principal actor) {
        return reports.transfers(filters, actor, page);
    }

    @GetMapping("/stock/movements.csv")
    ResponseEntity<StreamingResponseBody> export(@ModelAttribute Reports.Filters filters,
            @AuthenticationPrincipal Accounts.Principal actor) {
        var query = reports.exportQuery(filters, actor);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=stock-movements.csv")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(stream -> {
                    var writer = new java.io.OutputStreamWriter(stream, java.nio.charset.StandardCharsets.UTF_8);
                    reports.export(query, writer);
                    writer.flush();
                });
    }
}
