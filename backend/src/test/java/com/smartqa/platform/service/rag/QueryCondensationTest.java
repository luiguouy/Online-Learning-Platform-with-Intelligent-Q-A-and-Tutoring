package com.smartqa.platform.service.rag;

import com.smartqa.platform.entity.QaRecord;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多轮对话 Query 指代消解与改写单元测试 (Query Condensation)
 */
class QueryCondensationTest {

    private ChatLanguageModel chatLanguageModel;
    private QueryCondensationService condensationService;

    @BeforeEach
    void setUp() {
        chatLanguageModel = mock(ChatLanguageModel.class);
        condensationService = new QueryCondensationService(chatLanguageModel);
    }

    @Test
    @DisplayName("空提问或纯空白直接原样返回")
    void blankQuestionReturnsDirectly() {
        assertNull(condensationService.condenseQuery(Collections.emptyList(), null));
        assertEquals("", condensationService.condenseQuery(Collections.emptyList(), ""));
        assertEquals("   ", condensationService.condenseQuery(Collections.emptyList(), "   "));
        verify(chatLanguageModel, never()).generate(any(UserMessage.class));
    }

    @Test
    @DisplayName("无历史记录时（首问）零开销直接返回原提问")
    void noHistoryReturnsOriginalQuestionWithoutLlmCall() {
        String question = "什么是死锁？";
        // null 历史
        assertEquals(question, condensationService.condenseQuery(null, question));
        // 空列表历史
        assertEquals(question, condensationService.condenseQuery(Collections.emptyList(), question));
        verify(chatLanguageModel, never()).generate(any(UserMessage.class));
    }

    @Test
    @DisplayName("历史记录全为空对象或空内容时直接返回原提问")
    void emptyHistoryRecordsReturnsOriginalQuestionWithoutLlmCall() {
        List<QaRecord> history = new ArrayList<>();
        history.add(new QaRecord());
        QaRecord r2 = new QaRecord();
        r2.setQuestion("");
        r2.setAnswer("  ");
        history.add(r2);

        String question = "那为什么不是两次？";
        assertEquals(question, condensationService.condenseQuery(history, question));
        verify(chatLanguageModel, never()).generate(any(UserMessage.class));
    }

    @Test
    @DisplayName("存在单轮历史记录时成功改写指代词并清洗多余引号")
    void singleTurnHistoryRewritesSuccessfully() {
        QaRecord r1 = new QaRecord();
        r1.setQuestion("TCP 三次握手过程是怎样的？");
        r1.setAnswer("第一次握手客户端发SYN，第二次握手服务端发SYN+ACK，第三次握手客户端发ACK。");
        List<QaRecord> history = List.of(r1);

        String rawLlmOutput = "“TCP 三次握手为什么不能改为两次握手？”";
        when(chatLanguageModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from(rawLlmOutput)));

        String currentQuestion = "那为什么不能是两次？";
        String condensed = condensationService.condenseQuery(history, currentQuestion);

        assertEquals("TCP 三次握手为什么不能改为两次握手？", condensed);

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(chatLanguageModel).generate(captor.capture());
        String sentPrompt = captor.getValue().singleText();
        assertTrue(sentPrompt.contains("TCP 三次握手过程是怎样的？"), "Prompt 应包含历史问题");
        assertTrue(sentPrompt.contains("那为什么不能是两次？"), "Prompt 应包含当前追问");
    }

    @Test
    @DisplayName("超过2轮历史时仅取最近2轮拼接")
    void historyTruncatedToLastTwoTurns() {
        QaRecord r1 = new QaRecord();
        r1.setQuestion("第一轮提问：操作系统简介");
        r1.setAnswer("操作系统是管理硬件和软件资源的系统程序。");

        QaRecord r2 = new QaRecord();
        r2.setQuestion("第二轮提问：什么是虚拟内存？");
        r2.setAnswer("虚拟内存使得程序认为其拥有连续的地址空间。");

        QaRecord r3 = new QaRecord();
        r3.setQuestion("第三轮提问：页面置换算法有哪些？");
        r3.setAnswer("包括 FIFO, LRU, OPT 等算法。");

        List<QaRecord> history = List.of(r1, r2, r3);

        when(chatLanguageModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("虚拟内存页面置换算法中的LRU算法具体实现原理")));

        String currentQuestion = "那LRU是怎么实现的？";
        String condensed = condensationService.condenseQuery(history, currentQuestion);

        assertEquals("虚拟内存页面置换算法中的LRU算法具体实现原理", condensed);

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(chatLanguageModel).generate(captor.capture());
        String sentPrompt = captor.getValue().singleText();
        assertTrue(!sentPrompt.contains("第一轮提问"), "第一轮历史提问应被截断抛弃");
        assertTrue(sentPrompt.contains("第二轮提问"), "第二轮提问应被保留");
        assertTrue(sentPrompt.contains("第三轮提问"), "第三轮提问应被保留");
    }

    @Test
    @DisplayName("大模型调用异常时优雅降级返回原提问")
    void llmExceptionFallsBackSafelyToOriginalQuestion() {
        QaRecord r1 = new QaRecord();
        r1.setQuestion("什么是死锁？");
        r1.setAnswer("死锁是指多个进程互相等待对方占有的资源。");
        List<QaRecord> history = List.of(r1);

        when(chatLanguageModel.generate(any(UserMessage.class)))
                .thenThrow(new RuntimeException("API Connection Timeout"));

        String question = "预防它的条件有哪些？";
        String condensed = condensationService.condenseQuery(history, question);

        // 绝不抛异常，且降级返回原提问
        assertEquals(question, condensed);
    }

    @Test
    @DisplayName("大模型返回空字符串时降级返回原提问")
    void emptyLlmOutputFallsBackToOriginalQuestion() {
        QaRecord r1 = new QaRecord();
        r1.setQuestion("什么是死锁？");
        r1.setAnswer("死锁说明");
        List<QaRecord> history = List.of(r1);

        when(chatLanguageModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("   \n   ")));

        String question = "产生条件是什么？";
        String condensed = condensationService.condenseQuery(history, question);

        assertEquals(question, condensed);
    }
}
