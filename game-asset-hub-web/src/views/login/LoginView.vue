<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, Message, User } from '@element-plus/icons-vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { fetchCurrentUser, login as loginApi, register as registerApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useProjectStore } from '@/stores/project'

/**
 * 登录 / 注册页。
 *
 * 两个表单放同一个页面用 tab 切换：注册完通常紧接着就要登录，
 * 跳来跳去反而多一步。注册成功后自动填好登录名并切到登录 tab。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const projectStore = useProjectStore()

const activeTab = ref<'login' | 'register'>('login')
const submitting = ref(false)

const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  email: ''
})

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { min: 3, max: 50, message: '长度需在 3-50 个字符之间', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '只能包含字母、数字与下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '长度需在 6-32 个字符之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function handleLogin() {
  const form = loginFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const result = await loginApi(loginForm.username.trim(), loginForm.password)
    userStore.setLogin(result)

    // 登录后立刻拉一次项目上下文：顶栏切换器与 X-Project-Id 都要用
    try {
      const me = await fetchCurrentUser()
      userStore.setFromCurrentUser(me)
      projectStore.setProjects(me.projects)
    } catch {
      /* 拉取失败不阻塞进入，页面里还会再拉一次 */
    }

    ElMessage.success(`欢迎回来，${userStore.displayName}`)
    const redirect = route.query.redirect as string | undefined
    await router.push(redirect || '/projects')
  } catch {
    // 拦截器已提示具体原因（登录名或密码错误）
  } finally {
    submitting.value = false
  }
}

async function handleRegister() {
  const form = registerFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await registerApi({
      username: registerForm.username.trim(),
      password: registerForm.password,
      nickname: registerForm.nickname.trim() || undefined,
      email: registerForm.email.trim() || undefined
    })
    ElMessage.success('注册成功，请使用新账号登录')
    loginForm.username = registerForm.username.trim()
    loginForm.password = ''
    activeTab.value = 'login'
  } catch {
    /* 拦截器已提示（用户名/邮箱已占用等） */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__theme">
      <ThemeToggle />
    </div>

    <!-- 左侧品牌区：说明这个系统解决什么协作问题，而不是一句空洞的欢迎语 -->
    <section class="login__intro">
      <div class="login__logo">GA</div>
      <h1 class="login__title">GameAsset Hub</h1>
      <p class="login__subtitle">游戏项目资产与协作管理系统</p>

      <ul class="login__features">
        <li>
          <span class="login__dot" />
          <div>
            <strong>美术资产版本管理</strong>
            <p>模型 / 贴图 / 蓝图按版本迭代，历史可追溯、可回滚</p>
          </div>
        </li>
        <li>
          <span class="login__dot" />
          <div>
            <strong>任务看板与协作</strong>
            <p>看板拖拽推进任务，任务与资产双向关联</p>
          </div>
        </li>
        <li>
          <span class="login__dot" />
          <div>
            <strong>项目级权限隔离</strong>
            <p>按负责人 / 美术 / 程序 / 策划 / 外部协作者分配权限</p>
          </div>
        </li>
      </ul>

      <div class="login__demo">
        <div class="login__demo-title">演示账号（密码均为 123456）</div>
        <div class="login__demo-row">
          <code>zhangchi</code><span>项目负责人（OWNER）</span>
        </div>
        <div class="login__demo-row">
          <code>liyang</code><span>美术（ARTIST）</span>
        </div>
      </div>
    </section>

    <!-- 右侧表单 -->
    <section class="login__panel">
      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @submit.prevent="handleLogin"
          >
            <el-form-item label="登录名" prop="username">
              <el-input
                v-model="loginForm.username"
                placeholder="请输入登录名"
                :prefix-icon="User"
                size="large"
                clearable
              />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                :prefix-icon="Lock"
                size="large"
                show-password
                @keyup.enter="handleLogin"
              />
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              class="login__submit"
              :loading="submitting"
              @click="handleLogin"
            >
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            @submit.prevent="handleRegister"
          >
            <el-form-item label="登录名" prop="username">
              <el-input
                v-model="registerForm.username"
                placeholder="字母、数字、下划线，3-50 位"
                :prefix-icon="User"
                clearable
              />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="6-32 位"
                :prefix-icon="Lock"
                show-password
              />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="请再次输入密码"
                :prefix-icon="Lock"
                show-password
              />
            </el-form-item>
            <el-form-item label="昵称（选填）" prop="nickname">
              <el-input v-model="registerForm.nickname" placeholder="显示名，默认取登录名" />
            </el-form-item>
            <el-form-item label="邮箱（选填）" prop="email">
              <el-input
                v-model="registerForm.email"
                placeholder="用于被邀请时按邮箱查找"
                :prefix-icon="Message"
              />
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              class="login__submit"
              :loading="submitting"
              @click="handleRegister"
            >
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <p class="login__tip muted">
        注册后不会自动加入任何项目，需要项目负责人在成员页邀请，或使用邀请码加入。
      </p>
    </section>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  min-height: 100vh;
  background: var(--bg-base);
}

.login__theme {
  position: absolute;
  top: 16px;
  right: 18px;
  z-index: 5;
}

/* ---------- 左侧品牌区 ---------- */

.login__intro {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 60px 68px;
  background:
    radial-gradient(1100px 520px at 8% 12%, var(--accent-soft), transparent 62%),
    var(--bg-panel);
  border-right: 1px solid var(--border);
}

.login__logo {
  width: 52px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: -0.5px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), #8f6fff);
  margin-bottom: 22px;
}

.login__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.4px;
}

.login__subtitle {
  margin: 8px 0 0;
  font-size: 15px;
  color: var(--text-muted);
}

.login__features {
  margin: 38px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 20px;
  max-width: 430px;
}

.login__features li {
  display: flex;
  gap: 12px;
}

.login__features strong {
  display: block;
  font-size: 14px;
}

.login__features p {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
}

.login__dot {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--accent);
}

.login__demo {
  margin-top: 40px;
  padding: 14px 16px;
  max-width: 430px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: var(--bg-card);
  font-size: 12.5px;
}

.login__demo-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.login__demo-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-muted);
  line-height: 1.9;
}

.login__demo-row code {
  color: var(--accent);
  font-weight: 600;
}

/* ---------- 右侧表单 ---------- */

.login__panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 60px 54px;
  max-width: 520px;
  width: 100%;
  justify-self: center;
}

.login__submit {
  width: 100%;
  margin-top: 6px;
}

.login__tip {
  margin-top: 18px;
  font-size: 12.5px;
  line-height: 1.7;
}

/* 窄屏时收起品牌区，只留表单，避免手机上左右两栏挤成一团 */
@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }

  .login__intro {
    display: none;
  }

  .login__panel {
    padding: 48px 24px;
  }
}
</style>
