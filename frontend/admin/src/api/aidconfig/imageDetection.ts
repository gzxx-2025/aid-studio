import { request } from '@/utils/request';

export type ImageDetectionConfig = {
  enabled: boolean;
  provider: 'tencent_ci';
  credentialSource: 'COS_STORAGE' | 'DEDICATED';
  region: string;
  bucketName: string;
  secretId: string;
  secretKey: string;
  cosImageAccessMode: 'COS_OBJECT' | 'PUBLIC_URL';
  connectTimeoutMs: number;
  readTimeoutMs: number;
  maxCallsPerUserMinute: number;
  uploadMode: string;
};

export type ImageDetectionTestResult = {
  status: string;
  providerCalls: number;
  objectCount: number;
  elapsedMs: number;
  error: string | null;
  providerRequestId: string | null;
};

export const getImageDetectionConfig = () => request({ url: '/aidconfig/image-detection/config', method: 'get' });
export const saveImageDetectionConfig = (data: Partial<ImageDetectionConfig>) =>
  request({ url: '/aidconfig/image-detection/config', method: 'post', data });
export const testImageDetection = (data: { sourceType: 'COS_OBJECT' | 'VERIFIED_URL'; objectKey?: string; imageUrl?: string }) =>
  request({ url: '/aidconfig/image-detection/test', method: 'post', data, headers: { repeatSubmit: false }, timeout: 150000 });
