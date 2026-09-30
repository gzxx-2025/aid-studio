const BRAND_EXTENSIONS: Record<string, string> = {
  dashscope: 'jpg', volcengine: 'jpg', jimeng: 'jpg', gemini: 'png',
  openai: 'png', volcengine_tts: 'jpg', minimax: 'png', agnes: 'png',
  vidu: 'jpg', deepseek: 'jpg', kling: 'png', tokendance: 'png', topaz: 'ico', wavespeed: 'ico'
};

/** An uploaded URL always wins; a packaged icon fills an unset public provider. */
export function resolveProviderLogo(providerCode?: string | null, configuredUrl?: string | null): string | undefined {
  const custom = configuredUrl?.trim();
  if (custom) return custom;
  const code = providerCode?.trim().toLowerCase() || '';
  if (code === 'tencent_ci_media' || code === 'tencent_mps') return '/brand-icons/tencent-cloud.ico';
  const extension = BRAND_EXTENSIONS[code];
  return extension ? `/brand-icons/${code}.${extension}` : undefined;
}

export const BUILTIN_AVATARS = Array.from({ length: 5 }, (_, index) => `/default-avatars/${index + 1}.png`);
export const BUILTIN_CAPTCHA_BACKGROUNDS = Array.from({ length: 4 }, (_, index) => `/captcha-backgrounds/${index + 1}.png`);
