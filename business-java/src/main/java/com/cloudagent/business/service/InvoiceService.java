package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.dto.P1Responses.ReportSummary;
import com.cloudagent.business.repository.InvoiceRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 发票申请工作流与运营报表；本地开票标为模拟处理，不伪造有效税务发票。 */
@Service
public class InvoiceService {
  private final InvoiceRepository r;
  private final BusinessEvents events;

  public InvoiceService(InvoiceRepository r, BusinessEvents events) {
    this.r = r;
    this.events = events;
  }

  public Object list(AuthService.Actor a, boolean staff) {
    if (staff) a.staffOnly();
    return r.invoices(staff ? null : a.id());
  }

  @Transactional
  public Object apply(AuthService.Actor a, Invoice d) {
    var o = r.lockOrder(d.order_id(), a.id());
    long net = num(o, "total_cents") - num(o, "refunded_cents");
    require(
        r.paid(d.order_id()) == 1 && net > 0 && num(o, "refund_held_cents") == 0,
        409,
        "INVOICE_UNAVAILABLE",
        "未支付、无可开票金额或有售后占用");
    var old = r.byOrder(d.order_id());
    if (!old.isEmpty())
      require(
          Set.of("REJECTED", "VOID").contains(text(old.get(0), "state")),
          409,
          "INVOICE_EXISTS",
          "已有有效发票申请");
    r.apply(id(), a.id(), d.order_id(), d.title(), d.tax_number(), net, now());
    events.audit(a.id(), "INVOICE_APPLY", d.order_id(), "金额分：" + net);
    return r.byOrder(d.order_id()).get(0);
  }

  @Transactional
  public Object review(AuthService.Actor a, String id, Review d) {
    a.staffOnly();
    var first = r.findInvoice(id);
    var order = r.order(text(first, "order_id"));
    var i = r.lockInvoice(id);
    require(text(i, "state").equals("SUBMITTED"), 409, "INVOICE_STATE", "仅待审核申请可处理");
    if (d.approve()) {
      require(
          num(order, "total_cents") - num(order, "refunded_cents") == num(i, "amount_cents")
              && num(order, "refund_held_cents") == 0,
          409,
          "INVOICE_AMOUNT_CHANGED",
          "订单退款金额已变化，请拒绝后重申");
      require(
          d.reference() != null && d.reference().startsWith("SIM-"),
          400,
          "SIMULATED_INVOICE",
          "本地只接受SIM-开头的模拟处理编号");
    }
    r.review(id, d.approve() ? "ISSUED" : "REJECTED", d.reference(), d.reason());
    events.audit(a.id(), "SIMULATED_INVOICE_REVIEW", id, d.reason());
    return r.lockInvoice(id);
  }

  @Transactional
  public Object voidInvoice(AuthService.Actor a, String id, String reason) {
    a.staffOnly();
    var i = r.lockInvoice(id);
    require(
        Set.of("NEEDS_RED", "ISSUED").contains(text(i, "state")),
        409,
        "INVOICE_STATE",
        "发票不在可红冲状态");
    r.review(id, "VOID", null, reason);
    events.audit(a.id(), "SIMULATED_INVOICE_VOID", id, reason);
    return r.lockInvoice(id);
  }

  public ReportSummary report(AuthService.Actor a, LocalDate from, LocalDate to) {
    a.staffOnly();
    require(
        !to.isBefore(from)
            && Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays() <= 366,
        400,
        "INVALID_RANGE",
        "报表最多查询366天");
    var start = time(from.atStartOfDay(ZoneOffset.UTC).toInstant());
    var end = time(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    var p = r.paymentReport(start, end);
    var f = r.refundReport(start, end);
    return new ReportSummary(
        p,
        f,
        num(p, "paid_cents") - num(f, "refunded_cents"),
        r.sales(start, end),
        "UTC创建时间；按当前最终支付/退款状态聚合",
        "SIMULATED");
  }
}
