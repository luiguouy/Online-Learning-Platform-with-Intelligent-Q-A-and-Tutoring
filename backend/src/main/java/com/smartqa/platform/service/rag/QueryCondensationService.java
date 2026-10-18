package com.smartqa.platform.service.rag;

import com.smartqa.platform.entity.QaRecord;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 多轮对话 Query 指代消解与改写服务 (Query Condensation)
 *
 * 核心目标：
 * 在多轮对话中，学生的后续追问通常具有代词依赖或主语省略现象（如“那为什么不是两次？”）。
 * 本服务结合最近 1~2 轮问答历史，将追问改写为独立的、自包含的检索 Query，大幅提高向量召回率。
 *
 * 设计纪律：
 * 1. 零额外开销：若无问答历史或提问为空，直接跳过 LLM 调用返回原问题。
 * 2. 优雅降级：LLM 发生任何网络超时或异常时，记录警告日志并安全回退为原始提问，绝不阻断问答推流。
 * 3. 严格遵循 SEC-1 铁律：本改写仅提取提问语义，不引入任何权限或访问控制判断。
 */
@Service
public class QueryCondensationService {

    private static final Logger log = LoggerFactory.getLogger(QueryCondensationService.class);

    /** 最多回溯的历史问答轮数（控制上下文长度与改写延迟） */
    private static final int MAX_HISTORY_TURNS = 2;

    /** 单条历史回答注入 Prompt 的最大字符数截断，防止提示词臃肿 */
    private static final int MAX_ANSWER_PREVIEW_CHARS = 150;

    /**
     * 指代消解改写专用的轻量系统指令模板。
     * 强约束 LLM 仅输出一句话自包含检索词，严禁回答问题或添加解释。
     */
    private static final String CONDENSATION_PROMPT_TEMPLATE = """
            你是一个搜索查询改写专家。请根据以下对话历史，将学生的最新追问改写为一个语义完整、自包含的独立检索问题。
            
            【对话历史】
            %s
            
            【最新追问】
            %s
            
            【改写要求】
            1. 消除追问中的代词（如“它”、“这个”、“那”等）和主语省略，补全缺失的上下文背景
            2. 改写后的问题必须是一个独立的、可直接在知识库中检索出答案的完整问句
            3. 严禁回答学生的问题，严禁输出任何解释或问候语
            4. 直接输出改写后的单行问题，不要包含引号或多余格式
            """;

    private final ChatLanguageModel chatLanguageModel;

    public QueryCondensationService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 对多轮对话中的追问进行指代消解与改写
     *
     * @param history         当前会话历史问答记录列表（按时间升序）
     * @param currentQuestion 学生当前提问内容
     * @return 语义自包含的改写后检索 Query；若无历史或改写失败则返回原始提问
     */
    public String condenseQuery(List<QaRecord> history, String currentQuestion) {
        if (!StringUtils.hasText(currentQuestion)) {
            return currentQuestion;
        }

        // 首问或无历史：零开销直通返回
        if (history == null || history.isEmpty()) {
            return currentQuestion;
        }

        try {
            // 提取最近最多 MAX_HISTORY_TURNS 轮记录
            int fromIndex = Math.max(0, history.size() - MAX_HISTORY_TURNS);
            List<QaRecord> recentHistory = history.subList(fromIndex, history.size());

            StringBuilder historyText = new StringBuilder();
            boolean hasValidContent = false;
            int round = 1;
            for (QaRecord record : recentHistory) {
                if (record == null) {
                    continue;
                }
                String q = record.getQuestion() != null ? record.getQuestion().trim() : "";
                String a = record.getAnswer() != null ? record.getAnswer().trim() : "";
                if (q.isEmpty() && a.isEmpty()) {
                    continue;
                }
                hasValidContent = true;
                if (a.length() > MAX_ANSWER_PREVIEW_CHARS) {
                    a = a.substring(0, MAX_ANSWER_PREVIEW_CHARS) + "...";
                }
                historyText.append(String.format("第%d轮 学生: %s\n第%d轮 助手: %s\n", round, q, round, a));
                round++;
            }

            // 若历史问答全无实质内容，无需改写
            if (!hasValidContent) {
                return currentQuestion;
            }

            String prompt = String.format(CONDENSATION_PROMPT_TEMPLATE,
                    historyText.toString().trim(), currentQuestion.trim());

            log.debug("[QueryCondensation] 开始执行指代消解改写，原问题: {}", currentQuestion);
            String rewritten = chatLanguageModel.generate(UserMessage.from(prompt)).content().text();

            if (StringUtils.hasText(rewritten)) {
                // 清洗可能的多余首尾引号和换行
                String cleaned = cleanRewrittenQuery(rewritten);
                if (StringUtils.hasText(cleaned)) {
                    log.info("[QueryCondensation] Query改写完成: 原问题='{}' -> 改写后='{}'",
                            currentQuestion, cleaned);
                    return cleaned;
                }
            }

            log.warn("[QueryCondensation] 模型改写结果为空，回退原问题: '{}'", currentQuestion);
            return currentQuestion;

        } catch (Throwable t) {
            // 优雅降级：LLM 调用网络故障或超时等异常绝不阻断问答推流
            log.warn("[QueryCondensation] 指代消解调用异常，降级使用原问题: '{}', 错误原因: {}",
                    currentQuestion, t.getMessage());
            return currentQuestion;
        }
    }

    /**
     * 清洗大模型输出（去除包裹引号、前后空白、换行符等）
     */
    private String cleanRewrittenQuery(String text) {
        String cleaned = text.trim();
        // 去除包裹的双引号或单引号
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
            (cleaned.startsWith("“") && cleaned.endsWith("”")) ||
            (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        // 如果包含多行，只保留首行
        int newlineIdx = cleaned.indexOf('\n');
        if (newlineIdx > 0) {
            cleaned = cleaned.substring(0, newlineIdx).trim();
        }
        return cleaned;
    }
}
