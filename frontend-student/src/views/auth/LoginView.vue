<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-brand">
        <h1 class="login-title">智能答疑平台</h1>
        <p class="login-subtitle">学生问答工作台</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入账号" :prefix-icon="User" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button class="login-button" type="primary" :loading="loading" @click="handleSubmit">
          登 录
        </el-button>
      </el-form>

      <el-alert
        v-if="USE_MOCK"
        class="login-tip"
        type="info"
        :closable="false"
        show-icon
        title="当前为 Mock 阶段（后端登录接口 B1.4 未就绪）"
        description="可用账号：student01 / 123456、student02 / 123456（学生）；teacher01 / 123456（教师，用于验证被拦截）。"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端登录页（C1.4）
 * 说明：本期无注册功能（AGENT_INSTRUCTIONS.md 2.3），账号由 data.sql 种子数据预置。
 * 与教师端 LoginView 的差异：本页只接受 STUDENT 角色，教师账号登录成功也不得进入工作台。
 */
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Lock, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';

import { login } from '@/api/auth';
import { useUserStore } from '@/stores/userStore';
import { USE_MOCK } from '@/config';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ username: '', password: '' });

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return;

  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const result = await login(form.username, form.password);

    // 学生端只接受 STUDENT 角色，教师账号不得进入学生工作台
    if (result.role !== 'STUDENT') {
      ElMessage.error('请使用学生账号登录');
      return;
    }

    userStore.setSession(result);
    ElMessage.success('登录成功');

    const redirect = route.query.redirect;
    await router.replace(typeof redirect === 'string' ? redirect : '/student/chat');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败');
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 55%, #f5f3ff 100%);
}

.login-card {
  width: 400px;
  padding: 8px 12px 20px;
  border-radius: 12px;
}

.login-brand {
  margin-bottom: 22px;
  text-align: center;
}

.login-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: 1px;
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}

.login-button {
  width: 100%;
  margin-top: 4px;
}

.login-tip {
  margin-top: 18px;
}
</style>
