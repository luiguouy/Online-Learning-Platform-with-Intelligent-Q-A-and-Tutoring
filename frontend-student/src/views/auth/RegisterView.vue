<template>
  <div class="register-page">
    <el-card class="register-card" shadow="always">
      <div class="register-brand">
        <h1 class="register-title">智能答疑平台</h1>
        <p class="register-subtitle">注册学生账号</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="4-20 位字母、数字或下划线" :prefix-icon="User" clearable />
        </el-form-item>

        <el-form-item label="姓名" prop="nickname">
          <el-input v-model="form.nickname" placeholder="请输入真实姓名" :prefix-icon="Postcard" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="8-32 位，含字母与数字"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button class="register-button" type="primary" :loading="loading" @click="handleSubmit">
          注 册
        </el-button>

        <div class="register-footer">
          <el-link type="primary" :underline="false" @click="goLogin">已有账号？去登录</el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端注册页（官方功能，仅面向学生）
 * 后端只收 username/password/nickname 并固定落 STUDENT，教师端不提供注册入口。
 * 表单规则须与后端 RegisterDTO 的 @Pattern/@Size 保持一致，改一边必须同步另一边。
 *
 * 后端约定：POST /api/auth/register 固定落 STUDENT 角色，注册成功即登录
 * 并返回 LoginVO，因此本页成功后直接 setSession 进工作台，无需二次登录。
 */
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Lock, Postcard, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';

import { register } from '@/api/auth';
import { useUserStore } from '@/stores/userStore';
// 口令与姓名规则走共享模块，与后端 StrongPassword / ValidNickname 同一口径；
// 改阈值需三处同改（后端约束、utils/validators.ts、backend/README §3.1.1）
import { validateNickname, validatePassword } from '@/utils/validators';

const router = useRouter();
const userStore = useUserStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' });

const validateConfirm = (_rule: unknown, value: string, callback: (e?: Error) => void): void => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'));
    return;
  }
  callback();
};

const rules: FormRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '账号须为 4-20 位字母、数字或下划线', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { validator: validateNickname, trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { validator: validatePassword, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
};

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return;

  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const result = await register(form.username, form.password, form.nickname);

    // 后端固定返回 STUDENT；防御性校验与登录页保持一致
    if (result.role !== 'STUDENT') {
      ElMessage.error('注册异常，请重试');
      return;
    }

    userStore.setSession(result);
    ElMessage.success('注册成功，已自动登录');
    await router.replace('/student/chat');
  } catch {
    // 失败提示（用户名已存在 / 参数校验失败）由 request.ts 响应拦截器统一弹出
  } finally {
    loading.value = false;
  }
}

async function goLogin(): Promise<void> {
  await router.push('/login');
}
</script>

<style scoped>
.register-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 55%, #f5f3ff 100%);
}

.register-card {
  width: 400px;
  padding: 8px 12px 20px;
  border-radius: 12px;
}

.register-brand {
  margin-bottom: 22px;
  text-align: center;
}

.register-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: 1px;
}

.register-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}

.register-button {
  width: 100%;
  margin-top: 4px;
}

.register-footer {
  margin-top: 14px;
  text-align: center;
}
</style>
