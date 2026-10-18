<template>
  <div class="profile-view">
    <!-- 顶部导航条 -->
    <header class="profile-header">
      <div class="header-left">
        <el-button link class="back-btn" @click="goBackToChat">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回答疑工作台</span>
        </el-button>
        <span class="header-divider">/</span>
        <span class="header-title">个人中心与主页</span>
      </div>
      <div class="header-right">
        <el-tag type="success" size="small" effect="plain" class="role-badge">
          <el-icon><CircleCheck /></el-icon>
          <span>已完成学生认证</span>
        </el-tag>
      </div>
    </header>

    <el-scrollbar class="profile-scroll">
      <div class="profile-container">
        <!-- 顶部个人形象 Banner 卡片 -->
        <div class="hero-banner-card">
          <div class="banner-cover"></div>
          <div class="banner-body">
            <!-- 头像与更换遮罩 -->
            <div class="avatar-wrapper" @click="triggerAvatarSelect">
              <input
                ref="avatarInputRef"
                type="file"
                accept="image/*"
                style="display: none"
                @change="handleAvatarSelected"
              />
              <img
                v-if="profileForm.avatarUrl && !avatarLoadError"
                :src="profileForm.avatarUrl"
                alt="头像"
                class="avatar-img"
                @error="avatarLoadError = true"
              />
              <div v-else class="avatar-placeholder">
                {{ userInitial }}
              </div>
              <div class="avatar-hover-mask">
                <el-icon :size="20"><Camera /></el-icon>
                <span>更换头像</span>
              </div>
              <div v-if="uploadingAvatar" class="avatar-loading-mask">
                <el-icon class="is-loading" :size="22"><Loading /></el-icon>
              </div>
            </div>

            <!-- 用户头部概览信息 -->
            <div class="user-header-info">
              <div class="name-row">
                <h2 class="display-name">{{ profileForm.nickname || '未设置昵称' }}</h2>
                <span class="username-tag">学号: {{ profileForm.username || '--' }}</span>
              </div>
              <p class="display-bio">
                {{ profileForm.bio || '这个人很低调，还没有填写个性签名~' }}
              </p>
            </div>
          </div>
        </div>

        <!-- 详细内容网格：左侧资料编辑 + 右侧学情卡片 -->
        <div class="content-grid">
          <!-- 左侧：资料编辑表单卡片 -->
          <div class="card form-card">
            <div class="card-header">
              <div class="card-title-group">
                <el-icon class="card-title-icon"><User /></el-icon>
                <h3 class="card-title">基本信息设置</h3>
              </div>
              <span class="card-subtitle">定制您在智能答疑平台中的展示名与个人印记</span>
            </div>

            <el-form
              ref="formRef"
              :model="profileForm"
              :rules="formRules"
              label-position="top"
              class="profile-form"
            >
              <el-form-item label="登录学号 / 账号">
                <el-input
                  v-model="profileForm.username"
                  disabled
                  placeholder="学号"
                >
                  <template #prefix>
                    <el-icon><Key /></el-icon>
                  </template>
                </el-input>
                <div class="field-hint">学号为您在教务系统的唯一认证标识，不可修改</div>
              </el-form-item>

              <el-form-item label="学生昵称" prop="nickname">
                <el-input
                  v-model="profileForm.nickname"
                  placeholder="请输入您的昵称（1-30字符）"
                  maxlength="30"
                  show-word-limit
                  clearable
                >
                  <template #prefix>
                    <el-icon><EditPen /></el-icon>
                  </template>
                </el-input>
                <div class="field-hint">用于提问气泡、师生互动与个人主页的展示名称</div>
              </el-form-item>

              <el-form-item label="个性签名" prop="bio">
                <el-input
                  v-model="profileForm.bio"
                  type="textarea"
                  :rows="3"
                  placeholder="写一句个性的签名，展现您的学习志向（如：专注操作系统原理与底层架构）..."
                  maxlength="100"
                  show-word-limit
                />
              </el-form-item>

              <div class="form-actions">
                <el-button
                  type="primary"
                  class="save-btn"
                  :loading="saving"
                  @click="handleSaveProfile"
                >
                  <el-icon><Check /></el-icon>
                  <span>保存修改</span>
                </el-button>
                <el-button @click="resetForm">重置</el-button>
              </div>
            </el-form>
          </div>

          <!-- 右侧：学情与统计卡片 -->
          <div class="card stat-card">
            <div class="card-header">
              <div class="card-title-group">
                <el-icon class="card-title-icon"><DataAnalysis /></el-icon>
                <h3 class="card-title">学习互动概览</h3>
              </div>
              <span class="card-subtitle">记录您与 AI 智能学伴的每一次探索</span>
            </div>

            <div class="stat-items">
              <div class="stat-box">
                <div class="stat-icon-wrap icon-purple">
                  <el-icon :size="24"><ChatDotRound /></el-icon>
                </div>
                <div class="stat-meta">
                  <span class="stat-label">发起的答疑会话</span>
                  <span class="stat-number">{{ stats.sessionCount }}</span>
                </div>
              </div>

              <div class="stat-box">
                <div class="stat-icon-wrap icon-emerald">
                  <el-icon :size="24"><Collection /></el-icon>
                </div>
                <div class="stat-meta">
                  <span class="stat-label">累计问答交互数</span>
                  <span class="stat-number">{{ stats.recordCount }}</span>
                </div>
              </div>
            </div>

            <div class="learning-quote">
              <div class="quote-title">💡 学习印记</div>
              <p class="quote-text">
                “每一次探索与追问，都是理解深入的起点；AI 学伴始终在课程知识库的每一章节陪伴您的思考。”
              </p>
              <div class="quote-meta">
                <span>注册时间：{{ formatJoinDate(stats.createdAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';
import {
  ArrowLeft,
  Camera,
  ChatDotRound,
  Check,
  CircleCheck,
  Collection,
  DataAnalysis,
  EditPen,
  Key,
  Loading,
  User,
} from '@element-plus/icons-vue';

import { getUserProfile, updateUserProfile, uploadAvatar } from '@/api/user';
import { useUserStore } from '@/stores/userStore';
import type { UserProfile } from '@/types';
// 昵称规则与注册页、后端 ValidNickname 同一来源，不在本文件重复写一遍
import { validateNickname } from '@/utils/validators';

const router = useRouter();
const userStore = useUserStore();

const formRef = ref<FormInstance>();
const avatarInputRef = ref<HTMLInputElement | null>(null);

const saving = ref(false);
const uploadingAvatar = ref(false);
const avatarLoadError = ref(false);

const profileForm = reactive({
  username: '',
  nickname: '',
  avatarUrl: '',
  bio: '',
});

const stats = reactive({
  sessionCount: 0,
  recordCount: 0,
  createdAt: '',
});

let originalProfile: UserProfile | null = null;

const formRules: FormRules = {
  nickname: [
    { required: true, message: '请输入学生昵称', trigger: 'blur' },
    // 长度与字符卫生均交给共享规则（与后端同一口径）；
    // 以前这里只卡 1-30 长度，会出现“前端能提交、后端 400”的割裂
    { validator: validateNickname, trigger: 'blur' },
  ],
  bio: [
    // 上限与后端 UpdateProfileDTO 的 @Size(max=150) 一致，改一边必须同步另一边
    { max: 150, message: '个性签名不能超过 150 个字符', trigger: 'blur' },
  ],
};

const userInitial = computed(() => {
  const name = profileForm.nickname || userStore.nickname || '学';
  return name.slice(0, 1).toUpperCase();
});

function goBackToChat(): void {
  router.push('/student/chat');
}

function formatJoinDate(dateStr?: string): string {
  if (!dateStr) return '2026 年';
  return dateStr.replace('T', ' ').slice(0, 10);
}

function triggerAvatarSelect(): void {
  if (uploadingAvatar.value) return;
  avatarInputRef.value?.click();
}

async function handleAvatarSelected(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;

  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('头像图片不能超过 5MB');
    target.value = '';
    return;
  }

  uploadingAvatar.value = true;
  try {
    const res = await uploadAvatar(file);
    profileForm.avatarUrl = res.avatarUrl;
    avatarLoadError.value = false;
    ElMessage.success('头像上传成功，点击“保存修改”后生效');
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '头像上传失败';
    ElMessage.error(msg);
  } finally {
    uploadingAvatar.value = false;
    target.value = '';
  }
}

async function loadProfileData(): Promise<void> {
  try {
    const profile = await getUserProfile();
    originalProfile = profile;
    profileForm.username = profile.username;
    profileForm.nickname = profile.nickname;
    profileForm.avatarUrl = profile.avatarUrl || '';
    profileForm.bio = profile.bio || '';
    avatarLoadError.value = false;

    stats.sessionCount = profile.sessionCount ?? 0;
    stats.recordCount = profile.recordCount ?? 0;
    stats.createdAt = profile.createdAt || '';

    userStore.setProfile(profile);
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '加载个人资料失败';
    ElMessage.error(msg);
  }
}

async function handleSaveProfile(): Promise<void> {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    saving.value = true;
    try {
      const updated = await updateUserProfile({
        nickname: profileForm.nickname,
        avatarUrl: profileForm.avatarUrl,
        bio: profileForm.bio,
      });
      originalProfile = updated;
      userStore.setProfile(updated);
      ElMessage.success('个人资料保存成功！');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '保存失败，请稍后重试';
      ElMessage.error(msg);
    } finally {
      saving.value = false;
    }
  });
}

function resetForm(): void {
  if (!originalProfile) return;
  profileForm.nickname = originalProfile.nickname;
  profileForm.avatarUrl = originalProfile.avatarUrl || '';
  profileForm.bio = originalProfile.bio || '';
}

onMounted(() => {
  void loadProfileData();
});
</script>

<style scoped>
.profile-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background-color: var(--app-bg);
}

/* 顶部导航 Header */
.profile-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 28px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.back-btn {
  font-size: 13px;
  color: #475569;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}

.back-btn:hover {
  color: #2563eb;
}

.header-divider {
  color: #cbd5e1;
  font-size: 13px;
}

.header-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--app-text);
}

.role-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
  border-radius: 4px;
}

/* 滚动与容器 */
.profile-scroll {
  flex: 1;
  min-height: 0;
}

.profile-container {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 20px 48px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 顶部 Banner 卡片 */
.hero-banner-card {
  background: var(--app-surface);
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--app-border);
}

.banner-cover {
  height: 100px;
  background: #1e3a8a;
  position: relative;
}

.banner-body {
  padding: 0 28px 20px;
  display: flex;
  align-items: flex-end;
  gap: 20px;
  position: relative;
  margin-top: -36px;
}

/* 头像与更换遮罩 */
.avatar-wrapper {
  position: relative;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  border: 3px solid #ffffff;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  background: #ffffff;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  background: #2563eb;
  color: #ffffff;
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-hover-mask {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.65);
  color: #ffffff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 11px;
  font-weight: 500;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.avatar-wrapper:hover .avatar-hover-mask {
  opacity: 1;
}

.avatar-loading-mask {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.7);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-header-info {
  flex: 1;
  padding-bottom: 2px;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.display-name {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.username-tag {
  font-size: 12px;
  background: #f1f5f9;
  color: #64748b;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}

.display-bio {
  font-size: 13px;
  color: #64748b;
  margin: 4px 0 0;
  line-height: 1.5;
}

/* 内容网格 */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 20px;
}

@media (max-width: 860px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

/* 卡片统一规范 */
.card {
  background: var(--app-surface);
  border-radius: 8px;
  border: 1px solid var(--app-border);
  padding: 20px;
}

.card-header {
  margin-bottom: 16px;
}

.card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-title-icon {
  color: #2563eb;
  font-size: 18px;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.card-subtitle {
  font-size: 12px;
  color: #94a3b8;
  display: block;
  margin-top: 3px;
}

/* 表单样式 */
.profile-form {
  margin-top: 6px;
}

.field-hint {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
  padding-top: 14px;
  border-top: 1px solid var(--app-border-subtle);
}

.save-btn {
  background: #2563eb;
  border: none;
  font-weight: 500;
  padding: 8px 20px;
  border-radius: 6px;
}

.save-btn:hover {
  background: #1d4ed8;
}

/* 统计卡片 */
.stat-items {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.stat-box {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--app-border);
}

.stat-icon-wrap {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.icon-purple {
  background: #eff6ff;
  color: #2563eb;
}

.icon-emerald {
  background: #ecfdf5;
  color: #059669;
}

.stat-meta {
  display: flex;
  flex-direction: column;
}

.stat-label {
  font-size: 12px;
  color: #64748b;
}

.stat-number {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.2;
  margin-top: 2px;
}

.learning-quote {
  padding: 14px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--app-border);
}

.quote-title {
  font-size: 12px;
  font-weight: 600;
  color: #1e40af;
  margin-bottom: 4px;
}

.quote-text {
  font-size: 12px;
  color: #334155;
  line-height: 1.6;
  margin: 0;
}

.quote-meta {
  margin-top: 8px;
  font-size: 11px;
  color: #94a3b8;
  text-align: right;
}
</style>
