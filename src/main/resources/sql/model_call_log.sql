-- =============================================================================
-- AI 作业批改：大模型调用日志表
--
-- 用途：记录 gradeSdk 接口每次对大模型的调用（入参 / 返回 / token 消耗 / 耗时 / 成败），
--      方便后续查看与统计。
--
-- 粒度：一行 = 一次大模型调用。一次 gradeSdk 请求包含两次调用（阶段一转录 + 阶段二批改），
--      两条记录共享同一个 request_id，用 GROUP BY request_id 即可聚合出"本次批改"的总消耗。
--
-- 图片入参：阶段一会向模型传图，但单张图最大 20MB、最多 8 张，base64 原文不宜直接入库，
--      因此只存图片元信息（image_meta：数量 / MIME / 字节数 / md5），不存 base64 原文。
--      如需事后回看原图，建议另存到磁盘 logs/images/{request_id}/，本表不承载原图。
--
-- 适配：以下 DDL 按 MySQL 语法编写；SQLite 需把 BIGINT AUTO_INCREMENT 改为
--      INTEGER PRIMARY KEY AUTOINCREMENT、把 MEDIUMTEXT 改为 TEXT、去掉索引里的长度。
-- =============================================================================

-- 调用级日志表（每次大模型调用一行）
CREATE TABLE IF NOT EXISTS model_call_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自增主键',
    request_id        VARCHAR(40)  NOT NULL                 COMMENT '一次 gradeSdk 请求的唯一标识(UUID)，两次调用共享',
    stage             VARCHAR(16)  NOT NULL                 COMMENT '阶段：transcribe(转录) / grade(批改)',
    model             VARCHAR(64)  NOT NULL                 COMMENT '模型名，如 qwen3.8-max',
    enable_thinking   BOOLEAN      NOT NULL DEFAULT FALSE   COMMENT '本次调用是否开启思考',
    request_prompt    MEDIUMTEXT            DEFAULT NULL    COMMENT '发给模型的文本提示词(阶段一=转录prompt;阶段二=批改prompt,{transcript}已替换)',
    image_count       INT          NOT NULL DEFAULT 0       COMMENT '本次调用携带图片数(阶段一>0,阶段二=0)',
    image_meta        TEXT                  DEFAULT NULL    COMMENT '图片元信息JSON:[{"index":1,"mime":"image/jpeg","bytes":123456,"md5":"..."}]',
    response_content  MEDIUMTEXT            DEFAULT NULL    COMMENT '模型返回正文(阶段一=转录全文;阶段二=批改JSON原文,解析前)',
    reasoning_content MEDIUMTEXT            DEFAULT NULL    COMMENT '思考过程(仅 enable_thinking=true 时有值)',
    prompt_tokens     INT                   DEFAULT NULL    COMMENT '输入 token',
    completion_tokens INT                   DEFAULT NULL    COMMENT '输出 token',
    total_tokens      INT                   DEFAULT NULL    COMMENT '合计 token',
    duration_ms       BIGINT                DEFAULT NULL    COMMENT '本次调用耗时(毫秒)',
    status            VARCHAR(16)  NOT NULL                 COMMENT 'success / error',
    error_message     TEXT                  DEFAULT NULL    COMMENT '失败时的错误信息',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    PRIMARY KEY (id),
    INDEX idx_call_request_id (request_id),
    INDEX idx_call_created_at (created_at),
    INDEX idx_call_model_stage (model, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型调用日志(调用级)';


-- =============================================================================
-- 可选：请求级汇总表（一张图一批改一行）
-- 不建也能用——下列信息都可从 model_call_log 按 request_id 聚合得到；
-- 若想要请求级直接查看，可启用本表，在 gradeSdk 入口写入、调用结束时回填结果。
-- =============================================================================
CREATE TABLE IF NOT EXISTS grade_request (
    request_id        VARCHAR(40)  NOT NULL                 COMMENT '一次 gradeSdk 请求的唯一标识(UUID)',
    image_count       INT          NOT NULL DEFAULT 0       COMMENT '上传图片数',
    show_thinking     BOOLEAN      NOT NULL DEFAULT TRUE    COMMENT '前端是否要求展示思考',
    enable_thinking   BOOLEAN      NOT NULL DEFAULT FALSE   COMMENT '阶段二是否开启思考(默认关闭)',
    total_tokens      INT                   DEFAULT NULL    COMMENT '两次调用 total_tokens 之和',
    total_duration_ms BIGINT                DEFAULT NULL    COMMENT '两次调用耗时之和(毫秒)',
    status            VARCHAR(16)  NOT NULL                 COMMENT 'success / error',
    error_message     TEXT                  DEFAULT NULL    COMMENT '失败时的错误信息',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求时间',
    PRIMARY KEY (request_id),
    INDEX idx_req_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批改请求汇总(请求级,可选)';


-- =============================================================================
-- 常用统计查询（参考）
-- =============================================================================
-- 每日 token 消耗
-- SELECT DATE(created_at) d, SUM(total_tokens) tokens, COUNT(*) calls
-- FROM model_call_log GROUP BY d ORDER BY d;

-- 每个模型 / 每个阶段的 token
-- SELECT model, stage, SUM(total_tokens) tokens, COUNT(*) calls
-- FROM model_call_log GROUP BY model, stage;

-- 每次批改（请求级）总消耗
-- SELECT request_id, SUM(total_tokens) tokens, SUM(duration_ms) ms, COUNT(*) calls
-- FROM model_call_log GROUP BY request_id ORDER BY MIN(created_at) DESC;

-- 成功率
-- SELECT status, COUNT(*) FROM model_call_log GROUP BY status;

-- 查某次请求的两次调用明细
-- SELECT stage, model, total_tokens, duration_ms, status, response_content
-- FROM model_call_log WHERE request_id = '<uuid>' ORDER BY id;
