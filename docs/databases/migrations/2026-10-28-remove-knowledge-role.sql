-- 取消知识库角色体系，只保留 users.role（USER / ADMIN）
-- 原 FILE_MANAGER / ROLE_MANAGER 账号失去管理权限，统一按普通用户处理
ALTER TABLE users DROP COLUMN knowledge_role;
