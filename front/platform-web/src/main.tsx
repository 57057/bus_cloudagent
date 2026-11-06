import React, { useEffect, useState, useRef } from "react";
import { createRoot } from "react-dom/client";
import {
  api,
  chat,
  refresh,
  setToken,
  money,
  date,
  parse,
  key,
  download,
  Row,
} from "./api";
import "./style.css";
import { P1UserPage, P1AdminPage, PasswordReset, ActionInbox } from "./p1";
import { Applications,ApplicationPlanInbox } from "./applications";

const names: Row = {
  PENDING_PAYMENT: "待支付",
  FULFILLING: "开通 / 续费中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  CLOSED: "已关闭",
  PARTIALLY_FULFILLED: "部分开通",
  FULFILLMENT_FAILED: "开通失败",
  RUNNING: "运行中",
  STOPPED: "已关机",
  SUSPENDED: "到期暂停",
  RELEASING: "释放中",
  RELEASED: "已释放",
  STARTING: "启动中",
  STOPPING: "停止中",
  RESTARTING: "重启中",
  SUBMITTED: "待审核",
  APPROVED: "审核通过",
  REJECTED: "已拒绝",
  RESOURCE_PROCESSING: "资源处理中",
  REFUNDING: "退款中",
  SUCCEEDED: "成功",
  FAILED: "失败",
  CREATED: "已创建",
  PROCESSING: "处理中",
  MANUAL_REQUIRED: "需人工处理",
  PENDING: "等待执行",
  OPEN: "待处理",
  IN_PROGRESS: "处理中",
  WAITING_USER: "待用户回复",
  RESOLVED: "已解决",
  USER: "用户",
  OPERATOR: "运营",
  ADMIN: "管理员",
  NEW_PURCHASE: "新购",
  RENEWAL: "续费",
};
const Badge = ({ value }: { value: string }) => (
  <span
    className={`badge ${["RUNNING", "COMPLETED", "SUCCEEDED", "ADMIN"].includes(value) ? "good" : ["FAILED", "MANUAL_REQUIRED", "REJECTED", "SUSPENDED"].includes(value) ? "bad" : ""}`}
  >
    {names[value] || value}
  </span>
);
function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}
function Empty({ text = "暂无记录" }: { text?: string }) {
  return (
    <div className="empty">
      ◇<p>{text}</p>
    </div>
  );
}
function Modal({
  title,
  children,
  close,
}: {
  title: string;
  children: React.ReactNode;
  close: () => void;
}) {
  return (
    <div className="overlay" onClick={close}>
      <section className="modal" onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>{title}</h2>
          <button className="ghost" onClick={close}>
            关闭 ×
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
function useData(path: string, interval = 0) {
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
    const timer = interval ? setInterval(load, interval) : undefined;
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [path, version, interval]);
  return { data, error, reload: () => bump((v) => v + 1) };
}
const ErrorBox = ({ error }: { error: string }) =>
  error ? (
    <div role="alert" className="error">
      {error}
    </div>
  ) : null;
const Loading = () => <p className="muted">正在加载…</p>;
type Context = {
  user: Row | null;
  notice: (s: string) => void;
  run: (fn: () => Promise<void>) => void;
  login: () => void;
};
function App() {
  const [user, setUser] = useState<Row | null>(null),
    [ready, setReady] = useState(false),
    [path, setPath] = useState(location.hash.slice(1) || "/products"),
    [auth, setAuth] = useState(false),
    [toast, setToast] = useState("");
  useEffect(() => {
    refresh()
      .then((d) => setUser(d.user))
      .catch(() => {})
      .finally(() => setReady(true));
    const nav = () => setPath(location.hash.slice(1) || "/products");
    const expired = () => {
      setUser(null);
      setAuth(true);
    };
    window.addEventListener("hashchange", nav);
    window.addEventListener("session-expired", expired);
    return () => {
      window.removeEventListener("hashchange", nav);
      window.removeEventListener("session-expired", expired);
    };
  }, []);
  useEffect(() => {
    if (toast) {
      const t = setTimeout(() => setToast(""), 6000);
      return () => clearTimeout(t);
    }
  }, [toast]);
  const run = (fn: () => Promise<void>) => {
    fn().catch((e) => setToast(e.message));
  };
  const ctx: Context = {
    user,
    notice: setToast,
    run,
    login: () => setAuth(true),
  };
  const staff = user && ["ADMIN", "OPERATOR"].includes(user.role);
  const nav = [
    ["/products", "▦", "产品中心"],
    ["/orders", "▤", "我的订单"],
    ["/instances", "▱", "我的资源"],
    ["/applications", "▧", "我的应用"],
    ["/refunds", "↶", "退订退款"],
    ["/tickets", "◷", "服务工单"],
    ["/notifications", "♧", "站内通知"],
    ["/chat", "✧", "AI 助手"],
    ["/profile", "○", "用户中心"],
    ["/services", "◇", "费用与服务"],
  ];
  return (
    <div className="layout">
      <aside className="sidebar">
        <a className="brand" href="#/products">
          <span className="brand-mark">☁</span> 云栈 <small>CLOUD STACK</small>
        </a>
        <div className="workspace">
          个人云工作台 <span>{window.__CLOUDAGENT_DEPLOYMENT__?.environmentLabel||'工作台'}</span>
        </div>
        <nav>
          {nav.map(([href, icon, title]) => (
            <a
              className={path.startsWith(href) ? "active" : ""}
              href={"#" + href}
              key={href}
            >
              <span>{icon}</span>
              {title}
            </a>
          ))}
          {user?.role === 'ADMIN' && <a href="#/admin/hosts">▧ 平台资源池</a>}
          {staff && (
            <a
              className={path.startsWith("/admin") ? "active" : ""}
              href="#/admin"
            >
              <span>⚙</span>管理后台
            </a>
          )}
        </nav>
        <div className="sidebar-foot">
            <span className="dot" /> {window.__CLOUDAGENT_DEPLOYMENT__?.environmentLabel || (window.__CLOUDAGENT_DEPLOYMENT__?.frontendOnly ? '云端前端预览' : '本地验收环境')}<p>模拟 / 沙箱支付 · 不采购云资源</p>
        </div>
      </aside>
      <main>
        <div className="topbar">
          <span>
            控制台 <i>/</i> 云服务租用平台
          </span>
          <div>
            {user ? (
              <>
                <span>
                  {user.nickname} <Badge value={user.role} />
                </span>
                <button
                  className="ghost"
                  onClick={() =>
                    run(async () => {
                      await api("/auth/logout", "POST");
                      setToken("");
                      setUser(null);
                      location.hash = "/products";
                    })
                  }
                >
                  退出
                </button>
              </>
            ) : (
              <button onClick={() => setAuth(true)}>登录 / 注册</button>
            )}
          </div>
        </div>
        <div className="demo-strip">
            {window.__CLOUDAGENT_DEPLOYMENT__?.frontendOnly ? '云端前端预览 · 业务后端尚未接入，登录、下单和AI服务暂不可用。' : window.__CLOUDAGENT_DEPLOYMENT__?.notice || '本地验收环境 · 支持模拟与支付宝沙箱支付。实例按记录区分模拟资源和平台本地Linux资源，不创建阿里云收费实例。'}
        </div>
        <div className="page">
          {!ready ? (
            <Loading />
          ) : path.startsWith("/products") ? (
            <Products ctx={ctx} path={path} />
          ) : !user ? (
            <section className="panel">
              <Empty text="登录后管理你的订单、资源与会话" />
              <button onClick={() => setAuth(true)}>立即登录</button>
            </section>
          ) : path === "/orders" ? (
            <Orders ctx={ctx} />
          ) : path === "/instances" ? (
            <Instances ctx={ctx} />
          ) : path === "/refunds" ? (
            <Refunds />
          ) : path === "/tickets" ? (
            <Tickets ctx={ctx} />
          ) : path === "/notifications" ? (
            <Notices ctx={ctx} />
          ) : path === "/chat" ? (
            <Chat ctx={ctx} />
          ) : path === "/profile" ? (
            <Profile ctx={ctx} update={setUser} />
          ) : path === "/applications" ? (
            <Applications ctx={ctx} />
          ) : path === "/admin/hosts" && user?.role === 'ADMIN' ? (
            <Applications ctx={ctx} admin />
          ) : path === "/services" ? (
            <P1UserPage ctx={ctx} />
          ) : path === "/admin/p1" && staff ? (
            <P1AdminPage ctx={ctx} />
          ) : path.startsWith("/admin") && staff ? (
            <Admin ctx={ctx} />
          ) : (
            <Empty text="页面不存在" />
          )}
        </div>
      </main>
      {toast && (
        <div role="status" className="toast" onClick={() => setToast("")}>
          {toast}
        </div>
      )}
      {auth && (
        <Auth
          close={() => setAuth(false)}
          success={(u) => {
            setUser(u);
            setAuth(false);
          }}
        />
      )}
    </div>
  );
}
function Auth({
  close,
  success,
}: {
  close: () => void;
  success: (u: Row) => void;
}) {
  const [forgot, setForgot] = useState(false);
  const [register, setRegister] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <Modal title={register ? "创建账号" : "欢迎回到云栈"} close={close}>
      <button className="ghost" onClick={() => setForgot(!forgot)}>
        {forgot ? "返回登录" : "忘记密码？"}
      </button>
      {forgot ? (
        <PasswordReset close={() => setForgot(false)} />
      ) : (
        <>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const d = Object.fromEntries(new FormData(e.currentTarget));
              setBusy(true);
              setError("");
              try {
                if (register)
                  await api("/auth/register", "POST", {
                    ...d,
                    agreement: true,
                  });
                const result = await api("/auth/login", "POST", d);
                setToken(result.access_token);
                success(result.user);
              } catch (e: any) {
                setError(e.message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <p className="muted">通过账号管理你的云端资源与租用记录</p>
            <Field label="用户名">
              <input
                name="username"
                autoComplete="username"
                required
                minLength={3}
                maxLength={32}
                pattern="[a-z0-9_]+"
                placeholder="3~32位小写字母、数字或下划线"
              />
            </Field>
            {register && (
              <Field label="昵称">
                <input name="nickname" maxLength={64} required />
              </Field>
            )}
            <Field label="密码">
              <input
                name="password"
                type="password"
                autoComplete={register ? "new-password" : "current-password"}
                required
                minLength={register ? 10 : 1}
                placeholder="至少10位，包含字母和数字"
              />
            </Field>
            {register && (
              <label className="check">
                <input type="checkbox" required />
                我同意：这是模拟租用平台，不提供真实计算资源或支付交易。
              </label>
            )}
            <ErrorBox error={error} />
            <button className="wide" disabled={busy}>
              {busy ? "处理中…" : register ? "注册并登录" : "登录"}
            </button>
            <button
              type="button"
              className="ghost wide"
              onClick={() => setRegister(!register)}
            >
              {register ? "已有账号，返回登录" : "没有账号？立即注册"}
            </button>
          </form>
        </>
      )}
    </Modal>
  );
}
function Heading({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: React.ReactNode;
}) {
  return (
    <header className="heading">
      <div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {children}
    </header>
  );
}
function CouponSelect({
  user,
  value,
  change,
}: {
  user: Row | null;
  value: string;
  change: (v: string) => void;
}) {
  const [coupons, setCoupons] = useState<Row[]>([]);
  useEffect(() => {
    if (user)
      api("/coupons")
        .then(setCoupons)
        .catch(() => {});
    else setCoupons([]);
  }, [user]);
  return (
    <label className="field">
      <span>优惠券（先在费用与服务领取）</span>
      <select value={value} onChange={(e) => change(e.target.value)}>
        <option value="">不使用优惠券</option>
        {coupons
          .filter((c) => c.state === "AVAILABLE" && c.active)
          .map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} · 满{money(c.minimum_cents)}减{money(c.discount_cents)}
            </option>
          ))}
      </select>
    </label>
  );
}
function Products({ ctx, path }: { ctx: Context; path: string }) {
  const [filter, setFilter] = useState({
    keyword: "",
    category: "",
    region: "",
    cpu: "",
    memory_gb: "",
    min_price: "",
    max_price: "",
    sort: "name",
    page: "1",
  });
  const id = path.split("?")[0].split("/")[2];
  const { data, error } = useData("/products?" + new URLSearchParams(filter));
  if (id) return <Configure ctx={ctx} id={id} />;
  return (
    <>
      <Heading
        title="为你的下一步，选择合适的算力"
        description="从开发测试到业务部署，按需选择配置，价格与库存透明可见。"
      />
      <div className="hero">
        <div>
          <span className="eyebrow">COMPUTE · CONNECT · BUILD</span>
          <h2>从一台云服务器开始</h2>
          <p>灵活配置 · 包月包年 · 全生命周期管理</p>
          <a className="button white" href="#/products/ecs">
            选配 ECS →
          </a>
        </div>
        <div className="server-art">
          <div />
          <div />
          <div />
          <span>ELASTIC COMPUTE</span>
        </div>
      </div>
      <div className="panel filters">
        {[
          ["keyword", "搜索名称或描述"],
          ["region", "地域，例如 cn-hangzhou"],
          ["cpu", "CPU核数"],
          ["memory_gb", "内存GB"],
          ["min_price", "最低基础月价（分）"],
          ["max_price", "最高基础月价（分）"],
        ].map(([k, label]) => (
          <input
            key={k}
            aria-label={label}
            placeholder={label}
            value={(filter as Row)[k]}
            onChange={(e) =>
              setFilter({ ...filter, [k]: e.target.value, page: "1" })
            }
          />
        ))}
        <select
          aria-label="产品类型"
          value={filter.category}
          onChange={(e) =>
            setFilter({ ...filter, category: e.target.value, page: "1" })
          }
        >
          <option value="">全部类型</option>
          {["ECS", "GPU", "RDS", "DISK"].map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
        <select
          aria-label="排序"
          value={filter.sort}
          onChange={(e) => setFilter({ ...filter, sort: e.target.value })}
        >
          <option value="name">名称排序</option>
          <option value="price_asc">价格从低到高</option>
          <option value="price_desc">价格从高到低</option>
        </select>
      </div>
      <ErrorBox error={error} />
      {!data ? (
        <Loading />
      ) : (
        <>
          <div className="section-label">
            可选云产品 <span>{data.total} 款产品</span>
          </div>
          <div className="product-grid">
            {data.items.map((p: Row) => (
              <article className="product-card" key={p.id}>
                <span className="product-icon">
                  {p.category === "ECS"
                    ? "▥"
                    : p.category === "GPU"
                      ? "⬡"
                      : "▤"}
                </span>
                <span className="tag">{p.category}</span>
                <h2>{p.name}</h2>
                <p>{p.description}</p>
                <small>{p.scenario}</small>
                <footer>
                  <div>
                    {p.price_cents ? (
                      <>
                        <strong>{money(p.price_cents)}</strong>
                        <span> / 月起</span>
                      </>
                    ) : (
                      <span>暂未开放购买</span>
                    )}
                  </div>
                  <a className="button secondary" href={"#/products/" + p.id}>
                    {p.purchasable ? "立即选配" : "查看详情"} →
                  </a>
                </footer>
              </article>
            ))}
          </div>
          {data.items.length === 0 && <Empty />}
          <Pager
            page={Number(filter.page)}
            total={data.total}
            size={12}
            change={(n) => setFilter({ ...filter, page: "" + n })}
          />
        </>
      )}
    </>
  );
}
function Pager({
  page,
  total,
  size = 20,
  change,
}: {
  page: number;
  total: number;
  size?: number;
  change: (n: number) => void;
}) {
  return (
    <div className="pager">
      <span>
        共 {total} 条 · 第 {page} 页
      </span>
      <button
        className="secondary"
        disabled={page === 1}
        onClick={() => change(page - 1)}
      >
        上一页
      </button>
      <button
        className="secondary"
        disabled={page * size >= total}
        onClick={() => change(page + 1)}
      >
        下一页
      </button>
    </div>
  );
}
function Configure({ ctx, id }: { ctx: Context; id: string }) {
  const product = useData("/products/" + id),
    skus = useData("/products/" + id + "/skus");
  const [config, setConfig] = useState<Row>(() => {
    try {
      return {
        ...{
          image: "ubuntu-22",
          disk_type: "ESSD",
          disk_gb: 40,
          bandwidth: 1,
          months: 1,
          quantity: 1,
          name: "我的云服务器",
        },
        ...JSON.parse(sessionStorage.getItem("rental-draft") || "{}"),
      };
    } catch {
      return {
        image: "ubuntu-22",
        disk_type: "ESSD",
        disk_gb: 40,
        bandwidth: 1,
        months: 1,
        quantity: 1,
        name: "我的云服务器",
      };
    }
  });
  const [quote, setQuote] = useState<Row | null>(null),
    [busy, setBusy] = useState(false);
  const orderKey = useRef(key());
  useEffect(() => {
    sessionStorage.setItem("rental-draft", JSON.stringify(config));
    setQuote(null);
  }, [config]);
  const selected =
    skus.data?.find((s: Row) => s.id === config.sku_id) || skus.data?.[0];
  const change = (k: string, v: any) => setConfig({ ...config, [k]: v });
  const getQuote = () =>
    ctx.run(async () => {
      if (!ctx.user) {
        ctx.login();
        return;
      }
      setBusy(true);
      try {
        const q = await api("/quotes", "POST", {
          ...config,
          sku_id: selected.id,
          promotion_link:
            new URLSearchParams(location.hash.split("?")[1] || "").get("ref") ||
            undefined,
        });
        setQuote(q);
        orderKey.current = key();
      } finally {
        setBusy(false);
      }
    });
  return (
    <>
      <a className="back" href="#/products">
        ← 产品中心
      </a>
      <Heading
        title={product.data?.name || "产品详情"}
        description={product.data?.description || "正在加载产品配置"}
      />
      <ErrorBox error={product.error || skus.error} />
      {!skus.data ? (
        <Loading />
      ) : skus.data.length === 0 ? (
        <div className="panel">
          <Empty text="此产品尚未配置供应能力，暂不可购买" />
        </div>
      ) : (
        <div className="configure-layout">
          <div className="panel">
            <h2>定制你的云服务器</h2>
            <p className="muted">选择预付费配置，确认报价后提交订单</p>
            <div className="sku-grid">
              {skus.data.map((s: Row) => (
                <button
                  key={s.id}
                  className={`sku ${selected?.id === s.id ? "selected" : ""}`}
                  onClick={() =>
                    setConfig({
                      ...config,
                      sku_id: s.id,
                      disk_gb: s.min_disk,
                      image: s.images.split(",")[0],
                    })
                  }
                >
                  <strong>{s.name}</strong>
                  <span>
                    {s.cpu} vCPU · {s.memory_gb} GB
                  </span>
                  <small>
                    {s.region} / {s.zone}
                  </small>
                  <span>
                    可售 {s.available} 台 · {money(s.base_cents)} 基础月价
                  </span>
                </button>
              ))}
            </div>
            <div className="form-grid">
              <Field label="镜像">
                <select
                  value={config.image}
                  onChange={(e) => change("image", e.target.value)}
                >
                  {selected.images.split(",").map((v: string) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </Field>
              <Field label="ESSD 系统盘（GB）">
                <input
                  type="number"
                  min={selected.min_disk}
                  max={selected.max_disk}
                  value={config.disk_gb}
                  onChange={(e) => change("disk_gb", Number(e.target.value))}
                />
              </Field>
              <Field label="公网带宽（Mbps）">
                <input
                  type="number"
                  min={1}
                  max={selected.max_bandwidth}
                  value={config.bandwidth}
                  onChange={(e) => change("bandwidth", Number(e.target.value))}
                />
              </Field>
              <Field label="租用周期">
                <select
                  value={config.months}
                  onChange={(e) => change("months", Number(e.target.value))}
                >
                  {selected.periods.split(",").map((m: string) => (
                    <option value={m} key={m}>
                      {m} 个月{m === "12" ? " · 85折" : ""}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="实例数量">
                <input
                  type="number"
                  min={1}
                  max={10}
                  value={config.quantity}
                  onChange={(e) => change("quantity", Number(e.target.value))}
                />
              </Field>
              <Field label="实例名称">
                <input
                  value={config.name}
                  maxLength={80}
                  onChange={(e) => change("name", e.target.value)}
                />
              </Field>
            </div>
          </div>
          <div className="panel quote-panel">
            <CouponSelect
              user={ctx.user}
              value={config.coupon_grant_id || ""}
              change={(v) => change("coupon_grant_id", v)}
            />
            <h2>配置与费用</h2>
            <dl>
              <dt>规格</dt>
              <dd>
                {selected.cpu} 核 / {selected.memory_gb} GB
              </dd>
              <dt>地域</dt>
              <dd>{selected.region}</dd>
              <dt>磁盘月价</dt>
              <dd>{money(selected.disk_cents)} / GB</dd>
              <dt>带宽月价</dt>
              <dd>{money(selected.bandwidth_cents)} / Mbps</dd>
              <dt>到期宽限期</dt>
              <dd>{selected.grace_days} 天</dd>
            </dl>
            <p className="muted">
              退款规则：首次开通后 {selected.refund_days}{" "}
              天内按剩余租期比例退款；失败项全额退。已履约续费请通过工单处理退订。
            </p>
            <button className="wide" disabled={busy} onClick={getQuote}>
              获取服务端报价
            </button>
            {quote && (
              <>
                <hr />
                <p>
                  单台租期费用 <strong>{money(quote.unit_cents)}</strong>
                </p>
                <div className="price">{money(quote.total_cents)}</div>
                <small>报价有效至 {date(quote.expires_at)} · CNY</small>
                <p>
                  配置：{config.image} / ESSD {config.disk_gb}GB /{" "}
                  {config.bandwidth}Mbps / {quote.months}个月 × {quote.quantity}
                  台
                </p>
                <button
                  className="wide"
                  disabled={busy}
                  onClick={() =>
                    ctx.run(async () => {
                      setBusy(true);
                      try {
                        await api(
                          "/orders",
                          "POST",
                          { quote_id: quote.id },
                          orderKey.current,
                        );
                        sessionStorage.removeItem("rental-draft");
                        ctx.notice("下单成功，请在支付窗口内完成模拟付款");
                        location.hash = "/orders";
                      } finally {
                        setBusy(false);
                      }
                    })
                  }
                >
                  确认配置并下单
                </button>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
function Orders({ ctx }: { ctx: Context }) {
  const [page, setPage] = useState(1),
    [state, setState] = useState(""),
    [selected, setSelected] = useState("");
  const { data, error, reload } = useData(
    `/orders?page=${page}&state=${state}`,
    3000,
  );
  return (
    <>
      <Heading
        title="我的订单"
        description="查看新购与续费订单，跟踪付款和每一台实例的开通进度。"
      />
      <div className="toolbar">
        <select
          aria-label="订单状态"
          value={state}
          onChange={(e) => {
            setState(e.target.value);
            setPage(1);
          }}
        >
          <option value="">全部状态</option>
          {[
            "PENDING_PAYMENT",
            "FULFILLING",
            "COMPLETED",
            "PARTIALLY_FULFILLED",
            "FULFILLMENT_FAILED",
            "CANCELLED",
            "CLOSED",
          ].map((v) => (
            <option value={v} key={v}>
              {names[v]}
            </option>
          ))}
        </select>
        <button className="secondary" onClick={reload}>
          刷新
        </button>
      </div>
      <ErrorBox error={error} />
      <div className="panel">
        {!data ? (
          <Loading />
        ) : !data.items.length ? (
          <Empty />
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>订单 / 创建时间</th>
                    <th>类型</th>
                    <th>数量 / 租期</th>
                    <th>应付金额</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((o: Row) => (
                    <tr key={o.id}>
                      <td>
                        <code>{o.id.slice(0, 12)}</code>
                        <small>{date(o.created_at)}</small>
                      </td>
                      <td>
                        <Badge value={o.kind} />
                      </td>
                      <td>
                        {o.quantity} 台 / {o.months} 个月
                      </td>
                      <td>
                        <strong>{money(o.total_cents)}</strong>
                      </td>
                      <td>
                        <Badge value={o.state} />
                      </td>
                      <td>
                        <button
                          className="secondary"
                          onClick={() => setSelected(o.id)}
                        >
                          查看详情
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} total={data.total} change={setPage} />
          </>
        )}
      </div>
      {selected && (
        <OrderDetail
          id={selected}
          ctx={ctx}
          close={() => {
            setSelected("");
            reload();
          }}
        />
      )}
    </>
  );
}
function OrderDetail({
  id,
  ctx,
  close,
}: {
  id: string;
  ctx: Context;
  close: () => void;
}) {
  const { data: o, error, reload } = useData("/orders/" + id, 1500),
    [payment, setPayment] = useState<Row | null>(null),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <Modal title="订单详情与模拟收银台" close={close}>
      <ErrorBox error={error} />
      {!o ? (
        <Loading />
      ) : (
        <>
          <div className="detail-head">
            <code>{o.id}</code>
            <Badge value={o.state} />
          </div>
          {o.state === "PENDING_PAYMENT" && (
            <button
              className="secondary"
              onClick={() =>
                ctx.run(async () => {
                  const pay = await api(
                    `/orders/${o.id}/sandbox-payment`,
                    "POST",
                  );
                  const result = await api(`/payments/${pay.id}/sandbox-qr`);
                  if (/^https:\/\//.test(result.qr_code))
                    window.open(
                      result.qr_code,
                      "_blank",
                      "noopener,noreferrer",
                    );
                  ctx.notice(
                    "请使用支付宝沙箱买家完成支付，结果由后端查询确认",
                  );
                })
              }
            >
              支付宝沙箱付款（需管理员配置）
            </button>
          )}
          <dl>
            <dt>产品</dt>
            <dd>
              {parse(o.snapshot).product_name} · {parse(o.snapshot).name}
            </dd>
            <dt>配置快照</dt>
            <dd>
              {parse(o.config).image} / {parse(o.config).disk_gb}GB /{" "}
              {parse(o.config).bandwidth}Mbps
            </dd>
            <dt>订单金额</dt>
            <dd>
              {money(o.total_cents)} · {o.payments?.[0]?.channel || "模拟交易"}
            </dd>
            <dt>支付期限</dt>
            <dd>{date(o.payment_deadline)}</dd>
            <dt>已退 / 退款处理中</dt>
            <dd>
              {money(o.refunded_cents)} / {money(o.refund_held_cents)}
            </dd>
            <dt>退款规则</dt>
            <dd>{parse(o.snapshot).refund_rule}</dd>
          </dl>
          {o.state === "PENDING_PAYMENT" && (
            <div className="actions">
              <button
                onClick={() =>
                  ctx.run(async () =>
                    setPayment(await api(`/orders/${id}/payments`, "POST")),
                  )
                }
              >
                进入模拟收银台
              </button>
              <button
                className="danger"
                onClick={() =>
                  ctx.run(async () => {
                    await api(`/orders/${id}/cancel`, "POST");
                    reload();
                  })
                }
              >
                取消订单
              </button>
            </div>
          )}
          {payment && (
            <div className="callout">
              <h3>模拟支付 · {money(payment.amount_cents)}</h3>
              <p>
                不会产生真实扣款。提交后由模拟渠道确认，系统自动查询付款结果。
              </p>
              <button
                disabled={busy || o.state !== "PENDING_PAYMENT"}
                onClick={() =>
                  ctx.run(async () => {
                    setBusy(true);
                    try {
                      await api(`/payments/${payment.id}/simulate`, "POST", {
                        confirmed: true,
                      });
                      ctx.notice("模拟渠道已受理，请等待支付与开通状态更新");
                      reload();
                    } finally {
                      setBusy(false);
                    }
                  })
                }
              >
                确认模拟付款
              </button>
            </div>
          )}
          <h3>支付记录</h3>
          {o.payments.map((p: Row) => (
            <p key={p.id}>
              模拟支付 · {money(p.amount_cents)} <Badge value={p.state} />
              <small> {p.id}</small>
            </p>
          ))}
          <h3>逐台履约</h3>
          {o.items.map((item: Row) => (
            <div className="item" key={item.id}>
              <div>
                <code>{item.instance_id}</code>
                <p>
                  {money(item.amount_cents)} ·{" "}
                  {item.refunded
                    ? "已退款"
                    : item.refund_pending
                      ? "退款处理中"
                      : ""}
                </p>
              </div>
              <Badge value={item.state} />
              {o.payments.some((p: Row) => p.state === "SUCCEEDED") &&
                !item.refunded &&
                !item.refund_pending && (
                  <button
                    className="secondary"
                    onClick={() =>
                      ctx.run(async () => {
                        if (!reason.trim()) {
                          ctx.notice("请先填写退款原因");
                          return;
                        }
                        const preview = await api(
                          `/orders/${id}/refund-preview?item_id=${item.id}`,
                        );
                        if (
                          !confirm(
                            `预计可退 ${money(preview.amount_cents)}。${preview.rule}。审核通过后可能释放对应资源，是否提交申请？`,
                          )
                        )
                          return;
                        const f = await api("/refunds", "POST", {
                          order_id: id,
                          item_id: item.id,
                          reason,
                        });
                        ctx.notice(
                          `申请已提交，可退 ${money(f.amount_cents)}，等待审核`,
                        );
                        reload();
                      })
                    }
                  >
                    申请退款
                  </button>
                )}
            </div>
          ))}
          <Field label="退订 / 退款原因">
            <input
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="系统根据订单规则计算可退额度"
            />
          </Field>
          <h3>履约任务</h3>
          {o.tasks.map((t: Row) => (
            <p key={t.id}>
              <Badge value={t.state} /> {t.type} · 尝试 {t.attempts} 次{" "}
              {t.error}
            </p>
          ))}
          {o.refunds.map((f: Row) => (
            <p key={f.id}>
              退款 {money(f.amount_cents)} <Badge value={f.state} />{" "}
              {f.review_reason}
            </p>
          ))}
        </>
      )}
    </Modal>
  );
}
function Instances({ ctx }: { ctx: Context }) {
  const [page, setPage] = useState(1),
    [selected, setSelected] = useState(""),
    [renew, setRenew] = useState<Row | null>(null),
    [months, setMonths] = useState(1),
    [quote, setQuote] = useState<Row | null>(null);
  const renewalKey = useRef(key());
  const { data, error, reload } = useData("/instances?page=" + page, 3000);
  const detail = useData(
    "/instances/" + (selected || "none"),
    selected ? 3000 : 0,
  );
  return (
    <>
      <Heading
        title="我的资源"
        description="租用资源后，前往“我的应用”创建网站并部署。停止应用保留资源；释放资源会结束租约并清理应用。"
      />
      <ErrorBox error={error} />
      <div className="panel">
        {!data ? (
          <Loading />
        ) : !data.items.length ? (
          <Empty text="还没有可用资源，先到产品中心选择一个应用部署套餐吧" />
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>实例名称 / 编号</th>
                    <th>状态</th>
                    <th>网络与配置</th>
                    <th>到期时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((i: Row) => (
                    <tr key={i.id}>
                      <td>
                        <strong>{i.name}</strong>
                        <small>{i.id.slice(0, 12)} · {i.simulated ? '模拟实例' : '平台资源池实例'}</small>
                      </td>
                      <td>
                        <Badge value={i.state} />
                      </td>
                      <td>
                        {i.public_ip}
                        <small>
                          {parse(i.config).disk_gb}GB /{" "}
                          {parse(i.config).bandwidth}Mbps · {i.simulated?'模拟地址':'平台管理的应用资源'}
                        </small>
                      </td>
                      <td>{date(i.expires_at)}</td>
                      <td>
                        <div className="actions">
                          {!i.simulated&&i.state==='RUNNING'&&<a className="button secondary" href="#/applications">部署 / 查看应用</a>}
                          <button
                            className="secondary"
                            onClick={() => setSelected(i.id)}
                          >
                            详情
                          </button>
                          {i.state !== "RELEASED" && (
                            <button
                              onClick={() => {
                                setRenew(i);
                                setQuote(null);
                              }}
                            >
                              续费
                            </button>
                          )}
                          {["START", "STOP", "RESTART", "RELEASE"].map(
                            (action) => (
                              <button
                                key={action}
                                className={
                                  action === "RELEASE" ? "danger" : "ghost"
                                }
                                disabled={
                                  action === "START"
                                    ? i.state !== "STOPPED"
                                    : action === "RELEASE"
                                      ? ![
                                          "RUNNING",
                                          "STOPPED",
                                          "SUSPENDED",
                                        ].includes(i.state)
                                      : i.state !== "RUNNING"
                                }
                                onClick={() =>
                                  ctx.run(async () => {
                                    if (
                                      action === "RELEASE" &&
                                      !confirm(
                                        "释放将结束此资源租约并清理已部署应用，且不会自动退款。确定释放？",
                                      )
                                    )
                                      return;
                                    await api(
                                      `/instances/${i.id}/actions`,
                                      "POST",
                                      {
                                        action,
                                        confirmed: action === "RELEASE",
                                      },
                                      key(),
                                    );
                                    ctx.notice("操作已提交，等待供应商确认");
                                    reload();
                                  })
                                }
                              >
                                {
                                  (
                                    {
                                      START: "启动",
                                      STOP: "停止",
                                      RESTART: "重启",
                                      RELEASE: "释放",
                                    } as Row
                                  )[action]
                                }
                              </button>
                            ),
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} total={data.total} change={setPage} />
          </>
        )}
      </div>
      {renew && (
        <Modal title="手动续费" close={() => setRenew(null)}>
          <p>
            {renew.name} · 到期 {date(renew.expires_at)}
          </p>
          <Field label="续费周期">
            <select
              value={months}
              onChange={(e) => {
                setMonths(+e.target.value);
                setQuote(null);
              }}
            >
              {[1, 3, 12].map((m) => (
                <option value={m} key={m}>
                  {m}个月
                </option>
              ))}
            </select>
          </Field>
          <p>未到期从原有效期延长；宽限期内从恢复服务时间开始计算新周期。</p>
          <button
            onClick={() =>
              ctx.run(async () => {
                setQuote(
                  await api(`/instances/${renew.id}/renewal-quotes`, "POST", {
                    months,
                  }),
                );
                renewalKey.current = key();
              })
            }
          >
            获取续费报价
          </button>
          {quote && (
            <div className="callout">
              <strong>{money(quote.total_cents)}</strong>
              <p>有效至 {date(quote.expires_at)}</p>
              <button
                onClick={() =>
                  ctx.run(async () => {
                    await api(
                      "/renewal-orders",
                      "POST",
                      { quote_id: quote.id },
                      renewalKey.current,
                    );
                    setRenew(null);
                    location.hash = "/orders";
                  })
                }
              >
                确认并生成续费订单
              </button>
            </div>
          )}
        </Modal>
      )}
      {selected && (
        <Modal title="实例详情" close={() => setSelected("")}>
          <ErrorBox error={detail.error} />
          {detail.data && (
            <>
              <dl>
                {[
                  "id",
                  "provider_id",
                  "sku_id",
                  "state",
                  "created_at",
                  "starts_at",
                  "expires_at",
                ].map((k) => (
                  <React.Fragment key={k}>
                    <dt>{k}</dt>
                    <dd>
                      {k.endsWith("_at")
                        ? date(detail.data[k])
                        : detail.data[k]}
                    </dd>
                  </React.Fragment>
                ))}
              </dl>
              <p className="callout">
                监控：暂无采样数据，不生成虚构 CPU 或内存使用率。
              </p>
              <h3>操作记录</h3>
              {detail.data.operations.map((o: Row) => (
                <p key={o.id}>
                  {o.type} <Badge value={o.state} /> {o.error} ·{" "}
                  {date(o.created_at)}
                </p>
              ))}
              <h3>续费记录</h3>
              {detail.data.renewals.map((v: Row) => (
                <p key={v.order_id}>
                  {date(v.old_expires)} → {date(v.new_expires)}
                </p>
              ))}
            </>
          )}
        </Modal>
      )}
    </>
  );
}
function Refunds() {
  const [page, setPage] = useState(1);
  const { data, error } = useData("/refunds?page=" + page, 3000);
  return (
    <>
      <Heading
        title="退订与退款"
        description="退款申请成功不代表资金退回，最终状态以模拟渠道处理结果为准。"
      />
      <ErrorBox error={error} />
      <div className="panel">
        {!data ? (
          <Loading />
        ) : !data.items.length ? (
          <Empty text="暂无退款申请，可在订单详情中申请" />
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>申请 / 订单</th>
                  <th>金额</th>
                  <th>状态</th>
                  <th>原因 / 审核意见</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((f: Row) => (
                  <tr key={f.id}>
                    <td>
                      <code>{f.id.slice(0, 12)}</code>
                      <small>
                        {f.order_id.slice(0, 12)} · {date(f.created_at)}
                      </small>
                    </td>
                    <td>
                      {money(f.amount_cents)}
                      <small>模拟退款</small>
                    </td>
                    <td>
                      <Badge value={f.state} />
                    </td>
                    <td>
                      {f.reason}
                      <small>{f.review_reason}</small>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={page} total={data.total} change={setPage} />
          </>
        )}
      </div>
    </>
  );
}
function Tickets({ ctx, admin = false }: { ctx: Context; admin?: boolean }) {
  const [page, setPage] = useState(1),
    [selected, setSelected] = useState(""),
    [create, setCreate] = useState(false);
  const prefix = admin ? "/admin/tickets" : "/tickets";
  const { data, error, reload } = useData(prefix + "?page=" + page, 5000);
  return (
    <>
      <Heading
        title={admin ? "工单处理" : "服务工单"}
        description="购买、支付、开通、资源与退款问题，在这里持续跟进。"
      >
        {!admin && <button onClick={() => setCreate(true)}>＋ 新建工单</button>}
      </Heading>
      <ErrorBox error={error} />
      <div className="panel">
        {!data ? (
          <Loading />
        ) : !data.items.length ? (
          <Empty />
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>主题</th>
                  <th>类型</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.items.map((t: Row) => (
                  <tr key={t.id}>
                    <td>{t.title}</td>
                    <td>{t.type}</td>
                    <td>
                      <Badge value={t.state} />
                    </td>
                    <td>{date(t.created_at)}</td>
                    <td>
                      <button
                        className="secondary"
                        onClick={() => setSelected(t.id)}
                      >
                        查看
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={page} total={data.total} change={setPage} />
          </>
        )}
      </div>
      {create && (
        <Modal title="新建工单" close={() => setCreate(false)}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const d = Object.fromEntries(new FormData(e.currentTarget));
              ctx.run(async () => {
                await api("/tickets", "POST", d);
                setCreate(false);
                reload();
              });
            }}
          >
            <Field label="类型">
              <select name="type">
                {["PURCHASE", "PAYMENT", "PROVISION", "RESOURCE", "REFUND"].map(
                  (v) => (
                    <option key={v}>{v}</option>
                  ),
                )}
              </select>
            </Field>
            <Field label="主题">
              <input name="title" required maxLength={100} />
            </Field>
            <Field label="关联订单（选填）">
              <input name="order_id" />
            </Field>
            <Field label="关联实例（选填）">
              <input name="instance_id" />
            </Field>
            <Field label="问题描述">
              <textarea name="content" required maxLength={5000} />
            </Field>
            <button>提交工单</button>
          </form>
        </Modal>
      )}
      {selected && (
        <TicketDetail
          id={selected}
          ctx={ctx}
          admin={admin}
          close={() => {
            setSelected("");
            reload();
          }}
        />
      )}
    </>
  );
}
function TicketDetail({
  id,
  ctx,
  admin,
  close,
}: {
  id: string;
  ctx: Context;
  admin: boolean;
  close: () => void;
}) {
  const prefix = admin ? "/admin/tickets" : "/tickets";
  const { data, error, reload } = useData(prefix + "/" + id, 4000);
  return (
    <Modal title={data?.title || "工单详情"} close={close}>
      <ErrorBox error={error} />
      {data && (
        <>
          <Badge value={data.state} />
          <p>
            关联订单：{data.order_id || "无"} · 实例：{data.instance_id || "无"}{" "}
            · 处理人：{data.assignee || "未分派"}
          </p>
          {data.replies.map((v: Row) => (
            <div className={"reply " + (v.staff ? "staff" : "")} key={v.id}>
              <strong>{v.staff ? "服务人员" : "用户"}</strong>
              <small>{date(v.created_at)}</small>
              <p>{v.content}</p>
            </div>
          ))}
          {data.attachments.map((f: Row) => (
            <button
              className="ghost"
              key={f.id}
              onClick={() => ctx.run(() => download(f.id))}
            >
              ↓ {f.name}
            </button>
          ))}
          {!admin && data.state !== "CLOSED" && (
            <Field label="上传附件（PNG/JPEG/PDF，最大2MB）">
              <input
                type="file"
                accept="image/png,image/jpeg,application/pdf"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f)
                    ctx.run(async () => {
                      const d = new FormData();
                      d.append("file", f);
                      await api(`/tickets/${id}/attachments`, "POST", d);
                      reload();
                    });
                }}
              />
            </Field>
          )}
          {data.state !== "CLOSED" && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const form = e.currentTarget,
                  d = Object.fromEntries(new FormData(form));
                ctx.run(async () => {
                  await api(prefix + `/${id}/replies`, "POST", d);
                  form.reset();
                  reload();
                });
              }}
            >
              <Field label="追加回复">
                <textarea required name="content" maxLength={5000} />
              </Field>
              {admin && (
                <select name="state">
                  {["WAITING_USER", "IN_PROGRESS", "RESOLVED"].map((v) => (
                    <option value={v} key={v}>
                      {names[v]}
                    </option>
                  ))}
                </select>
              )}
              <button>发送回复</button>
            </form>
          )}
          {!admin && (
            <button
              className="ghost"
              onClick={() =>
                ctx.run(async () => {
                  await api(`/tickets/${id}`, "PATCH", { close: true });
                  reload();
                })
              }
            >
              关闭工单
            </button>
          )}
          {admin && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const d = Object.fromEntries(new FormData(e.currentTarget));
                ctx.run(async () => {
                  await api(prefix + "/" + id, "PATCH", d);
                  reload();
                });
              }}
            >
              <Field label="分派给运营 / 管理员用户ID">
                <input name="assignee" required />
              </Field>
              <button className="secondary">分派</button>
            </form>
          )}
        </>
      )}
    </Modal>
  );
}
function Notices({ ctx }: { ctx: Context }) {
  const [page, setPage] = useState(1);
  const { data, error, reload } = useData("/notifications?page=" + page, 5000);
  return (
    <>
      <Heading
        title="站内通知"
        description="支付、开通、到期、续费、退款与工单进度。"
      />
      <ErrorBox error={error} />
      <div className="panel">
        {!data ? (
          <Loading />
        ) : !data.items.length ? (
          <Empty />
        ) : (
          <>
            {data.items.map((n: Row) => (
              <div
                className={"notification " + (n.is_read ? "read" : "")}
                key={n.id}
              >
                <div>
                  <h3>
                    {!n.is_read && <span className="dot" />}
                    {n.title}
                  </h3>
                  <p>{n.content}</p>
                  <small>{date(n.created_at)}</small>
                </div>
                {!n.is_read && (
                  <button
                    className="secondary"
                    onClick={() =>
                      ctx.run(async () => {
                        await api(`/notifications/${n.id}/read`, "POST");
                        reload();
                      })
                    }
                  >
                    标记已读
                  </button>
                )}
              </div>
            ))}
            <Pager page={page} total={data.total} change={setPage} />
          </>
        )}
      </div>
    </>
  );
}
function Profile({ ctx, update }: { ctx: Context; update: (u: Row) => void }) {
  const [prefs, setPrefs] = useState<Row | null>(null);
  const payments = useData("/payments");
  return (
    <>
      <Heading
        title="用户中心"
        description="管理个人资料、登录安全与长期偏好。"
      />
      <div className="two-col">
        <div className="panel">
          <h2>个人资料</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const d = Object.fromEntries(new FormData(e.currentTarget));
              ctx.run(async () => {
                update(await api("/me", "PATCH", d));
                ctx.notice("资料已更新");
              });
            }}
          >
            <Field label="用户名">
              <input value={ctx.user?.username} disabled />
            </Field>
            <Field label="昵称">
              <input
                defaultValue={ctx.user?.nickname}
                name="nickname"
                required
                maxLength={64}
              />
            </Field>
            <button>保存资料</button>
          </form>
          <Field label="头像（PNG/JPEG，2MB以内）">
            <input
              type="file"
              accept="image/png,image/jpeg"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f)
                  ctx.run(async () => {
                    const d = new FormData();
                    d.append("file", f);
                    await api("/me/avatar", "POST", d);
                    update(await api("/me"));
                    ctx.notice("头像已上传");
                  });
              }}
            />
          </Field>
          {ctx.user?.avatarId && (
            <button
              className="ghost"
              onClick={() => ctx.run(() => download(ctx.user!.avatarId))}
            >
              查看当前头像
            </button>
          )}
          <hr />
          <h2>长期偏好</h2>
          <p>默认不提取。开启后允许从会话提取稳定偏好，可查看与删除。</p>
          <label className="check">
            <input
              type="checkbox"
              checked={!!ctx.user?.preferencesEnabled}
              onChange={(e) => {
                const checked = e.target.checked;
                ctx.run(async () =>
                  update(
                    await api("/me", "PATCH", { preferences_enabled: checked }),
                  ),
                );
              }}
            />
            允许保存长期偏好
          </label>
          <button
            className="secondary"
            onClick={() =>
              ctx.run(async () => setPrefs(await api("/me/preferences")))
            }
          >
            查看已保存偏好
          </button>
          {prefs?.items?.length === 0 && <p>暂无长期偏好</p>}
          {prefs?.items?.map((p: Row) => (
            <div className="item" key={p.id}>
              <span>{p.content}</span>
              <button
                className="danger"
                onClick={() =>
                  ctx.run(async () => {
                    await api("/me/preferences/" + p.id, "DELETE");
                    setPrefs(await api("/me/preferences"));
                  })
                }
              >
                删除
              </button>
            </div>
          ))}
        </div>
        <div className="panel">
          <h2>修改密码</h2>
          <p className="muted">修改后全部会话立即失效，需要重新登录。</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const d = Object.fromEntries(new FormData(e.currentTarget));
              ctx.run(async () => {
                await api("/me/password", "POST", d);
                setToken("");
                window.dispatchEvent(new Event("session-expired"));
                ctx.notice("密码已更新，请重新登录");
              });
            }}
          >
            <Field label="旧密码">
              <input
                type="password"
                name="old_password"
                required
                autoComplete="current-password"
              />
            </Field>
            <Field label="新密码">
              <input
                type="password"
                name="new_password"
                required
                minLength={10}
                autoComplete="new-password"
              />
            </Field>
            <button>修改并退出所有会话</button>
          </form>
          <hr />
          <h2>付款记录</h2>
          <ErrorBox error={payments.error} />
          {payments.data?.items?.map((p: Row) => (
            <p key={p.id}>
              {money(p.amount_cents)} <Badge value={p.state} />{" "}
              <small>模拟支付 · {p.id.slice(0, 12)}</small>
            </p>
          ))}
        </div>
      </div>
    </>
  );
}
function Chat({ ctx }: { ctx: Context }) {
  const sessions = useData("/chat/sessions"),
    [session, setSession] = useState(""),
    [messages, setMessages] = useState<Row[]>([]),
    [query, setQuery] = useState(""),
    [busy, setBusy] = useState(false),
    [status, setStatus] = useState("");
  const abort = useRef<AbortController | null>(null);
  useEffect(() => () => abort.current?.abort(), []);
  return (
    <>
      <Heading
        title="AI 云助手"
        description="知识问答、产品选型与本人订单查询。业务价格和状态以平台实时接口为准。"
      />
      <div className="chat-layout">
        <div className="panel chat-sessions">
          <button
            className="wide"
            onClick={() =>
              ctx.run(async () => {
                abort.current?.abort();
                const s = await api("/chat/sessions", "POST", {
                  title: "云服务咨询",
                });
                setSession(s.id);
                setMessages([]);
                sessions.reload();
              })
            }
          >
            ＋ 新会话
          </button>
          {sessions.data?.map((s: Row) => (
            <div
              className={"session " + (session === s.id ? "selected" : "")}
              key={s.id}
            >
              <button
                className="ghost"
                onClick={() =>
                  ctx.run(async () => {
                    abort.current?.abort();
                    setSession(s.id);
                    setMessages(await api("/chat/sessions/" + s.id));
                  })
                }
              >
                {s.title}
                <small>{date(s.created_at)}</small>
              </button>
              <button
                className="ghost"
                aria-label="删除会话"
                onClick={() =>
                  ctx.run(async () => {
                    if (!confirm("删除此会话记录？")) return;
                    await api("/chat/sessions/" + s.id, "DELETE");
                    if (session === s.id) {
                      abort.current?.abort();
                      setSession("");
                      setMessages([]);
                    }
                    sessions.reload();
                  })
                }
              >
                ×
              </button>
            </div>
          ))}
        </div>
        <div className="panel conversation">
          <div className="messages">
            {!messages.length && (
              <Empty text="询问云产品知识，或查询你当前登录账号的订单与实例" />
            )}
            {messages.map((m, i) => (
              <div key={i} className={"message " + m.role}>
                <strong>{m.role === "user" ? "你" : "云栈助手"}</strong>
                <p>{m.content}</p>
              </div>
            ))}
            {busy && <p className="muted">{status || "正在思考…"}</p>}
          </div>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (!query.trim() || busy) return;
              const text = query;
              setQuery("");
              setBusy(true);
              let id = session;
              try {
                if (!id) {
                  const s = await api("/chat/sessions", "POST", {
                    title: text.slice(0, 30),
                  });
                  id = s.id;
                  setSession(id);
                  sessions.reload();
                }
                setMessages((m) => [
                  ...m,
                  { role: "user", content: text },
                  { role: "assistant", content: "" },
                ]);
                abort.current = new AbortController();
                await chat(text, id, abort.current.signal, (event) => {
                  if (event.content)
                    setMessages((m) =>
                      m.map((x, i) =>
                        i === m.length - 1
                          ? { ...x, content: x.content + event.content }
                          : x,
                      ),
                    );
                  if (event.tool_status)
                    setStatus("正在检索资料与调用获准工具…");
                  if (event.error) ctx.notice(event.error);
                });
              } catch (e: any) {
                if (e.name !== "AbortError") ctx.notice(e.message);
              } finally {
                setBusy(false);
                setStatus("");
              }
            }}
          >
            <textarea
              aria-label="聊天内容"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              maxLength={10000}
              placeholder="例如：我有哪些订单？ECS 和云数据库有什么区别？"
              required
            />
            {busy ? (
              <button
                type="button"
                className="secondary"
                onClick={() => abort.current?.abort()}
              >
                停止
              </button>
            ) : (
              <button>发送 ↑</button>
            )}
          </form>
          <small>
            AI
            可能出错。订单操作请通过业务页面确认；不会通过聊天直接扣费或释放资源。
          </small>
        </div>
      </div>
      <ActionInbox ctx={ctx} />
      <ApplicationPlanInbox ctx={ctx} />
    </>
  );
}
function Admin({ ctx }: { ctx: Context }) {
  const [tab, setTab] = useState("summary"),
    [page, setPage] = useState(1),
    [edit, setEdit] = useState<Row | null>(null),
    [reason, setReason] = useState(""),
    [selectedOrder, setSelectedOrder] = useState("");
  const { data, error, reload } = useData(
    "/admin/" + tab + "?page=" + page,
    5000,
  );
  const admin = ctx.user?.role === "ADMIN";
  const tabs = [
    ["summary", "工作台"],
    ["products", "产品"],
    ["skus", "SKU / 库存"],
    ["price-plans", "价格版本"],
    ["orders", "订单"],
    ["payments", "支付"],
    ["payment-events", "支付事件"],
    ["instances", "资源"],
    ["renewals", "续费"],
    ["refunds", "退款审核"],
    ["tickets", "工单"],
    ["tasks", "履约任务"],
    ["notification-templates", "通知模板"],
    ...(admin
      ? [
          ["outbox", "事件投递"],
          ["users", "用户 / 角色"],
          ["audit-logs", "操作审计"],
        ]
      : []),
    ["legacy-orders", "历史订单"],
  ];
  const columns: Row = {
    "notification-templates": [
      "id",
      "title_template",
      "content_template",
      "version",
    ],
    products: ["id", "name", "category", "active"],
    skus: [
      "id",
      "name",
      "region",
      "cpu",
      "memory_gb",
      "base_cents",
      "capacity",
      "reserved",
      "allocated",
      "version",
      "active",
    ],
    orders: ["id", "user_id", "kind", "total_cents", "state", "created_at"],
    payments: ["id", "order_id", "amount_cents", "channel", "state"],
    instances: ["id", "user_id", "name", "state", "expires_at"],
    refunds: [
      "id",
      "order_id",
      "amount_cents",
      "reason",
      "state",
      "review_reason",
    ],
    users: ["id", "username", "nickname", "role", "enabled"],
    tasks: ["id", "type", "order_id", "state", "attempts", "error"],
    outbox: ["id", "event_key", "type", "state", "attempts"],
    "audit-logs": [
      "created_at",
      "actor_id",
      "action",
      "object_id",
      "detail",
      "request_id",
    ],
    "price-plans": ["id", "sku_id", "version", "created_at"],
    "payment-events": [
      "id",
      "payment_id",
      "amount_cents",
      "outcome",
      "created_at",
    ],
    renewals: ["order_id", "instance_id", "old_expires", "new_expires"],
    "legacy-orders": [
      "id",
      "legacy_user_id",
      "product_name",
      "amount_cents",
      "legacy_status",
      "provenance",
    ],
  };
  const productDefault = {
    name: "",
    category: "ECS",
    description: "",
    scenario: "",
    active: true,
    image_url: "",
  };
  const skuDefault = {
    product_id: "ecs",
    name: "",
    region: "cn-hangzhou",
    zone: "cn-hangzhou-a",
    cpu: 2,
    memory_gb: 4,
    base_cents: 3900,
    disk_cents: 5,
    bandwidth_cents: 300,
    capacity: 10,
    active: true,
    adapter: "SIMULATED",
    images: "ubuntu-22,rocky-9",
    min_disk: 40,
    max_disk: 500,
    max_bandwidth: 100,
    periods: "1,3,12",
    grace_days: 7,
    refund_days: 7,
  };
  return (
    <>
      <a className="back" href="#/admin/p1">
        进入 P1 运营中心：知识、优惠券、发票、推广、通知与渠道 →
      </a>
      <Heading
        title="运营管理后台"
        description="维护可售配置与库存，处理退款、异常任务和服务工单。"
      />
      <div className="tabs">
        {tabs.map(([k, title]) => (
          <button
            key={k}
            className={tab === k ? "" : "secondary"}
            onClick={() => {
              setTab(k);
              setPage(1);
            }}
          >
            {title}
          </button>
        ))}
      </div>
      {tab === "tickets" ? (
        <Tickets ctx={ctx} admin />
      ) : (
        <>
          <ErrorBox error={error} />
          {tab === "summary" ? (
            data && (
              <div className="stats">
                {Object.entries(data).map(([k, v]) => (
                  <div className="panel" key={k}>
                    <span>
                      {
                        (
                          {
                            users: "平台用户",
                            orders: "累计订单",
                            instances: "活动实例",
                            paid_cents: "模拟实付总额",
                            pending_refunds: "待审核退款",
                            manual_tasks: "人工处理任务",
                          } as Row
                        )[k]
                      }
                    </span>
                    <strong>
                      {k === "paid_cents" ? money(Number(v)) : String(v)}
                    </strong>
                  </div>
                ))}
              </div>
            )
          ) : (
            <div className="panel">
              <div className="toolbar">
                <button className="secondary" onClick={reload}>
                  刷新
                </button>
                {["products", "skus"].includes(tab) && (
                  <button
                    onClick={() =>
                      setEdit({
                        ...(tab === "notification-templates"
                          ? {
                              title_template: "{title}",
                              content_template: "{content}",
                            }
                          : tab === "products"
                            ? productDefault
                            : skuDefault),
                      })
                    }
                  >
                    ＋ 新建{tab === "products" ? "产品" : "SKU"}
                  </button>
                )}
                <span className="muted">
                  {tab === "skus"
                    ? "可售 = 总量 − 预留 − 已分配；金额单位为分"
                    : tab === "legacy-orders"
                      ? "历史导入记录，不绑定新注册账号，不伪造支付流水"
                      : ""}
                </span>
              </div>
              {!data ? (
                <Loading />
              ) : data.items?.length ? (
                <>
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          {(columns[tab] || []).map((c: string) => (
                            <th key={c}>
                              {(
                                {
                                  id: "编号",
                                  name: "名称",
                                  state: "状态",
                                  capacity: "总量",
                                  reserved: "预留",
                                  allocated: "已分配",
                                  base_cents: "基础月价(分)",
                                  enabled: "启用",
                                } as Row
                              )[c] || c}
                            </th>
                          ))}
                          <th>操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.items.map((row: Row) => (
                          <tr key={row.id || row.order_id}>
                            {(columns[tab] || []).map((c: string) => (
                              <td
                                key={c}
                                className={c === "detail" ? "audit-detail" : ""}
                              >
                                {c === "state" ? (
                                  <Badge value={row[c]} />
                                ) : typeof row[c] === "boolean" ? (
                                  row[c] ? (
                                    "是"
                                  ) : (
                                    "否"
                                  )
                                ) : c.endsWith("_at") ||
                                  c.endsWith("_expires") ? (
                                  date(row[c])
                                ) : (
                                  String(row[c] ?? "—")
                                )}
                              </td>
                            ))}
                            <td>
                              {[
                                "products",
                                "skus",
                                "notification-templates",
                              ].includes(tab) && (
                                <button
                                  className="secondary"
                                  onClick={() => setEdit({ ...row })}
                                >
                                  编辑
                                </button>
                              )}
                              {tab === "users" && (
                                <button
                                  className="secondary"
                                  onClick={() => setEdit({ ...row })}
                                >
                                  管理角色 / 状态
                                </button>
                              )}
                              {tab === "refunds" &&
                                row.state === "SUBMITTED" && (
                                  <>
                                    <button
                                      onClick={() =>
                                        setEdit({ ...row, approve: true })
                                      }
                                    >
                                      审核
                                    </button>
                                  </>
                                )}
                              {["tasks", "outbox"].includes(tab) &&
                                row.state === "MANUAL_REQUIRED" &&
                                admin && (
                                  <button onClick={() => setEdit({ ...row })}>
                                    重试
                                  </button>
                                )}
                              {tab === "orders" && (
                                <button
                                  className="ghost"
                                  onClick={() => setSelectedOrder(row.id)}
                                >
                                  快照
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <Pager page={page} total={data.total} change={setPage} />
                </>
              ) : (
                <Empty />
              )}
            </div>
          )}
        </>
      )}
      {selectedOrder && (
        <AdminOrder id={selectedOrder} close={() => setSelectedOrder("")} />
      )}
      {edit && (
        <Modal
          title={"管理操作 · " + tab}
          close={() => {
            setEdit(null);
            setReason("");
          }}
        >
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const d = { ...edit, reason };
              ctx.run(async () => {
                if (
                  ["products", "skus", "notification-templates"].includes(tab)
                )
                  await api("/admin/" + tab, "POST", d);
                else if (tab === "users")
                  await api("/admin/users/" + edit.id, "PATCH", d);
                else if (tab === "refunds")
                  await api(`/admin/refunds/${edit.id}/review`, "POST", d);
                else
                  await api(`/admin/${tab}/${edit.id}/retry`, "POST", {
                    reason,
                  });
                setEdit(null);
                setReason("");
                reload();
                ctx.notice("操作已提交并记录审计");
              });
            }}
          >
            {["products", "skus", "notification-templates"].includes(tab) &&
              Object.keys(
                tab === "notification-templates"
                  ? { title_template: "{title}", content_template: "{content}" }
                  : tab === "products"
                    ? productDefault
                    : skuDefault,
              ).map((k) => (
                <Field label={k} key={k}>
                  {typeof edit[k] === "boolean" ? (
                    <select
                      value={String(edit[k])}
                      onChange={(e) =>
                        setEdit({ ...edit, [k]: e.target.value === "true" })
                      }
                    >
                      <option value="true">启用</option>
                      <option value="false">停用</option>
                    </select>
                  ) : (
                    <input
                      required={!["image_url"].includes(k)}
                      type={typeof edit[k] === "number" ? "number" : "text"}
                      value={edit[k] ?? ""}
                      onChange={(e) =>
                        setEdit({
                          ...edit,
                          [k]:
                            typeof edit[k] === "number"
                              ? Number(e.target.value)
                              : e.target.value,
                        })
                      }
                    />
                  )}
                </Field>
              ))}
            {tab === "users" && (
              <>
                <Field label="角色">
                  <select
                    value={edit.role}
                    onChange={(e) => setEdit({ ...edit, role: e.target.value })}
                  >
                    {["USER", "OPERATOR", "ADMIN"].map((v) => (
                      <option key={v}>{v}</option>
                    ))}
                  </select>
                </Field>
                <Field label="账户状态">
                  <select
                    value={String(edit.enabled)}
                    onChange={(e) =>
                      setEdit({ ...edit, enabled: e.target.value === "true" })
                    }
                  >
                    <option value="true">启用</option>
                    <option value="false">停用</option>
                  </select>
                </Field>
              </>
            )}
            {tab === "refunds" && (
              <>
                <p>
                  金额：{money(edit.amount_cents)} · {edit.reason}
                </p>
                <Field label="审核结果">
                  <select
                    value={String(edit.approve)}
                    onChange={(e) =>
                      setEdit({ ...edit, approve: e.target.value === "true" })
                    }
                  >
                    <option value="true">通过：先处理资源，再模拟退款</option>
                    <option value="false">拒绝申请</option>
                  </select>
                </Field>
              </>
            )}
            <Field label="操作原因（必填，记录审计）">
              <textarea
                required
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
            <button>确认提交</button>
          </form>
        </Modal>
      )}
    </>
  );
}

function AdminOrder({ id, close }: { id: string; close: () => void }) {
  const { data, error } = useData("/admin/orders/" + id, 2000);
  return (
    <Modal title="订单管理详情" close={close}>
      <ErrorBox error={error} />
      {data && (
        <>
          <dl>
            <dt>订单</dt>
            <dd>{data.id}</dd>
            <dt>用户</dt>
            <dd>{data.user_id}</dd>
            <dt>状态</dt>
            <dd>
              <Badge value={data.state} />
            </dd>
            <dt>报价金额</dt>
            <dd>{money(data.total_cents)}</dd>
            <dt>已退 / 处理中</dt>
            <dd>
              {money(data.refunded_cents)} / {money(data.refund_held_cents)}
            </dd>
          </dl>
          <h3>配置及规则快照</h3>
          <pre className="json-view">
            {JSON.stringify(
              { config: parse(data.config), snapshot: parse(data.snapshot) },
              null,
              2,
            )}
          </pre>
          {["items", "payments", "tasks", "refunds"].map((k) => (
            <section key={k}>
              <h3>
                {
                  (
                    {
                      items: "逐台履约",
                      payments: "支付单",
                      tasks: "任务",
                      refunds: "退款",
                    } as Row
                  )[k]
                }
              </h3>
              {data[k].map((v: Row) => (
                <div className="item" key={v.id}>
                  <code>{v.id}</code>
                  <span>{v.type || v.channel || v.instance_id}</span>
                  <Badge value={v.state} />
                  <span>
                    {v.amount_cents ? money(v.amount_cents) : v.error}
                  </span>
                </div>
              ))}
            </section>
          ))}
        </>
      )}
    </Modal>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
