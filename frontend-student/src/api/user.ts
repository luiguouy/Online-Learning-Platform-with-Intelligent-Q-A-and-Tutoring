import request from '@/utils/request';
import type { UpdateProfileDTO, UserProfile } from '@/types';

/**
 * 获取当前登录学生个人主页资料
 * GET /api/user/profile
 */
export async function getUserProfile(): Promise<UserProfile> {
  return request.get<unknown, UserProfile>('/user/profile');
}

/**
 * 更新当前登录学生个人资料
 * PUT /api/user/profile
 */
export async function updateUserProfile(data: UpdateProfileDTO): Promise<UserProfile> {
  return request.put<unknown, UserProfile>('/user/profile', data);
}

/**
 * 上传头像图片
 * POST /api/user/avatar
 */
export async function uploadAvatar(file: File): Promise<{ avatarUrl: string }> {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<unknown, { avatarUrl: string }>('/user/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
