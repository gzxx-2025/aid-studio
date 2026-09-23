import axios, {
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig
} from 'axios';
import { message, Modal } from 'antd';
import { saveAs } from 'file-saver';

import { getToken } from './auth';
import errorCode from './errorCode';
import { tansParams, blobValidate, resolveAppUrl } from './ruoyi';
import cache from './cache';
import { ApiResponse } from '@/types/common';

export const isRelogin = { show: false };

// 用户手动登出时调用的钩子（注册在 store 初始化里）
let logoutHandler: (() => void | Promise<void>) | null = null;
export function registerLogoutHandler(handler: () => void | Promise<void>) {
  logoutHandler = handler;
}

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json;charset=utf-8' }
});

// 请求拦截
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const anyHeaders = (config.headers || {}) as any;
    const isToken = anyHeaders.isToken === false;
    const isRepeatSubmit = anyHeaders.repeatSubmit === false;
    const interval = anyHeaders.interval || 1000;

    const token = getToken();
    if (token && !isToken) {
      config.headers.set('Authorization', 'Bearer ' + token);
    }

    // get 请求 params 拼接（保持与原 aid-manager 一致）
    if (config.method === 'get' && config.params) {
      let url = config.url + '?' + tansParams(config.params);
      url = url.slice(0, -1);
      config.params = {};
      config.url = url;
    }

    // 防重复提交
    // 仅拦截"写操作"，放行以 POST 发起的查询类接口（列表/详情/分页/导出等），
    // 避免以 POST 查询的页面在切换或重查时被误判为重复提交。
    const reqUrl = config.url || '';
    const isQueryLike = /\/(list|page|query|detail|export|tree|getInfo|getNames|getKeys|getValue)(\/|\?|$)/i.test(reqUrl);
    if (!isRepeatSubmit && !isQueryLike && (config.method === 'post' || config.method === 'put')) {
      const requestObj = {
        url: config.url,
        data:
          typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
        time: new Date().getTime()
      };
      const requestStr = JSON.stringify(requestObj);
      const size = requestStr.length;
      const limit = 5 * 1024 * 1024;
      if (size < limit) {
        const sessionObj = cache.session.getJSON('sessionObj');
        if (!sessionObj) {
          cache.session.setJSON('sessionObj', requestObj);
        } else {
          const { url, data, time } = sessionObj;
          if (
            data === requestObj.data &&
            requestObj.time - time < interval &&
            url === requestObj.url
          ) {
            return Promise.reject(new Error('数据正在处理，请勿重复提交'));
          }
          cache.session.setJSON('sessionObj', requestObj);
        }
      }
    }

    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截
service.interceptors.response.use(
  (res: AxiosResponse<ApiResponse>) => {
    const code = res.data.code || 200;
    // 优先使用后端返回的 msg，仅在 msg 缺失时回退到默认映射
    const msg = res.data.msg || errorCode[String(code)] || errorCode.default;

    // 二进制数据直通
    if (
      res.request?.responseType === 'blob' ||
      res.request?.responseType === 'arraybuffer'
    ) {
      return res.data as any;
    }

    if (code === 401) {
      if (!isRelogin.show) {
        isRelogin.show = true;
        const modal = Modal.confirm({
          title: '系统提示',
          content: '登录状态已过期，您可以继续留在该页面，或者重新登录',
          okText: '重新登录',
          cancelText: '取消',
          onOk: async () => {
            if (logoutHandler) await logoutHandler();
            window.location.href = resolveAppUrl('/login');
          },
          onCancel: () => {
            isRelogin.show = false;
          },
          afterClose: () => {
            // 确保弹窗完全关闭后（含动画）才允许下一次弹出
            isRelogin.show = false;
          }
        });
        // 安全兜底：30s 后兜底重置，防止 antd 内部异常导致标志永锁
        setTimeout(() => {
          if (isRelogin.show) {
            try { modal.destroy(); } catch { /* noop */ }
            isRelogin.show = false;
          }
        }, 30000);
      }
      return Promise.reject(new Error('无效的会话，或者会话已过期，请重新登录。'));
    }

    if (code === 500) {
      message.error(msg);
      return Promise.reject(new Error(msg));
    }

    if (code === 601) {
      message.warning(msg);
      return Promise.reject(new Error(msg));
    }

    if (code !== 200) {
      message.error({ content: msg, duration: 3 });
      return Promise.reject(new Error(msg));
    }

    return res.data as any;
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error);
    let msg = error?.message || '请求失败';
    if (msg === 'Network Error') msg = '后端接口连接异常';
    else if (msg.includes('timeout')) msg = '系统接口请求超时';
    else if (error?.response?.status) {
      msg = `系统接口 ${error.response.status} 异常`;
    }
    message.error({ content: msg, duration: 5 });
    return Promise.reject(error);
  }
);

/** 通用下载方法（与 aid-manager 保持一致） */
export function download(
  url: string,
  params: Record<string, any>,
  filename: string,
  config?: AxiosRequestConfig
) {
  const hide = message.loading('正在下载数据，请稍候', 0);
  return service
    .post(url, params, {
      transformRequest: [(p) => tansParams(p)],
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      responseType: 'blob',
      ...config
    })
    .then(async (data: any) => {
      const isBlob = blobValidate(data);
      if (isBlob) {
        const blob = new Blob([data]);
        saveAs(blob, filename);
      } else {
        const text = await data.text();
        try {
          const obj = JSON.parse(text);
          message.error(obj.msg || errorCode[String(obj.code)] || errorCode.default);
        } catch {
          message.error(errorCode.default);
        }
      }
      hide();
    })
    .catch((e) => {
      console.error(e);
      message.error('下载文件出现错误，请联系管理员！');
      hide();
    });
}

/** GET 方式下载 zip（对齐 Vue 版 $download.zip） */
export function downloadZip(url: string, filename: string) {
  const hide = message.loading('正在下载数据，请稍候', 0);
  return service
    .get(url, { responseType: 'blob' })
    .then(async (data: any) => {
      const isBlob = blobValidate(data);
      if (isBlob) {
        const blob = new Blob([data], { type: 'application/zip' });
        saveAs(blob, filename);
      } else {
        const text = await data.text();
        try {
          const obj = JSON.parse(text);
          message.error(obj.msg || errorCode[String(obj.code)] || errorCode.default);
        } catch {
          message.error(errorCode.default);
        }
      }
      hide();
    })
    .catch((e) => {
      console.error(e);
      message.error('下载文件出现错误，请联系管理员！');
      hide();
    });
}

export default service;

/** 类型化的 request 方法 —— 返回 data，与后端 ApiResponse 同形 */
export function request<T = any>(config: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.request(config) as unknown as Promise<ApiResponse<T>>;
}
