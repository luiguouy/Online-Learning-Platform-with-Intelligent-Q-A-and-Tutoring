<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-brand">
        <h1 class="login-title">智能答疑平台</h1>
        <p class="login-subtitle">教师管理后台</p>
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
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 登录页
 * 说明：本期无注册功能（AGENT_INSTRUCTIONS.md 2.3），账号由种子数据预置。
 * 该页面原本属于成员 C 的交付范围（C1.4），因教师端为独立工程、
 * 角色守卫必须有一个登录入口才能自测，故在此实现最小可用版本（已记入 README 待确认项）。
 * D2.1（2026-09-17）：已对接真实登录接口，移除 Mock 阶段提示条。
 */
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Lock, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';

import { login } from '@/api/auth';
import { useUserStore } from '@/stores/user';

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

    // 教师端只接受 TEACHER 角色，学生账号即使登录成功也不得进入后台
    if (result.role !== 'TEACHER') {
      ElMessage.error('无权访问教师管理后台');
      return;
    }

    userStore.setSession(result);
    ElMessage.success('登录成功');

    const redirect = route.query.redirect;
    await router.replace(typeof redirect === 'string' ? redirect : '/teacher/docs');
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
</style>
