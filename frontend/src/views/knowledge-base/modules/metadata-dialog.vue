<script setup lang="ts">
import {
  NAlert,
  NButton,
  NCheckbox,
  NDatePicker,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  useMessage
} from 'naive-ui';

defineOptions({ name: 'MetadataDialog' });

const visible = defineModel<boolean>('visible', { default: false });
const props = defineProps<{ row: Api.KnowledgeBase.UploadTask | null }>();
const emit = defineEmits<{ saved: [] }>();

const message = useMessage();

const docTypeOptions = [
  { label: '监管规定', value: 'REGULATION' },
  { label: '内部制度', value: 'INTERNAL_POLICY' },
  { label: '产品条款', value: 'PRODUCT_TERMS' },
  { label: '操作指引', value: 'GUIDELINE' }
];

const form = ref({
  docType: null as string | null,
  jurisdiction: '',
  issuer: '',
  applicableInstitutions: '',
  effectiveAt: '',
  expiresAt: '',
  repealed: false
});

watch(visible, value => {
  if (!value || !props.row) return;
  form.value = {
    docType: props.row.docType ?? null,
    jurisdiction: props.row.jurisdiction ?? '',
    issuer: props.row.issuer ?? '',
    applicableInstitutions: props.row.applicableInstitutions ?? '',
    effectiveAt: props.row.effectiveAt ?? '',
    expiresAt: props.row.expiresAt ?? '',
    repealed: Boolean(props.row.repealed)
  };
});

async function submit() {
  if (!props.row?.documentId) {
    message.error('该资料没有业务文档 ID，无法修订元数据');
    return;
  }
  const { error } = await request({
    url: `/documents/${props.row.documentId}/metadata`,
    method: 'patch',
    data: {
      docType: form.value.docType,
      jurisdiction: form.value.jurisdiction || null,
      issuer: form.value.issuer || null,
      applicableInstitutions: form.value.applicableInstitutions || null,
      effectiveAt: form.value.effectiveAt || null,
      expiresAt: form.value.expiresAt || null,
      repealed: form.value.repealed
    }
  });
  if (error) return;
  message.success('已提交元数据修订，重建完成前该资料暂停检索');
  visible.value = false;
  emit('saved');
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="修订资料信息"
    :show-icon="false"
    :mask-closable="false"
    class="w-520px!"
  >
    <NAlert type="warning" :bordered="false" class="mb-12px">
      修订会先暂停该文档的问答检索，重建成功后按有效期恢复。重建期间请不要重复提交修订。
    </NAlert>
    <NForm label-placement="left" :label-width="96">
      <NFormItem label="文档类型">
        <NSelect v-model:value="form.docType" :options="docTypeOptions" clearable class="w-full" />
      </NFormItem>
      <NFormItem label="适用地区">
        <NInput v-model:value="form.jurisdiction" maxlength="100" placeholder="如：全国 / 上海" />
      </NFormItem>
      <NFormItem label="发布机构">
        <NInput v-model:value="form.issuer" maxlength="255" placeholder="谁发布，不代表适用于谁" />
      </NFormItem>
      <NFormItem label="适用机构">
        <NInput v-model:value="form.applicableInstitutions" maxlength="512" placeholder="留空表示未标注" />
      </NFormItem>
      <NFormItem label="生效日期">
        <NDatePicker
          v-model:formatted-value="form.effectiveAt"
          value-format="yyyy-MM-dd"
          type="date"
          clearable
          class="w-full"
          placeholder="留空表示效力待确认"
        />
      </NFormItem>
      <NFormItem label="失效日期">
        <NDatePicker
          v-model:formatted-value="form.expiresAt"
          value-format="yyyy-MM-dd"
          type="date"
          clearable
          class="w-full"
          placeholder="留空表示未标注失效"
        />
      </NFormItem>
      <NFormItem label="废止标记">
        <NCheckbox v-model:checked="form.repealed">该版本已废止</NCheckbox>
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="visible = false">取消</NButton>
      <NButton type="primary" @click="submit">提交修订</NButton>
    </template>
  </NModal>
</template>
