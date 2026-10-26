<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NDataTable, NInput, NPagination, NSelect, NTag, useMessage } from 'naive-ui';
import { request } from '@/service/request';

defineOptions({ name: 'KnowledgeRoles' });

const message = useMessage();
const authStore = useAuthStore();

const loading = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const users = ref<Api.KnowledgeRole.User[]>([]);

const roleOptions: { label: string; value: Api.KnowledgeRole.Code }[] = [
  { label: '阅读者', value: 'READER' },
  { label: '文件管理者', value: 'FILE_MANAGER' },
  { label: '角色管理者', value: 'ROLE_MANAGER' }
];

const roleTagType: Record<Api.KnowledgeRole.Code, 'default' | 'info' | 'success'> = {
  READER: 'default',
  FILE_MANAGER: 'info',
  ROLE_MANAGER: 'success'
};

async function loadUsers() {
  loading.value = true;
  const { data, error } = await request<Api.KnowledgeRole.List>({
    url: '/knowledge-roles/users',
    params: { keyword: keyword.value || undefined, page: page.value, size: size.value }
  });
  loading.value = false;
  if (error || !data) return;
  users.value = data.content || [];
  total.value = data.totalElements || 0;
}

async function updateRole(user: Api.KnowledgeRole.User, role: Api.KnowledgeRole.Code) {
  if (role === user.knowledgeRole) return;
  const { data, error } = await request<Api.KnowledgeRole.User>({
    url: `/knowledge-roles/users/${user.id}`,
    method: 'put',
    data: { knowledgeRole: role }
  });
  if (error || !data) {
    await loadUsers();
    return;
  }
  message.success(`已将 ${user.username} 调整为${data.knowledgeRoleLabel}`);
  await loadUsers();
  if (user.id === authStore.userInfo.id) {
    await authStore.initUserInfo();
  }
}

const columns = computed(() => [
  { title: '用户名', key: 'username' },
  {
    title: '当前角色',
    key: 'knowledgeRole',
    render: (row: Api.KnowledgeRole.User) =>
      h(NTag, { type: roleTagType[row.knowledgeRole], bordered: false }, { default: () => row.knowledgeRoleLabel })
  },
  {
    title: '分配角色',
    key: 'action',
    width: 220,
    render: (row: Api.KnowledgeRole.User) =>
      h(NSelect, {
        value: row.knowledgeRole,
        options: roleOptions,
        class: 'w-180px',
        onUpdateValue: (value: Api.KnowledgeRole.Code) => updateRole(row, value)
      })
  }
]);

const rowKey = (row: Api.KnowledgeRole.User) => row.id;

onMounted(loadUsers);
</script>

<template>
  <div class="h-full flex-col gap-16px p-16px">
    <NCard title="账户知识库角色" :bordered="false">
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NInput v-model:value="keyword" clearable placeholder="搜索用户名" class="w-220px" @keyup.enter="loadUsers" />
          <NButton type="primary" :loading="loading" @click="loadUsers">查询</NButton>
        </div>
      </template>

      <NAlert type="info" :bordered="false" class="mb-12px">
        每个账户只有一个固定知识库角色。角色管理者只管理账户角色，不自动获得文件管理权限。
      </NAlert>

      <NDataTable :loading="loading" :columns="columns" :data="users" :row-key="rowKey" />

      <div class="mt-12px flex justify-end">
        <NPagination v-model:page="page" v-model:page-size="size" :item-count="total" @update:page="loadUsers" />
      </div>
    </NCard>
  </div>
</template>
