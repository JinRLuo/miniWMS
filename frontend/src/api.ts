export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

export function params(values: Record<string, string | number | boolean | undefined>) {
  const result = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== '') result.set(key, String(value))
  }
  return result.size ? '?' + result.toString() : ''
}

async function csrfToken() {
  const cookie = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))
  if (cookie) return decodeURIComponent(cookie.slice('XSRF-TOKEN='.length))
  return (await api<{ token: string }>('/auth/csrf')).token
}

export async function api<T>(path: string, body?: unknown, method = body === undefined ? 'GET' : 'POST'): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  let encoded: BodyInit | undefined
  if (method !== 'GET') headers.set('X-XSRF-TOKEN', await csrfToken())
  if (body instanceof URLSearchParams) encoded = body
  else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    encoded = JSON.stringify(body)
  }
  const abort = new AbortController()
  const timer = window.setTimeout(() => abort.abort(), 20000)
  let response: Response
  try {
    response = await fetch('/api' + path, { method, headers, body: encoded, signal: abort.signal })
  } catch {
    throw new ApiError(0, '暂时无法连接服务器，请检查网络后重试')
  } finally { window.clearTimeout(timer) }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/me') {
      window.dispatchEvent(new Event('wms:session-expired'))
    }
    throw new ApiError(response.status, data.message || '请求失败（' + response.status + '）')
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export const errorMessage = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'
