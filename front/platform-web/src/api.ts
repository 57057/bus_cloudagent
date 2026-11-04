export type Row = Record<string, any>;
let accessToken = "";
let refreshPromise: Promise<Row> | null = null;
export function setToken(token: string) {
  accessToken = token;
}
export function csrf() {
  return (
    document.cookie
      .split("; ")
      .find((c) => c.startsWith("XSRF-TOKEN="))
      ?.slice(11) || ""
  );
}
export async function refresh(): Promise<Row> {
  if (!refreshPromise)
    refreshPromise = fetch("/api/auth/refresh", {
      method: "POST",
      credentials: "include",
      headers: { "X-CSRF-Token": csrf() },
    })
      .then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.message);
        setToken(data.data.access_token);
        return data.data;
      })
      .finally(() => {
        refreshPromise = null;
      });
  return refreshPromise;
}
export async function api(
  path: string,
  method = "GET",
  data?: unknown,
  key?: string,
  retry = true,
): Promise<any> {
  const headers: Record<string, string> = { "X-CSRF-Token": csrf() };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (key) headers["Idempotency-Key"] = key;
  if (data && !(data instanceof FormData))
    headers["Content-Type"] = "application/json";
  const response = await fetch("/api" + path, {
    method,
    credentials: "include",
    headers,
    body:
      data === undefined
        ? undefined
        : data instanceof FormData
          ? data
          : JSON.stringify(data),
  });
  if (response.status === 401 && retry && !path.startsWith("/auth/")) {
    try {
      await refresh();
    } catch {
      setToken("");
      window.dispatchEvent(new Event("session-expired"));
      throw new Error("登录已过期，请重新登录");
    }
    return api(path, method, data, key, false);
  }
  const result = await response.json();
  if (!response.ok)
    throw new Error(
      `${result.message || "请求失败"}${result.requestId ? `（请求号 ${result.requestId.slice(0, 8)}）` : ""}`,
    );
  return result.data;
}
export async function download(id: string) {
  const response = await fetch(`/api/attachments/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) throw new Error("附件读取失败，请刷新登录状态");
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = "附件";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
export async function chat(
  query: string,
  session_id: string,
  signal: AbortSignal,
  receive: (event: Row) => void,
) {
  await refresh();
  const response = await fetch("/api/chat", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({ query, session_id }),
    signal,
  });
  if (!response.ok) {
    const e = await response.json();
    throw new Error(e.message || "AI服务不可用");
  }
  const reader = response.body!.getReader(),
    decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let pos;
      while ((pos = buffer.indexOf("\n\n")) >= 0) {
        const frame = buffer.slice(0, pos);
        buffer = buffer.slice(pos + 2);
        for (const line of frame.split("\n"))
          if (line.startsWith("data: ")) receive(JSON.parse(line.slice(6)));
      }
    }
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
}
export const money = (n: number) =>
  new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" }).format(
    (n || 0) / 100,
  );
export const date = (v: string | number) =>
  v ? new Date(v).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : "—";
export const parse = (v: any): Row =>
  typeof v === "string" ? JSON.parse(v) : v || {};
export const key = () => crypto.randomUUID();
