/** JSON接口覆盖连接和响应体读取的超时；SSE使用独立流式请求，不受此限制。 */
export async function requestJson(url: string, options: RequestInit = {}, timeoutMs = 30000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    let data: any;
    try {
      data = await response.json();
    } catch (error) {
      if (controller.signal.aborted) throw error;
      throw new Error(`服务响应异常（HTTP ${response.status}），请检查后端或连接隧道`);
    }
    return { response, data };
  } catch (error) {
    if (controller.signal.aborted)
      throw new Error("请求超时，请检查网络或云端服务；提交类操作请先查询结果再重试");
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
