-- ============================================================================
--  SmartQA 数据库初始化脚本（成员 B / B1.1 交付物）
--  目标库：MySQL 8.0
--  执行方式：
--      mysql -u root -p < schema.sql
--      mysql -u root -p < data.sql
--
--  ⚠️ 6 张表全部带 is_deleted 列。
--     application.yml 配了全局逻辑删除 logic-delete-field: isDeleted，
--     缺列的表一旦调用 removeById() 会直接抛 Unknown column 'is_deleted'。
--     后续改动 DDL 时严禁漏掉任一列。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS smart_qa
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE smart_qa;

-- 1. 用户信息表 ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '登录学号/工号',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    nickname VARCHAR(50) NOT NULL COMMENT '用户真实姓名',
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT-学生, TEACHER-教师',
    avatar_url VARCHAR(255) DEFAULT '' COMMENT '头像地址',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 课程表 ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课程ID',
    course_name VARCHAR(100) NOT NULL COMMENT '课程名称 (如: 操作系统原理)',
    course_code VARCHAR(50) NOT NULL UNIQUE COMMENT '课程编号 (如: CS202401)',
    teacher_id BIGINT NOT NULL COMMENT '任课教师ID',
    description VARCHAR(500) DEFAULT '' COMMENT '课程简介',
    cover_image VARCHAR(255) DEFAULT '' COMMENT '课程封面图',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_teacher (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程主表';

-- 3. 课程课件资料表 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课件ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    file_name VARCHAR(200) NOT NULL COMMENT '课件文件名 (如: 第3章_虚拟内存管理.pdf)',
    file_path VARCHAR(500) NOT NULL COMMENT '磁盘存储绝对路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节大小',
    file_type VARCHAR(20) NOT NULL COMMENT '文件格式 (pdf, docx, md, txt)',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '切块片段总数',
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-排队中, PARSING-切片中, CHUNKED-已就绪, FAILED-失败',
    error_msg VARCHAR(500) DEFAULT '' COMMENT '解析失败原因',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_course_doc (course_id, parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程课件资料表';

-- 4. 智能问答会话表 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS qa_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '提问学生ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    session_title VARCHAR(100) NOT NULL DEFAULT '新建答疑会话' COMMENT '会话标题',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_course_session (user_id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能问答会话表';

-- 5. 智能问答明细记录表 -------------------------------------------------------
--    纯只读记录表：功能范围已冻结，不做人工纠偏，
--    因此没有 is_corrected / corrected_answer / teacher_comment 字段。
CREATE TABLE IF NOT EXISTS qa_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    session_id BIGINT NOT NULL COMMENT '所属会话ID',
    user_id BIGINT NOT NULL COMMENT '提问学生ID',
    course_id BIGINT NOT NULL COMMENT '所属课程ID',
    question TEXT NOT NULL COMMENT '学生提问内容',
    answer LONGTEXT NOT NULL COMMENT 'AI生成的Markdown回答',
    grounding_references JSON NULL COMMENT '命中的课件出处快照 (JSON数组)',
    feedback_rating TINYINT DEFAULT 0 COMMENT '学生打分: 1-点赞, -1-点踩, 0-未评',
    latency_ms INT DEFAULT 0 COMMENT '模型生成耗时(毫秒)',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_record (session_id),
    INDEX idx_course_record (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答明细记录表';

-- 6. 课程核心知识点与考点库表 -------------------------------------------------
CREATE TABLE IF NOT EXISTS course_knowledge_point (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识点ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    chapter_name VARCHAR(100) NOT NULL COMMENT '所属章节',
    title VARCHAR(150) NOT NULL COMMENT '知识点标题 (如: 页面置换算法LRU与FIFO对比)',
    summary TEXT NOT NULL COMMENT '核心精解摘要',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_course_point (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程知识点库表';

-- 自检：应输出 6 行
SHOW TABLES;
