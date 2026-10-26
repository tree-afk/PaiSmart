<script setup lang="ts">
import { uploadAccept } from '@/constants/common';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });
const props = defineProps<{ documentId?: string | null }>();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

const docTypeOptions = [
  { label: '监管规定', value: 'REGULATION' },
  { label: '内部制度', value: 'INTERNAL_POLICY' },
  { label: '产品条款', value: 'PRODUCT_TERMS' },
  { label: '操作指引', value: 'GUIDELINE' }
];

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    documentId: props.documentId ?? null,
    docType: null,
    jurisdiction: '',
    issuer: '',
    applicableInstitutions: '',
    effectiveAt: '',
    expiresAt: '',
    repealed: false,
    fileList: []
  };
}

const rules = ref<FormRules>({
  docType: defaultRequiredRule,
  jurisdiction: defaultRequiredRule,
  issuer: defaultRequiredRule,
  fileList: defaultRequiredRule
});

const submitDisabled = computed(() => loading.value);

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
async function handleSubmit() {
  await validate();

  loading.value = true;
  await store.enqueueUpload(model.value);
  loading.value = false;
  close();
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    restoreValidation();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    :title="documentId ? '上传新版本' : '上传共享资料'"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NAlert type="info" :bordered="false" class="mb-12px">
      {{
        documentId
          ? '新版本处理期间旧版继续提供问答；处理完成并满足生效条件后自动切换。'
          : '资料处理完成后将进入共享知识库，供所有已登录的知识库用户问答使用。'
      }}
    </NAlert>
    <NForm
      ref="formRef"
      :model="model"
      :rules="rules"
      label-placement="left"
      :label-width="100"
      class="max-h-70vh overflow-y-auto pr-8px"
      mt-10
    >
      <NFormItem label="文档类型" path="docType">
        <NSelect v-model:value="model.docType" :options="docTypeOptions" placeholder="请选择文档类型" class="w-full" />
      </NFormItem>

      <NFormItem label="适用地区" path="jurisdiction">
        <NInput v-model:value="model.jurisdiction" placeholder="如：全国 / 上海" maxlength="100" />
        <div class="mt-8px text-12px text-gray-500">填“全国”表示全国适用；问地方问题时检索会自动带上全国资料。</div>
      </NFormItem>

      <NFormItem label="发布机构" path="issuer">
        <NInput v-model:value="model.issuer" placeholder="如：国家金融监督管理总局" maxlength="255" />
        <div class="mt-8px text-12px text-gray-500">只表示谁发布，不代表资料适用于谁。</div>
      </NFormItem>

      <NFormItem label="适用机构" path="applicableInstitutions">
        <NInput
          v-model:value="model.applicableInstitutions"
          placeholder="如：商业银行、保险公司；留空表示未标注"
          maxlength="512"
        />
      </NFormItem>

      <NFormItem label="生效日期" path="effectiveAt">
        <NDatePicker
          v-model:formatted-value="model.effectiveAt"
          value-format="yyyy-MM-dd"
          type="date"
          clearable
          class="w-full"
          placeholder="留空表示效力待确认"
        />
        <div class="mt-8px text-12px text-gray-500">
          留空表示效力待确认；未来日期会先完成构建，等到生效日才替换当前版本。
        </div>
      </NFormItem>

      <NFormItem label="失效日期" path="expiresAt">
        <NDatePicker
          v-model:formatted-value="model.expiresAt"
          value-format="yyyy-MM-dd"
          type="date"
          clearable
          class="w-full"
          placeholder="留空表示未标注失效"
        />
        <div class="mt-8px text-12px text-gray-500">已过失效日期的版本不会参与检索。</div>
      </NFormItem>

      <NFormItem label="废止标记" path="repealed">
        <NCheckbox v-model:checked="model.repealed">该版本已废止</NCheckbox>
        <div class="mt-8px text-12px text-gray-500">勾选后不会生效，也不会参与检索。</div>
      </NFormItem>

      <NFormItem label="上传文件" path="fileList">
        <NUpload
          v-model:file-list="model.fileList"
          :accept="uploadAccept"
          :max="1"
          :multiple="false"
          :default-upload="false"
        >
          <NButton>上传文件</NButton>
        </NUpload>
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" :disabled="submitDisabled" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
