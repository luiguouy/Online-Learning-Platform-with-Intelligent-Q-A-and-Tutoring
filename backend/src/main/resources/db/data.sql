-- ============================================================================
--  SmartQA 演示种子数据（成员 B / B1.8 交付物）
--  执行前提：先执行 schema.sql
--      mysql -u root -p smart_qa < data.sql
--
--  账号密码（明文，仅供本地联调）：
--      teacher01 / 123456   （教师）
--      student01 / 123456   （学生）
--      student02 / 123456   （学生）
--
--  密文由 BCrypt strength=10 生成，已用 checkpw 逐个校验通过。
--  Spring 的 BCryptPasswordEncoder.matches() 支持 $2b$ 前缀。
--  ⚠️ 不要手工改动下面这三串密文，改了就登不上去。
-- ============================================================================

USE smart_qa;

-- ---------------------------------------------------------------------------
-- 1. 用户（固定 id，后续脚本与联调都按这些 id 引用）
-- ---------------------------------------------------------------------------
INSERT INTO sys_user (id, username, password, nickname, role, avatar_url) VALUES
(1, 'teacher01', '$2b$10$mO81q57cLXNw0/KPyusNC.4RjXQh9qcoeRAPOWv32d.mS1g5FSkK2', '李老师', 'TEACHER', ''),
(2, 'student01', '$2b$10$mO81q57cLXNw0/KPyusNC.4RjXQh9qcoeRAPOWv32d.mS1g5FSkK2', '张小明', 'STUDENT', ''),
(3, 'student02', '$2b$10$mO81q57cLXNw0/KPyusNC.4RjXQh9qcoeRAPOWv32d.mS1g5FSkK2', '王小雨', 'STUDENT', '')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    nickname = VALUES(nickname),
    role = VALUES(role);

-- ---------------------------------------------------------------------------
-- 2. 课程（teacher_id = 1，全部归 teacher01，便于教师端演示）
-- ---------------------------------------------------------------------------
INSERT INTO course (id, course_name, course_code, teacher_id, description, cover_image) VALUES
(1, '操作系统原理', 'CS202401', 1, '进程管理、内存管理、文件系统与并发控制。', ''),
(2, '计算机网络', 'CS202402', 1, 'TCP/IP 协议栈、路由与拥塞控制、应用层协议。', ''),
(3, '数据库系统原理', 'CS202403', 1, '关系模型、SQL、事务与索引优化。', '')
ON DUPLICATE KEY UPDATE
    course_name = VALUES(course_name),
    teacher_id = VALUES(teacher_id),
    description = VALUES(description);

-- ---------------------------------------------------------------------------
-- 3. 课件
--    ⚠️ 演示占位数据：只有数据库记录、没有真实磁盘文件，
--       因此学生提问时检索不到它的内容（不是 Bug）。
--       成员 D 上传真实 PDF 后即可覆盖演示；不需要时直接删掉本段。
-- ---------------------------------------------------------------------------
INSERT INTO course_document (id, course_id, file_name, file_path, file_size, file_type, chunk_count, parse_status, error_msg) VALUES
(1, 1, '第1章_操作系统引论.pdf', '/tmp/smartqa-demo/第1章_操作系统引论.pdf', 0, 'pdf', 0, 'PENDING', '')
ON DUPLICATE KEY UPDATE
    course_id = VALUES(course_id),
    file_name = VALUES(file_name),
    parse_status = VALUES(parse_status);

-- ---------------------------------------------------------------------------
-- 4. 课程知识点（供成员 A 的知识点解析接口消费）
-- ---------------------------------------------------------------------------
INSERT INTO course_knowledge_point (id, course_id, chapter_name, title, summary) VALUES
(1, 1, '第2章 进程管理', '进程与线程的区别',
 '进程是资源分配的基本单位，拥有独立地址空间与 PCB；线程是 CPU 调度的基本单位，同一进程内线程共享地址空间、打开的文件与信号处理，只独享栈和寄存器上下文。因此线程切换开销远小于进程切换，但一个线程崩溃可能拖垮整个进程。'),
(2, 1, '第3章 内存管理', '页面置换算法 LRU 与 FIFO 对比',
 'FIFO 按进入内存的先后淘汰最老页面，实现简单但会出现 Belady 异常（分配更多页框反而缺页更多）。LRU 淘汰最久未被访问的页面，符合程序局部性原理，不会有 Belady 异常，但需要额外的访问时间戳或链表维护，硬件开销高，实际系统常用其近似算法如 Clock。'),
(3, 1, '第4章 文件系统', 'inode 与文件数据块的关系',
 'inode 存放文件的元数据（权限、大小、时间戳）以及指向数据块的索引指针，但不含文件名；文件名保存在目录项中，目录项把文件名映射到 inode 号。因此一个 inode 可以有多个文件名（硬链接），删除文件名只是减少 inode 的链接计数，计数归零且无进程打开时才真正释放数据块。'),
(4, 2, '第3章 传输层', 'TCP 三次握手与四次挥手',
 '三次握手用于双方同步初始序列号并确认收发能力：SYN → SYN+ACK → ACK，两次无法确认客户端接收能力，四次则多余。四次挥手因为 TCP 全双工，一方 FIN 只表示自己不再发送数据，仍可继续接收，故 ACK 与对端 FIN 通常分开发送；TIME_WAIT 等待 2MSL 是为了让最后的 ACK 有机会重传并让旧报文在网络中消散。')
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    chapter_name = VALUES(chapter_name),
    summary = VALUES(summary);

-- ---------------------------------------------------------------------------
-- 5. 问答记录演示数据
--    ⚠️ 仅用于让成员 D 的"问答记录查看页"首次打开时不是空表。
--       真实联调产生记录后可按需删除。
--       grounding_references 必须是合法 JSON，且字段名与 SseReferenceVO 一致。
-- ---------------------------------------------------------------------------
INSERT INTO qa_session (id, user_id, course_id, session_title) VALUES
(1, 2, 1, 'LRU 和 FIFO 该选哪个')
ON DUPLICATE KEY UPDATE
    session_title = VALUES(session_title);

INSERT INTO qa_record (id, session_id, user_id, course_id, question, answer,
                       grounding_references, feedback_rating, latency_ms) VALUES
(1, 1, 2, 1,
 '页面置换算法 LRU 和 FIFO 有什么区别？',
 '### 核心差异\n\n**FIFO** 按页面进入内存的先后顺序淘汰，实现最简单，但会出现 Belady 异常。\n\n**LRU** 淘汰最久未被访问的页面，符合程序局部性原理，不会出现 Belady 异常。\n\n### 代价对比\n\n| 算法 | 实现开销 | Belady 异常 | 命中率 |\n| --- | --- | --- | --- |\n| FIFO | 低（一个队列） | 会 | 较低 |\n| LRU | 高（需维护访问序） | 不会 | 较高 |\n\n实际系统常用 Clock 等近似算法来兼顾开销与命中率。',
 '[{"docId":12,"fileName":"第3章 内存管理.pdf","chunkIndex":14,"score":0.88,"snippet":"虚拟内存分页机制中，页表存储了虚页号与物理页框号的映射关系，当访问的页不在内存中时触发缺页中断，由置换算法决定淘汰哪一页。"}]',
 1, 1840)
ON DUPLICATE KEY UPDATE
    question = VALUES(question),
    answer = VALUES(answer),
    grounding_references = VALUES(grounding_references);

-- 自检
SELECT id, username, nickname, role FROM sys_user;
SELECT id, course_name, teacher_id FROM course;
