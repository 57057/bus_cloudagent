package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

/** InvoiceController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class InvoiceController {
  private final InvoiceService invoices;

  public InvoiceController(InvoiceService invoices) {
    this.invoices = invoices;
  }

  @GetMapping("/api/invoices")
  public Object invoices(HttpServletRequest r) {
    return ApiFilter.result(invoices.list(ApiFilter.actor(r), false));
  }

  @PostMapping("/api/invoices")
  public Object invoice(@Valid @RequestBody Invoice d, HttpServletRequest r) {
    return ApiFilter.result(invoices.apply(ApiFilter.actor(r), d));
  }

  @GetMapping("/api/admin/invoices")
  public Object adminInvoices(HttpServletRequest r) {
    return ApiFilter.result(invoices.list(ApiFilter.actor(r), true));
  }

  @PostMapping("/api/admin/invoices/{id}/review")
  public Object invoiceReview(
      @PathVariable String id, @Valid @RequestBody Review d, HttpServletRequest r) {
    return ApiFilter.result(invoices.review(ApiFilter.actor(r), id, d));
  }

  @PostMapping("/api/admin/invoices/{id}/void")
  public Object invoiceVoid(
      @PathVariable String id, @Valid @RequestBody Decision d, HttpServletRequest r) {
    return ApiFilter.result(invoices.voidInvoice(ApiFilter.actor(r), id, d.reason()));
  }

  @GetMapping("/api/admin/reports")
  public Object report(
      @RequestParam LocalDate from, @RequestParam LocalDate to, HttpServletRequest r) {
    return ApiFilter.result(invoices.report(ApiFilter.actor(r), from, to));
  }
}
