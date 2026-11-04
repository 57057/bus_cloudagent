import React, { useEffect, useState } from "react";
import { api, money, date, parse, Row } from "./api";
type Ctx = {
  user: Row | null;
  notice: (s: string) => void;
  run: (f: () => Promise<void>) => void;
  login: () => void;
};
function useRows(path: string, poll = 0) {
  const [data, setData] = useState<any>(null),
    [error, setError] = useState(""),
    [version, bump] = useState(0);
  useEffect(() => {
    let active = true;
    const load = () =>
      api(path)
        .then((d) => {
          if (active) {
            setData(d);
            setError("");
          }
        })
        .catch((e) => active && setError(e.message));
    load();
    const timer = poll ? setInterval(load, poll) : undefined;
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [path, version, poll]);
  return { data, error, reload: () => bump((n) => n + 1) };
}
const ErrorBox = ({ text }: { text: string }) =>
  text ? (
    <p className="error" role="alert">
      {text}
    </p>
  ) : null;
const localInput = (v: number | string = Date.now()) => {
  const d = new Date(v);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};
const field = (
  label: string,
  name: string,
  type = "text",
  value?: string | number,
) => (
  <label className="field">
    <span>{label}</span>
    <input
      name={name}
      type={type}
      defaultValue={value}
      required
      maxLength={type === "text" ? 150 : undefined}
    />
  </label>
);
function Rows({
  rows,
  columns,
  actions,
}: {
  rows: Row[];
  columns: [string, string][];
  actions?: (row: Row) => React.ReactNode;
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map(([k, t]) => (
              <th key={k}>{t}</th>
            ))}
            {actions && <th>操作</th>}
          </tr>
        </thead>
        <tbody>
          {(rows || []).map((row, i) => (
            <tr key={row.id || i}>
              {columns.map(([k]) => (
                <td key={k}>
                  {k.endsWith("_cents")
                    ? money(row[k])
                    : k.endsWith("_at")
                      ? date(row[k])
                      : typeof row[k] === "boolean"
                        ? row[k]
                          ? "是"
                          : "否"
                        : String(row[k] ?? "—")}
                </td>
              ))}
              {actions && <td>{actions(row)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
      {!rows?.length && <p className="muted">暂无记录</p>}
    </div>
  );
}
function JsonView({ data }: { data: any }) {
  return <pre className="p1-json">{JSON.stringify(data, null, 2)}</pre>;
}

/** 可复用的明确确认卡；仅浏览器按钮可确认，模型工具没有此接口。 */
function PreviewSummary({ data }: { data: Row }) {
  const config = parse(data.config),
    snapshot = parse(data.snapshot);
  return (
    <dl>
      <dt>应付 / 预计退款</dt>
      <dd>{money(data.total_cents ?? data.amount_cents)}</dd>
      {data.total_cents !== undefined && (
        <>
          <dt>产品规格</dt>
          <dd>
            {snapshot.product_name || snapshot.name} · {snapshot.cpu}核 /{" "}
            {snapshot.memory_gb}GB
          </dd>
          <dt>租期和数量</dt>
          <dd>
            {data.months}个月 × {data.quantity}
          </dd>
          <dt>资源配置</dt>
          <dd>
            {config.image} · 磁盘{config.disk_gb}GB · {config.bandwidth}Mbps
          </dd>
        </>
      )}
      <dt>退款规则</dt>
      <dd>{snapshot.refund_rule || data.rule || "以订单快照为准"}</dd>
    </dl>
  );
}
export function ActionInbox({ ctx }: { ctx: Ctx }) {
  const rows = useRows("/assistant/actions", 4000);
  return (
    <section className="panel">
      <h2>待确认的辅助办理</h2>
      <p className="muted">
        核对配置、金额和有效期后确认。购买/续费确认只创建订单，仍需你到收银台付款。
      </p>
      <ErrorBox text={rows.error} />
      {(rows.data || []).slice(0, 8).map((p: Row) => (
        <article className="p1-card" key={p.id}>
          <strong>
            {{ PURCHASE: "购买", RENEWAL: "续费", REFUND: "退款" }[
              p.action as string
            ] || p.action}{" "}
            · {p.state}
          </strong>
          <p>有效至 {date(p.expires_at)}</p>
          <PreviewSummary data={parse(p.preview)} />
          <button
            disabled={
              p.state !== "PENDING" ||
              new Date(p.expires_at).getTime() < Date.now()
            }
            onClick={() =>
              ctx.run(async () => {
                await api(`/assistant/actions/${p.id}/confirm`, "POST", {
                  confirmed: true,
                });
                ctx.notice(
                  p.action === "REFUND"
                    ? "退款申请已提交，等待审核"
                    : "订单已创建，请前往我的订单付款",
                );
                rows.reload();
              })
            }
          >
            已核对，确认{p.action === "REFUND" ? "提交退款申请" : "创建订单"}
          </button>
        </article>
      ))}
    </section>
  );
}
export function PasswordReset({ close }: { close: () => void }) {
  const [challenge, setChallenge] = useState(""),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <section>
      <h3>找回密码</h3>
      <p className="muted">
        必须使用账号已验证的邮箱或手机号。本地模拟验证码由管理员在沙箱投递箱查看。
      </p>
      <ErrorBox text={error} />
      <p>{message}</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          const d = Object.fromEntries(new FormData(e.currentTarget));
          try {
            if (!challenge) {
              const r = await api("/auth/password-reset/request", "POST", d);
              setChallenge(r.challenge_id);
              setMessage(r.message);
            } else {
              await api("/auth/password-reset/confirm", "POST", {
                ...d,
                challenge_id: challenge,
              });
              setMessage("密码已重置，请使用新密码登录");
              setChallenge("");
            }
          } catch (e: any) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        {!challenge ? (
          <>
            <label className="field">
              <span>已绑定渠道</span>
              <select name="channel">
                <option>EMAIL</option>
                <option>SMS</option>
              </select>
            </label>
            {field("已验证的邮箱 / 手机号", "destination")}
          </>
        ) : (
          <>
            {field("六位验证码", "code")}
            {field("新密码（至少10位字母与数字）", "new_password", "password")}
          </>
        )}
        <div className="actions">
          <button disabled={busy}>
            {challenge ? "验证并重置密码" : "发送验证码"}
          </button>
          <button type="button" className="ghost" onClick={close}>
            返回登录
          </button>
        </div>
      </form>
    </section>
  );
}
export function P1UserPage({ ctx }: { ctx: Ctx }) {
  const [tab, setTab] = useState("coupons");
  return (
    <>
      <h1>费用与服务</h1>
      <p className="muted">
        优惠券、发票、推广、自动续费、联系方式及辅助办理。
      </p>
      <div className="tabs">
        {[
          ["coupons", "优惠券"],
          ["invoices", "发票申请"],
          ["promotion", "推广佣金"],
          ["renewals", "自动续费"],
          ["contact", "验证联系方式"],
          ["actions", "辅助办理"],
        ].map(([id, title]) => (
          <button
            key={id}
            className={tab === id ? "" : "ghost"}
            onClick={() => setTab(id)}
          >
            {title}
          </button>
        ))}
      </div>
      {tab === "actions" ? (
        <ActionInbox ctx={ctx} />
      ) : tab === "contact" ? (
        <ContactPanel ctx={ctx} />
      ) : tab === "promotion" ? (
        <PromotionPanel ctx={ctx} />
      ) : tab === "renewals" ? (
        <RenewalPanel ctx={ctx} />
      ) : tab === "invoices" ? (
        <InvoicePanel ctx={ctx} />
      ) : (
        <CouponPanel ctx={ctx} />
      )}
    </>
  );
}
function CouponPanel({ ctx }: { ctx: Ctx }) {
  const rows = useRows("/coupons");
  return (
    <section className="panel">
      <h2>我的优惠券</h2>
      <form
        className="p1-form"
        onSubmit={(e) => {
          e.preventDefault();
          const d = Object.fromEntries(new FormData(e.currentTarget));
          ctx.run(async () => {
            await api("/coupons/claim", "POST", d);
            rows.reload();
            ctx.notice("领取成功，可在产品选配时使用");
          });
        }}
      >
        {field("兑换码", "code")}
        <button>领取优惠券</button>
      </form>
      <ErrorBox text={rows.error} />
      <Rows
        rows={rows.data}
        columns={[
          ["id", "券编号"],
          ["name", "名称"],
          ["minimum_cents", "满额门槛"],
          ["discount_cents", "优惠额"],
          ["state", "状态"],
          ["ends_at", "失效时间"],
        ]}
      />
    </section>
  );
}
function InvoicePanel({ ctx }: { ctx: Ctx }) {
  const rows = useRows("/invoices"),
    orders = useRows("/orders?size=100");
  return (
    <section className="panel">
      <h2>发票申请</h2>
      <p className="muted">
        按实付减已退款金额申请。本地仅演示申请、审核和红冲流程，不生成有效税务发票。
      </p>
      <form
        className="p1-form"
        onSubmit={(e) => {
          e.preventDefault();
          const d = Object.fromEntries(new FormData(e.currentTarget));
          ctx.run(async () => {
            await api("/invoices", "POST", d);
            rows.reload();
            ctx.notice("申请已提交");
          });
        }}
      >
        <label className="field">
          <span>订单</span>
          <select name="order_id" required>
            <option value="">请选择已付款订单</option>
            {(orders.data?.items || []).map((o: Row) => (
              <option key={o.id} value={o.id}>
                {o.id.slice(0, 12)} · {money(o.total_cents)} · {o.state}
              </option>
            ))}
          </select>
        </label>
        {field("抬头", "title")}
        {field("税号（个人填PERSONAL）", "tax_number", "text", "PERSONAL")}
        <button>提交申请</button>
      </form>
      <ErrorBox text={rows.error} />
      <Rows
        rows={rows.data}
        columns={[
          ["id", "编号"],
          ["order_id", "订单"],
          ["title", "抬头"],
          ["amount_cents", "金额"],
          ["state", "状态"],
          ["invoice_number", "模拟处理编号"],
          ["review_reason", "处理说明"],
        ]}
      />
    </section>
  );
}
function PromotionPanel({ ctx }: { ctx: Ctx }) {
  const campaigns = useRows("/promotion/campaigns"),
    ledger = useRows("/promotion/commissions");
  const [link, setLink] = useState("");
  return (
    <section className="panel">
      <h2>推广与佣金</h2>
      <p className="muted">
        禁止自购返佣。支付后记账，退款追加冲正，8天冻结期后可由管理员模拟结算；不进行真实资金转账。
      </p>
      <ErrorBox text={campaigns.error || ledger.error} />
      {link && (
        <p>
          专属链接：<a href={link}>{location.origin + link}</a>
        </p>
      )}
      <Rows
        rows={campaigns.data}
        columns={[
          ["title", "活动"],
          ["product_id", "产品"],
          ["rate_bps", "佣金万分比"],
          ["version", "版本"],
        ]}
        actions={(c) => (
          <button
            onClick={() =>
              ctx.run(async () => {
                const d = await api(
                  `/promotion/campaigns/${c.id}/links`,
                  "POST",
                );
                setLink(d.path);
              })
            }
          >
            生成专属链接
          </button>
        )}
      />
      <h3>佣金流水</h3>
      <Rows
        rows={ledger.data}
        columns={[
          ["order_id", "订单"],
          ["kind", "类型"],
          ["amount_cents", "变动"],
          ["settled", "已结算"],
          ["created_at", "时间"],
        ]}
      />
    </section>
  );
}
function RenewalPanel({ ctx }: { ctx: Ctx }) {
  const rows = useRows("/auto-renewals", 5000),
    instances = useRows("/instances?size=100");
  return (
    <section className="panel">
      <h2>逐实例自动续费授权</h2>
      <p className="muted">
        仅模拟自动付款，到期前3天触发。单次报价超过授权上限则停止并通知。取消授权阻止后续周期，已创建的本期付款可能仍会完成。
      </p>
      <form
        className="p1-form"
        onSubmit={(e) => {
          e.preventDefault();
          const d: any = Object.fromEntries(new FormData(e.currentTarget));
          ctx.run(async () => {
            await api(`/instances/${d.instance_id}/auto-renewal`, "PUT", {
              enabled: true,
              months: Number(d.months),
              max_cents: Math.round(Number(d.max_yuan) * 100),
              confirmed: d.confirmed === "on",
            });
            rows.reload();
            ctx.notice("模拟自动续费已授权");
          });
        }}
      >
        <label className="field">
          <span>实例</span>
          <select name="instance_id" required>
            {(instances.data?.items || [])
              .filter((r: Row) => r.state !== "RELEASED")
              .map((r: Row) => (
                <option key={r.id} value={r.id}>
                  {r.name} · {r.id.slice(0, 8)}
                </option>
              ))}
          </select>
        </label>
        <label className="field">
          <span>续费周期</span>
          <select name="months">
            <option value="1">1个月</option>
            <option value="3">3个月</option>
            <option value="12">12个月</option>
          </select>
        </label>
        {field("单次金额上限（元）", "max_yuan", "number", 1000)}
        <label>
          <input type="checkbox" name="confirmed" required />
          我明确授权在金额上限内模拟扣款
        </label>
        <button>开启 / 更新授权</button>
      </form>
      <ErrorBox text={rows.error} />
      <Rows
        rows={rows.data}
        columns={[
          ["instance_id", "实例"],
          ["enabled", "已授权"],
          ["months", "月数"],
          ["max_cents", "单次上限"],
          ["state", "状态"],
          ["error", "失败说明"],
        ]}
        actions={(r) => (
          <button
            className="ghost"
            disabled={!r.enabled}
            onClick={() =>
              ctx.run(async () => {
                await api(`/instances/${r.instance_id}/auto-renewal`, "PUT", {
                  enabled: false,
                  months: r.months,
                  max_cents: r.max_cents,
                  confirmed: false,
                });
                rows.reload();
              })
            }
          >
            取消授权
          </button>
        )}
      />
    </section>
  );
}
function ContactPanel({ ctx }: { ctx: Ctx }) {
  const rows = useRows("/me/contact");
  const [challenge, setChallenge] = useState("");
  return (
    <section className="panel">
      <h2>验证联系方式</h2>
      <p className="muted">
        验证后用于找回密码和外部业务通知。本地模式不会发送真实邮件/短信，验证码仅在管理员沙箱投递箱可见，10分钟失效。
      </p>
      <Rows
        rows={rows.data}
        columns={[
          ["channel", "渠道"],
          ["destination", "已验证地址"],
          ["verified_at", "验证时间"],
        ]}
      />
      <form
        className="p1-form"
        onSubmit={(e) => {
          e.preventDefault();
          const d = Object.fromEntries(new FormData(e.currentTarget));
          ctx.run(async () => {
            const value = await api("/me/contact/request", "POST", d);
            setChallenge(value.challenge_id);
            ctx.notice(value.message);
          });
        }}
      >
        <label className="field">
          <span>渠道</span>
          <select name="channel">
            <option>EMAIL</option>
            <option>SMS</option>
          </select>
        </label>
        {field("邮箱 / 手机号", "destination")}
        <button>发送验证码</button>
      </form>
      {challenge && (
        <form
          className="p1-form"
          onSubmit={(e) => {
            e.preventDefault();
            const d = Object.fromEntries(new FormData(e.currentTarget));
            ctx.run(async () => {
              await api("/me/contact/verify", "POST", {
                ...d,
                challenge_id: challenge,
              });
              setChallenge("");
              rows.reload();
              ctx.notice("验证成功");
            });
          }}
        >
          {field("六位验证码", "code")}
          <button>验证并绑定</button>
        </form>
      )}
      <ErrorBox text={rows.error} />
    </section>
  );
}

const adminTabs = [
  ["coupons", "优惠券"],
  ["invoices", "发票审核"],
  ["campaigns", "推广活动"],
  ["commissions", "佣金结算"],
  ["knowledge", "知识发布"],
  ["reports", "运营报表"],
  ["deliveries", "外部通知"],
  ["providers", "渠道接入"],
];
export function P1AdminPage({ ctx }: { ctx: Ctx }) {
  const [tab, setTab] = useState("coupons"),
    [edit, setEdit] = useState<Row | null>(null),
    [details, setDetails] = useState<any>(null);
  const rows = useRows(
    tab === "reports"
      ? "/admin/reports?from=" +
          new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10) +
          "&to=" +
          new Date().toISOString().slice(0, 10)
      : "/admin/" + tab,
  );
  const mutate = async (path: string, data: any, method = "POST") => {
    const value = await api(path, method, data);
    rows.reload();
    ctx.notice("操作成功");
    return value;
  };
  const decision = (path: string, action: string, reference?: string) =>
    ctx.run(async () => {
      const reason = prompt("请输入操作原因");
      if (!reason) return;
      await mutate(
        path,
        { action, reason, approve: action === "APPROVE", reference },
        path.startsWith("/admin/coupons/") ? "PATCH" : "POST",
      );
    });
  return (
    <>
      <h1>P1 运营中心</h1>
      <a href="#/admin">← P0 管理工作台</a>
      <div className="tabs">
        {adminTabs.map(([id, title]) => (
          <button
            key={id}
            className={tab === id ? "" : "ghost"}
            onClick={() => {
              setTab(id);
              setEdit(null);
              setDetails(null);
            }}
          >
            {title}
          </button>
        ))}
      </div>
      <ErrorBox text={rows.error} />
      <section className="panel">
        {["coupons", "campaigns", "knowledge", "providers"].includes(tab) && (
          <button onClick={() => setEdit({})}>新建 / 配置</button>
        )}
        {tab === "reports" ? (
          <ReportView />
        ) : tab === "providers" ? (
          <>
            <p className="muted">
              密钥通过 business-java/.env
              配置，页面不收集密钥。支付宝只允许沙箱，阿里云只开放DryRun，不创建收费资源。
            </p>
            <JsonView data={rows.data} />
            <button
              onClick={() =>
                ctx.run(async () =>
                  setDetails(
                    await api("/admin/providers/aliyun/dry-run", "POST"),
                  ),
                )
              }
            >
              执行阿里云 DryRun 预检
            </button>
          </>
        ) : tab === "deliveries" ? (
          <>
            <Rows
              rows={rows.data}
              columns={[
                ["id", "任务"],
                ["channel", "渠道"],
                ["state", "状态"],
                ["attempts", "尝试次数"],
                ["error", "错误"],
              ]}
              actions={(r) => (
                <button
                  disabled={r.state !== "MANUAL_REQUIRED"}
                  onClick={() =>
                    decision(`/admin/deliveries/${r.id}/retry`, "RETRY")
                  }
                >
                  重试
                </button>
              )}
            />
            {ctx.user?.role === "ADMIN" && (
              <button
                onClick={() =>
                  ctx.run(async () =>
                    setDetails(await api("/admin/deliveries/inbox")),
                  )
                }
              >
                查看本地沙箱收件箱（管理员）
              </button>
            )}
          </>
        ) : tab === "knowledge" ? (
          <Rows
            rows={rows.data}
            columns={[
              ["title", "标题"],
              ["product_id", "产品"],
              ["version", "版本"],
              ["state", "状态"],
              ["author_id", "作者"],
              ["valid_until", "有效期"],
            ]}
            actions={(r) => (
              <div className="actions">
                <button
                  className="ghost"
                  onClick={() =>
                    ctx.run(async () =>
                      setEdit(await api(`/admin/knowledge/${r.id}`)),
                    )
                  }
                >
                  创建修订版
                </button>
                {["DRAFT", "REJECTED"].includes(r.state) && (
                  <button
                    onClick={() =>
                      decision(`/admin/knowledge/${r.id}/transition`, "SUBMIT")
                    }
                  >
                    提交审核
                  </button>
                )}
                {r.state === "SUBMITTED" && (
                  <>
                    <button
                      onClick={() =>
                        decision(
                          `/admin/knowledge/${r.id}/transition`,
                          "PUBLISH",
                        )
                      }
                    >
                      审核并发布
                    </button>
                    <button
                      onClick={() =>
                        decision(
                          `/admin/knowledge/${r.id}/transition`,
                          "REJECT",
                        )
                      }
                    >
                      退回
                    </button>
                  </>
                )}
                {r.state === "PUBLISHED" && (
                  <button
                    onClick={() =>
                      decision(`/admin/knowledge/${r.id}/transition`, "RETIRE")
                    }
                  >
                    下架
                  </button>
                )}
              </div>
            )}
          />
        ) : tab === "coupons" ? (
          <Rows
            rows={rows.data}
            columns={[
              ["code", "兑换码"],
              ["name", "名称"],
              ["discount_cents", "优惠额"],
              ["minimum_cents", "门槛"],
              ["capacity", "发放总量"],
              ["active", "启用"],
            ]}
            actions={(r) => (
              <button
                onClick={() =>
                  decision(
                    `/admin/coupons/${r.id}`,
                    r.active ? "DISABLE" : "ENABLE",
                  )
                }
              >
                启用/停用
              </button>
            )}
          />
        ) : tab === "invoices" ? (
          <Rows
            rows={rows.data}
            columns={[
              ["id", "申请"],
              ["title", "抬头"],
              ["amount_cents", "金额"],
              ["state", "状态"],
            ]}
            actions={(r) =>
              r.state === "SUBMITTED" ? (
                <>
                  <button
                    onClick={() => {
                      const reference = prompt("模拟处理编号（SIM-开头）");
                      if (reference)
                        decision(
                          `/admin/invoices/${r.id}/review`,
                          "APPROVE",
                          reference,
                        );
                    }}
                  >
                    通过（模拟）
                  </button>
                  <button
                    onClick={() =>
                      decision(`/admin/invoices/${r.id}/review`, "REJECT")
                    }
                  >
                    拒绝
                  </button>
                </>
              ) : ["ISSUED", "NEEDS_RED"].includes(r.state) ? (
                <button
                  onClick={() =>
                    decision(`/admin/invoices/${r.id}/void`, "VOID")
                  }
                >
                  模拟红冲
                </button>
              ) : null
            }
          />
        ) : tab === "campaigns" ? (
          <Rows
            rows={rows.data}
            columns={[
              ["title", "活动"],
              ["product_id", "产品"],
              ["rate_bps", "佣金万分比"],
              ["version", "版本"],
              ["active", "启用"],
            ]}
            actions={(r) => (
              <button onClick={() => setEdit(r)}>修订活动</button>
            )}
          />
        ) : (
          <Rows
            rows={rows.data}
            columns={[
              ["order_id", "订单"],
              ["user_id", "推广者"],
              ["kind", "类型"],
              ["amount_cents", "变动"],
              ["settled", "已结算"],
            ]}
            actions={(r) => (
              <button
                disabled={r.settled}
                onClick={() =>
                  decision(`/admin/commissions/${r.order_id}/settle`, "SETTLE")
                }
              >
                模拟结算
              </button>
            )}
          />
        )}
        {details && (
          <>
            <button className="ghost" onClick={() => setDetails(null)}>
              关闭详情
            </button>
            <JsonView data={details} />
          </>
        )}
      </section>
      {edit && (
        <section className="panel">
          <h2>
            {tab === "knowledge"
              ? "新知识草稿 / 修订版"
              : tab === "providers"
                ? "非秘密渠道配置"
                : "规则配置"}
          </h2>
          <form
            key={tab + edit.id}
            className="p1-form"
            onSubmit={(e) => {
              e.preventDefault();
              const d: any = Object.fromEntries(new FormData(e.currentTarget));
              for (const k of [
                "minimum_cents",
                "discount_cents",
                "capacity",
                "rate_bps",
              ])
                if (k in d) d[k] = Number(d[k]);
              if ("active" in d) d.active = d.active === "true";
              if (tab === "knowledge") {
                if (edit.id) d.id = edit.id;
                d.valid_until = new Date(d.valid_until).toISOString();
              }
              if (tab === "coupons") {
                d.starts_at = new Date(d.starts_at).toISOString();
                d.ends_at = new Date(d.ends_at).toISOString();
              }
              if (tab === "campaigns" && edit.id) d.id = edit.id;
              if (tab === "providers") d.settings = JSON.parse(d.settings);
              ctx.run(async () => {
                await mutate(
                  "/admin/" + tab,
                  d,
                  tab === "providers" ? "PUT" : "POST",
                );
                setEdit(null);
              });
            }}
          >
            {tab === "coupons" ? (
              <>
                {field("兑换码", "code")}
                {field("名称", "name")}
                {field("满额门槛（分）", "minimum_cents", "number", 1000)}
                {field("优惠额（分）", "discount_cents", "number", 100)}
                {field("总发放量", "capacity", "number", 100)}
                {field("生效时间", "starts_at", "datetime-local", localInput())}
                {field(
                  "截止时间",
                  "ends_at",
                  "datetime-local",
                  localInput(Date.now() + 30 * 86400000),
                )}
              </>
            ) : tab === "campaigns" ? (
              <>
                {field(
                  "产品ID",
                  "product_id",
                  "text",
                  edit.product_id || "ecs",
                )}
                {field("活动名称", "title", "text", edit.title)}
                {field(
                  "佣金万分比（500即5%）",
                  "rate_bps",
                  "number",
                  edit.rate_bps || 500,
                )}
              </>
            ) : tab === "knowledge" ? (
              <>
                {field(
                  "产品ID",
                  "product_id",
                  "text",
                  edit.product_id || "ecs",
                )}
                {field("标题", "title", "text", edit.title)}
                <label className="field">
                  <span>正文（只在审核发布后进入检索）</span>
                  <textarea
                    name="content"
                    required
                    maxLength={100000}
                    rows={12}
                    defaultValue={edit.content}
                  />
                </label>
                {field(
                  "有效截止时间",
                  "valid_until",
                  "datetime-local",
                  localInput(edit.valid_until || Date.now() + 365 * 86400000),
                )}
              </>
            ) : (
              <>
                <label className="field">
                  <span>服务商</span>
                  <select name="provider">
                    <option>ALIPAY_SANDBOX</option>
                    <option>ALIYUN_ECS</option>
                    <option>DELIVERY</option>
                  </select>
                </label>
                <label className="field">
                  <span>模式</span>
                  <select name="mode">
                    <option>DISABLED</option>
                    <option>SANDBOX</option>
                    <option>DRY_RUN</option>
                    <option>LOCAL</option>
                  </select>
                </label>
                <label className="field">
                  <span>阿里云资源映射（其他渠道填空对象；不填写密钥）</span>
                  <textarea name="settings" defaultValue={"{}"} required />
                </label>
              </>
            )}
            {["coupons", "campaigns"].includes(tab) && (
              <label className="field">
                <span>启用</span>
                <select
                  name="active"
                  defaultValue={String(edit.active ?? true)}
                >
                  <option value="true">启用</option>
                  <option value="false">停用</option>
                </select>
              </label>
            )}
            {field("操作原因", "reason")}
            <div className="actions">
              <button>保存{tab === "knowledge" ? "为草稿" : "配置"}</button>
              <button
                type="button"
                className="ghost"
                onClick={() => setEdit(null)}
              >
                取消
              </button>
            </div>
          </form>
        </section>
      )}
    </>
  );
}
function ReportView() {
  const [range, setRange] = useState({
      from: new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10),
      to: new Date().toISOString().slice(0, 10),
    }),
    [result, setResult] = useState<any>(null),
    [error, setError] = useState("");
  return (
    <>
      <h2>运营报表</h2>
      <form
        className="p1-form"
        onSubmit={(e) => {
          e.preventDefault();
          api("/admin/reports?" + new URLSearchParams(range))
            .then(setResult)
            .catch((e) => setError(e.message));
        }}
      >
        <input
          aria-label="开始日期"
          type="date"
          value={range.from}
          onChange={(e) => setRange({ ...range, from: e.target.value })}
        />
        <input
          aria-label="结束日期"
          type="date"
          value={range.to}
          onChange={(e) => setRange({ ...range, to: e.target.value })}
        />
        <button>查询</button>
      </form>
      <ErrorBox text={error} />
      {result && (
        <>
          <p>
            实付：{money(result.payments.paid_cents)} · 已退：
            {money(result.refunds.refunded_cents)} · 净额：
            {money(result.net_cents)}
          </p>
          <p className="muted">{result.time_basis}</p>
          <Rows
            rows={result.sales}
            columns={[
              ["sku_id", "SKU"],
              ["kind", "订单类型"],
              ["orders", "订单数"],
              ["gross_cents", "实付"],
              ["refunded_cents", "已退"],
            ]}
          />
        </>
      )}
    </>
  );
}
