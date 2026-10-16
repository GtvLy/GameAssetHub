<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { CopyDocument, Delete, Plus, Refresh, UserFilled } from '@element-plus/icons-vue'
import { fetchMembers, inviteMember, removeMember, updateMemberRole } from '@/api/project'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import { MEMBER_STATUSES, PROJECT_ROLES, roleMeta } from '@/constants'
import type { MemberInviteDTO, ProjectMemberVO, ProjectRole } from '@/types'

/**
 * 成员与角色管理。
 *
 * 权限说明：
 *   - 「邀请 / 改角色 / 移除」按钮仅对 OWNER 显示。这只是体验优化，
 *     真正的拦截在后端（MEMBER_MANAGE 权限 + 服务层的唯一负责人校验），
 *     前端隐藏按钮不作为安全边界。
 *   - 邀请码只有 OWNER 能看到（后端对非 OWNER 直接返回 null，
 *     不是靠前端不渲染）。
 */
const route = useRoute()
const projectStore = useProjectStore()
const userStore = useUserStore()

const projectId = computed(() => Number(route.params.projectId))
const canManage = computed(() => projectStore.canManage)
const currentUserId = computed(() => userStore.user?.id)

const loading = ref(false)
const members = ref<ProjectMemberVO[]>([])

const inviteVisible = ref(false)
const submitting = ref(false)
const inviteFormRef = ref<FormInstance>()

const inviteForm = reactive<MemberInviteDTO>({
  account: '',
  role: 'ARTIST'
})

/** 刚生成的邀请码，弹窗里高亮展示并支持一键复制 */
const generatedInvite = ref<{ nickname: string; code: string } | null>(null)

const inviteRules: FormRules = {
  account: [{ required: true, message: '请输入对方的用户名或邮箱', trigger: 'blur' }],
  role: [{ required: true, message: '请选择成员角色', trigger: 'change' }]
}

const activeMembers = computed(() => members.value.filter((m) => m.status === 'ACTIVE'))
const invitedMembers = computed(() => members.value.filter((m) => m.status === 'INVITED'))

const statusMeta = (status: string) =>
  MEMBER_STATUSES[status as keyof typeof MEMBER_STATUSES] ?? { label: status, tagType: 'info' as const }

/** 该成员是否是项目里唯一的 ACTIVE 负责人（前端预判，用于给出更早的提示；后端仍会拦） */
const isLastOwner = (member: ProjectMemberVO) =>
  member.role === 'OWNER' &&
  member.status === 'ACTIVE' &&
  activeMembers.value.filter((m) => m.role === 'OWNER').length <= 1

async function load() {
  loading.value = true
  try {
    members.value = await fetchMembers(projectId.value)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openInvite() {
  inviteForm.account = ''
  inviteForm.role = 'ARTIST'
  generatedInvite.value = null
  inviteVisible.value = true
}

async function submitInvite() {
  const form = inviteFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const member = await inviteMember(projectId.value, {
      account: inviteForm.account.trim(),
      role: inviteForm.role
    })
    generatedInvite.value = {
      nickname: member.nickname || member.username || inviteForm.account.trim(),
      code: member.inviteCode || ''
    }
    ElMessage.success('邀请已生成')
    await load()
  } catch {
    /* 拦截器已提示（用户不存在 / 已是成员 / 非 OWNER） */
  } finally {
    submitting.value = false
  }
}

async function copyCode(code: string) {
  try {
    // navigator.clipboard 在非 HTTPS 的 localhost 之外不可用，降级到 execCommand
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = code
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success('邀请码已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

async function handleRoleChange(member: ProjectMemberVO, nextRole: ProjectRole) {
  const previous = member.role
  try {
    await updateMemberRole(projectId.value, member.userId, nextRole)
    ElMessage.success(`已将「${member.nickname || member.username}」的角色改为 ${roleMeta(nextRole).label}`)
    await load()
  } catch {
    // 失败时回滚下拉框显示，否则界面会停留在"看起来改成功了"的状态
    member.role = previous
  }
}

async function handleRemove(member: ProjectMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确定要把「${member.nickname || member.username}」从项目中移除吗？` +
        '移除后该成员将无法再访问本项目的任何数据，但历史操作记录会保留。',
      '移除成员',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await removeMember(projectId.value, member.userId)
    ElMessage.success('成员已移除')
    await load()
  } catch {
    /* 拦截器已提示（唯一负责人不可移除等） */
  }
}

/** 角色下拉的可选项：OWNER 只能由 OWNER 授予，且不能被自己以外的人改动 */
function roleOptions(member: ProjectMemberVO) {
  return PROJECT_ROLES.filter((r) => {
    if (r.value === 'OWNER' && !canManage.value) return false
    return true
  }).map((r) => ({ value: r.value, label: r.label, description: r.description }))
}
</script>

<template>
  <div class="members">
    <div class="members__toolbar">
      <div>
        <h2 class="members__title">成员与角色</h2>
        <p class="muted members__desc">
          {{ activeMembers.length }} 名已加入<template v-if="invitedMembers.length">
            ，{{ invitedMembers.length }} 名待加入</template
          >。角色决定成员在本项目内的权限范围。
        </p>
      </div>

      <div class="members__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openInvite">
          邀请成员
        </el-button>
        <el-tooltip v-else content="仅项目负责人（OWNER）可管理成员" placement="bottom">
          <el-tag type="info" effect="plain">只读视角</el-tag>
        </el-tooltip>
      </div>
    </div>

    <div v-loading="loading" class="panel">
      <el-table :data="members" style="width: 100%" empty-text="暂无成员">
        <el-table-column label="成员" min-width="200">
          <template #default="{ row }">
            <div class="member">
              <el-avatar :size="32" class="member__avatar">
                {{ (row.nickname || row.username || '?').charAt(0).toUpperCase() }}
              </el-avatar>
              <div class="member__info">
                <div class="member__name">
                  {{ row.nickname || row.username }}
                  <el-tag v-if="row.userId === currentUserId" size="small" effect="plain" type="primary">
                    我
                  </el-tag>
                </div>
                <div class="member__sub muted">
                  <span class="mono">@{{ row.username }}</span>
                  <span v-if="row.email"> · {{ row.email }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="角色" width="190">
          <template #default="{ row }">
            <el-select
              v-if="canManage"
              :model-value="row.role"
              size="small"
              :disabled="isLastOwner(row)"
              style="width: 100%"
              @change="(value: ProjectRole) => handleRoleChange(row, value)"
            >
              <el-option
                v-for="option in roleOptions(row)"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              >
                <div class="role-option">
                  <span>{{ option.label }}</span>
                  <span class="muted role-option__desc">{{ option.description }}</span>
                </div>
              </el-option>
            </el-select>
            <el-tag v-else :type="roleMeta(row.role).tagType" effect="plain" size="small">
              {{ row.roleLabel || roleMeta(row.role).label }}
            </el-tag>
            <div v-if="canManage && isLastOwner(row)" class="member__warn muted">
              项目唯一负责人，不可降级
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).tagType" size="small" effect="light">
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="邀请码" min-width="190">
          <template #default="{ row }">
            <div v-if="row.inviteCode" class="invite-cell">
              <code class="invite-cell__code">{{ row.inviteCode }}</code>
              <el-button
                text
                size="small"
                :icon="CopyDocument"
                title="复制邀请码"
                @click="copyCode(row.inviteCode!)"
              />
            </div>
            <span v-else-if="row.status === 'ACTIVE'" class="muted">—</span>
            <span v-else class="muted">邀请码不可见</span>
          </template>
        </el-table-column>

        <el-table-column label="加入时间" width="170">
          <template #default="{ row }">
            <span class="muted">{{ row.joinTime || '尚未加入' }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="canManage" label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!isLastOwner(row)"
              content="项目至少需要保留一名负责人，无法移除唯一负责人"
              placement="top"
            >
              <span>
                <el-button
                  text
                  type="danger"
                  size="small"
                  :icon="Delete"
                  :disabled="isLastOwner(row)"
                  @click="handleRemove(row)"
                >
                  移除
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 角色权限说明：把后端 3.3 的权限矩阵如实呈现，避免成员对"为什么我点不了"产生疑问 -->
    <div class="panel">
      <div class="panel-title">
        <span>角色权限矩阵</span>
        <span class="muted matrix__hint">与后端 RolePermissionMap 一致，越权由服务端拦截</span>
      </div>
      <div class="panel-body">
        <el-table :data="PROJECT_ROLES" size="small" style="width: 100%">
          <el-table-column prop="label" label="角色" width="130">
            <template #default="{ row }">
              <el-tag :type="row.tagType" size="small" effect="plain">{{ row.label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="权限范围" />
        </el-table>
      </div>
    </div>

    <!-- 邀请成员 -->
    <el-dialog v-model="inviteVisible" title="邀请成员" width="520px" append-to-body>
      <template v-if="!generatedInvite">
        <el-form ref="inviteFormRef" :model="inviteForm" :rules="inviteRules" label-width="88px">
          <el-form-item label="用户名/邮箱" prop="account">
            <el-input
              v-model="inviteForm.account"
              placeholder="对方注册时使用的用户名或邮箱"
              :prefix-icon="UserFilled"
              @keyup.enter="submitInvite"
            />
          </el-form-item>
          <el-form-item label="角色" prop="role">
            <el-select v-model="inviteForm.role" style="width: 100%">
              <el-option
                v-for="option in PROJECT_ROLES"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              >
                <div class="role-option">
                  <span>{{ option.label }}</span>
                  <span class="muted role-option__desc">{{ option.description }}</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
        </el-form>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="对方需要先注册账号，并告诉你注册用的用户名或邮箱。"
          description="提交后会生成一次性邀请码，把它发给对方，由对方在「凭邀请码加入」中兑换。"
        />
      </template>

      <!-- 生成结果：突出邀请码本身，这是这个弹窗唯一要交付的东西 -->
      <div v-else class="invite-result">
        <el-icon :size="30" color="var(--success)"><UserFilled /></el-icon>
        <p class="invite-result__title">已为「{{ generatedInvite.nickname }}」生成邀请码</p>
        <p class="muted invite-result__sub">把下面的邀请码发给对方，由对方登录后自行加入项目：</p>

        <div class="invite-result__code">
          <code>{{ generatedInvite.code }}</code>
          <el-button type="primary" :icon="CopyDocument" @click="copyCode(generatedInvite.code)">
            复制
          </el-button>
        </div>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="邀请码是一次性的，被使用后立即失效。"
        />
      </div>

      <template #footer>
        <template v-if="!generatedInvite">
          <el-button @click="inviteVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitInvite">生成邀请码</el-button>
        </template>
        <el-button v-else type="primary" @click="inviteVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.members {
  display: grid;
  gap: 18px;
}

.members__toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.members__title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}

.members__desc {
  margin: 6px 0 0;
  font-size: 13px;
}

.members__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.member {
  display: flex;
  align-items: center;
  gap: 11px;
}

.member__avatar {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  flex: none;
}

.member__info {
  min-width: 0;
}

.member__name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13.5px;
}

.member__sub {
  font-size: 11.5px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member__warn {
  margin-top: 3px;
  font-size: 11px;
}

.invite-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.invite-cell__code {
  font-size: 11.5px;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  color: var(--accent);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.role-option__desc {
  font-size: 11.5px;
}

.matrix__hint {
  font-size: 12px;
  font-weight: 400;
}

/* ---------- 邀请结果 ---------- */

.invite-result {
  text-align: center;
  padding: 10px 4px 4px;
}

.invite-result__title {
  margin: 12px 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.invite-result__sub {
  margin: 0 0 18px;
  font-size: 12.5px;
}

.invite-result__code {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  border: 1px dashed var(--accent);
  border-radius: 10px;
  background: var(--accent-soft);
}

.invite-result__code code {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.4px;
  word-break: break-all;
  text-align: left;
  color: var(--accent);
}
</style>
